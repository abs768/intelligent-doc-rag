import React, { useRef } from 'react';

export default function Sidebar({
  documents,
  selectedDocs,
  currentUser,
  theme,
  isLoading,
  uploadProgress,
  onUpload,
  onDeleteDoc,
  onDeleteAll,
  onToggleDoc,
  onClearChat,
  onToggleTheme,
  onLogout,
}) {
  const fileInputRef = useRef(null);

  const handleFileChange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    onUpload(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleDeleteDoc = (documentId, e) => {
    e.stopPropagation();
    const doc = documents.find((d) => d.documentId === documentId);
    if (!window.confirm(`Delete "${doc?.filename}"?`)) return;
    onDeleteDoc(documentId);
  };

  const handleDeleteAll = () => {
    if (!window.confirm('Delete ALL documents? This cannot be undone.')) return;
    onDeleteAll();
  };

  // Status badge renderer
  const renderStatusBadge = (status) => {
    switch (status) {
      case 'PROCESSING':
        return <span className="status-badge processing" title="Embedding in progress...">Processing...</span>;
      case 'FAILED':
        return <span className="status-badge failed" title="Ingestion failed">Failed</span>;
      case 'INGESTED':
        return (
          <svg className="check-icon status-ready" width="14" height="14" viewBox="0 0 24 24"
               fill="none" stroke="currentColor" strokeWidth="3" title="Ready">
            <path d="M20 6L9 17l-5-5" />
          </svg>
        );
      default:
        return null;
    }
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <button onClick={onClearChat} className="new-chat-btn">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M12 5v14M5 12h14" />
          </svg>
          New chat
        </button>
      </div>

      <div className="sidebar-content">
        <div className="upload-section">
          <h3>Documents</h3>
          <div className="file-input-wrapper">
            <input
              ref={fileInputRef}
              type="file"
              accept=".txt,.pdf"
              onChange={handleFileChange}
              className="file-input"
              id="file-upload"
              disabled={uploadProgress !== null}
            />
            <label htmlFor="file-upload" className="file-label">
              {uploadProgress !== null ? `Uploading: ${uploadProgress}%` : 'Choose file...'}
            </label>
          </div>

          {/* Upload Progress Bar */}
          {uploadProgress !== null && (
            <div className="upload-progress-bar">
              <div
                className="upload-progress-fill"
                style={{ width: `${uploadProgress}%` }}
              />
            </div>
          )}
        </div>

        {documents.length > 0 && (
          <div className="documents-section">
            <div className="docs-header">
              <span>{documents.length} document(s)</span>
              <button onClick={handleDeleteAll} className="delete-all-btn" title="Delete all">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
                </svg>
              </button>
            </div>

            {documents.map((doc) => {
              const isProcessing = doc.status === 'PROCESSING';
              const isClickable = doc.status === 'INGESTED';

              return (
                <div
                  key={doc.documentId}
                  className={`doc-item ${selectedDocs.includes(doc.documentId) ? 'selected' : ''} ${isProcessing ? 'processing' : ''}`}
                  onClick={() => isClickable && onToggleDoc(doc.documentId)}
                  title={isProcessing ? `${doc.filename} — embedding in progress` : doc.filename}
                  style={{ cursor: isClickable ? 'pointer' : 'default' }}
                >
                  <div className="doc-info">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
                      <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" />
                    </svg>
                    <span className="doc-name">{doc.filename}</span>
                  </div>
                  <div className="doc-actions">
                    {renderStatusBadge(doc.status)}
                    {selectedDocs.includes(doc.documentId) && doc.status === 'INGESTED' && (
                      <svg className="check-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
                        <path d="M20 6L9 17l-5-5" />
                      </svg>
                    )}
                    <button
                      className="delete-doc-btn"
                      onClick={(e) => handleDeleteDoc(doc.documentId, e)}
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
                      </svg>
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="sidebar-footer">
        <div className="user-info">
          <div className="user-avatar">{currentUser?.name?.charAt(0) || 'U'}</div>
          <div className="user-details">
            <div className="user-name">{currentUser?.name}</div>
            <div className="user-email">{currentUser?.email}</div>
          </div>
        </div>
        <button className="theme-toggle" onClick={onToggleTheme}>
          {theme === 'light' ? (
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
            </svg>
          ) : (
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="5" />
              <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
            </svg>
          )}
          {theme === 'light' ? 'Dark' : 'Light'}
        </button>
        <button className="logout-btn" onClick={onLogout}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9" />
          </svg>
          Logout
        </button>
      </div>
    </aside>
  );
}
