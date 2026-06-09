// Centralized API client for the DocGPT backend.
// Every fetch call goes through here — single place to change the base URL or auth logic.

const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080';

function getAuthHeaders(contentType = 'application/json') {
  const token = localStorage.getItem('token');
  const headers = { Authorization: `Bearer ${token}` };
  if (contentType) headers['Content-Type'] = contentType;
  return headers;
}

// ======================== AUTH ========================

export async function register(email, password, name) {
  const res = await fetch(`${API_BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, name }),
  });
  if (!res.ok) throw new Error('Registration failed. Email may already be taken.');
  return res.json();
}

export async function login(email, password) {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error('Invalid email or password');
  return res.json();
}

// ======================== DOCUMENTS ========================

export async function listDocuments() {
  const res = await fetch(`${API_BASE}/api/documents/list`, {
    headers: getAuthHeaders(),
  });
  if (!res.ok) throw new Error('Failed to load documents');
  return res.json();
}

/**
 * Upload a file using chunked upload for large files (>5MB) or direct upload for small ones.
 * @param {File} file - The file to upload
 * @param {function} onProgress - Callback with progress percentage (0-100)
 * @returns {Promise<object>} - Upload response with documentId, filename, message
 */
export async function uploadDocument(file, onProgress) {
  const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB

  // Small files: use original single-request upload
  if (file.size <= CHUNK_SIZE) {
    if (onProgress) onProgress(50);
    const formData = new FormData();
    formData.append('file', file);

    const res = await fetch(`${API_BASE}/api/documents/upload`, {
      method: 'POST',
      headers: getAuthHeaders(null),
      body: formData,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || err.error || res.statusText);
    }
    if (onProgress) onProgress(100);
    return res.json();
  }

  // Large files: chunked upload
  const uploadId = crypto.randomUUID();
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

  for (let i = 0; i < totalChunks; i++) {
    const start = i * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, file.size);
    const blob = file.slice(start, end);

    const formData = new FormData();
    formData.append('uploadId', uploadId);
    formData.append('chunk', blob, `chunk-${i}`);

    const res = await fetch(`${API_BASE}/api/documents/upload-chunk`, {
      method: 'POST',
      headers: getAuthHeaders(null),
      body: formData,
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || err.error || 'Chunk upload failed');
    }

    if (onProgress) {
      const pct = Math.round(((i + 1) / totalChunks) * 90); // 0-90% for chunks
      onProgress(pct);
    }
  }

  // Finalize — server responds 202 and starts async embedding
  const params = new URLSearchParams({ uploadId, filename: file.name });
  const res = await fetch(`${API_BASE}/api/documents/upload-finalize?${params}`, {
    method: 'POST',
    headers: getAuthHeaders(null),
  });

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || err.error || 'Finalize failed');
  }

  if (onProgress) onProgress(100);
  return res.json();
}

export async function deleteDocument(documentId) {
  const res = await fetch(`${API_BASE}/api/documents/${documentId}`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  });
  if (!res.ok) throw new Error('Delete failed');
  return res.json();
}

export async function deleteAllDocuments() {
  const res = await fetch(`${API_BASE}/api/documents/all`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  });
  if (!res.ok) throw new Error('Delete all failed');
  return res.json();
}

// ======================== CHAT ========================

export async function sendChatMessage({ conversationId, message, selectedDocuments, useRag, persona, language }) {
  const res = await fetch(`${API_BASE}/api/chat`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify({
      conversationId: useRag ? null : conversationId,
      message,
      selectedDocuments: useRag ? selectedDocuments : [],
      useRag,
      persona: persona || 'Analyst',
      language: language || 'English',
    }),
  });
  if (res.status === 401 || res.status === 403) throw new Error('UNAUTHORIZED');
  if (!res.ok) throw new Error(res.statusText);
  return res.json();
}
