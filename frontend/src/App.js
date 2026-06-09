import React, { useState, useEffect, useCallback, useRef } from 'react';
import * as api from './api';
import AuthPage from './components/AuthPage';
import Sidebar from './components/Sidebar';
import ChatInterface from './components/ChatInterface';
import './App.css';

export default function App() {
  // ======================== AUTH STATE ========================
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [currentUser, setCurrentUser] = useState(null);

  // ======================== CHAT STATE ========================
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState(null);
  const [isLoading, setIsLoading] = useState(false);

  // ======================== DOCUMENT STATE ========================
  const [documents, setDocuments] = useState([]);
  const [selectedDocs, setSelectedDocs] = useState([]);
  const [uploadProgress, setUploadProgress] = useState(null); // null = no upload, 0-100 = progress

  // ======================== MODULE 3: PERSONA + LANGUAGE ========================
  const [persona, setPersona] = useState('Analyst');
  const [language, setLanguage] = useState('English');

  // ======================== THEME ========================
  const [theme, setTheme] = useState('light');

  // Polling ref for async status checks
  const pollingRef = useRef(null);

  // ======================== INIT ========================

  useEffect(() => {
    const token = localStorage.getItem('token');
    const user = localStorage.getItem('user');
    if (token && user) {
      setIsAuthenticated(true);
      setCurrentUser(JSON.parse(user));
      loadDocuments();
    }
    const savedTheme = localStorage.getItem('theme') || 'light';
    setTheme(savedTheme);
    document.documentElement.setAttribute('data-theme', savedTheme);

    return () => { if (pollingRef.current) clearInterval(pollingRef.current); };
  }, []);

  // ======================== AUTH HANDLERS ========================

  const handleLogin = (data) => {
    localStorage.setItem('token', data.token);
    localStorage.setItem('user', JSON.stringify({ email: data.email, name: data.name }));
    setIsAuthenticated(true);
    setCurrentUser({ email: data.email, name: data.name });
    loadDocuments();
  };

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setIsAuthenticated(false);
    setCurrentUser(null);
    setMessages([]);
    setConversationId(null);
    setDocuments([]);
    setSelectedDocs([]);
    if (pollingRef.current) clearInterval(pollingRef.current);
  };

  // ======================== DOCUMENT HANDLERS ========================

  const loadDocuments = useCallback(async () => {
    try {
      const docs = await api.listDocuments();
      setDocuments(docs);
      return docs;
    } catch {
      console.error('Failed to load documents');
      return [];
    }
  }, []);

  // Poll for PROCESSING → INGESTED/FAILED transitions
  const startPolling = useCallback(() => {
    if (pollingRef.current) return; // already polling
    pollingRef.current = setInterval(async () => {
      const docs = await loadDocuments();
      const stillProcessing = docs.some((d) => d.status === 'PROCESSING');
      if (!stillProcessing && pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    }, 3000);
  }, [loadDocuments]);

  const handleUpload = async (file) => {
    setUploadProgress(0);
    addSystemMessage(`Uploading "${file.name}"...`);
    setIsLoading(true);

    try {
      const data = await api.uploadDocument(file, (pct) => setUploadProgress(pct));

      if (data.message && data.message.includes('Embedding in progress')) {
        // Async path — server returned 202, embedding happens in background
        addSystemMessage(`"${data.filename}" uploaded. Embedding in progress — document will appear as "Processing" until ready.`);
        await loadDocuments();
        startPolling();
      } else {
        // Sync path — small file, already done
        addSystemMessage(`Document "${data.filename}" uploaded successfully! You can now select it and ask questions.`);
        await loadDocuments();
      }
    } catch (err) {
      if (isAuthError(err)) return handleLogout();
      addSystemMessage(`Upload failed: ${err.message}`);
    }

    setUploadProgress(null);
    setIsLoading(false);
  };

  const handleDeleteDoc = async (documentId) => {
    try {
      await api.deleteDocument(documentId);
      setSelectedDocs((prev) => prev.filter((id) => id !== documentId));
      addSystemMessage('Document deleted successfully.');
      await loadDocuments();
    } catch (err) {
      if (isAuthError(err)) return handleLogout();
      addSystemMessage(`Delete failed: ${err.message}`);
    }
  };

  const handleDeleteAll = async () => {
    setIsLoading(true);
    try {
      await api.deleteAllDocuments();
      setSelectedDocs([]);
      addSystemMessage('All documents deleted.');
      await loadDocuments();
    } catch (err) {
      if (isAuthError(err)) return handleLogout();
      addSystemMessage(`Delete all failed: ${err.message}`);
    }
    setIsLoading(false);
  };

  const handleToggleDoc = (documentId) => {
    setSelectedDocs((prev) =>
      prev.includes(documentId)
        ? prev.filter((id) => id !== documentId)
        : [...prev, documentId]
    );
  };

  // ======================== CHAT HANDLERS ========================

  const handleSend = async () => {
    if (!input.trim() || isLoading) return;

    const userMessage = { role: 'user', content: input };
    setMessages((prev) => [...prev, userMessage]);
    const currentInput = input;
    setInput('');
    setIsLoading(true);

    const useRag = selectedDocs.length > 0;

    try {
      const data = await api.sendChatMessage({
        conversationId,
        message: currentInput,
        selectedDocuments: selectedDocs,
        useRag,
        persona,
        language,
      });

      if (!useRag && data.conversationId) {
        setConversationId(data.conversationId);
      }

      const assistantMsg = {
        role: 'assistant',
        content: data.response,
        sources: data.sources || [],
        retrievalLatencyMs: data.retrievalLatencyMs || 0,
        llmInferenceLatencyMs: data.llmInferenceLatencyMs || 0,
      };

      setMessages((prev) => [...prev, assistantMsg]);
    } catch (err) {
      if (isAuthError(err)) return handleLogout();
      setMessages((prev) => [...prev, {
        role: 'system',
        content: `Error: ${err.message}. If asking about a document, make sure it's selected (orange) in the sidebar.`,
      }]);
    }

    setIsLoading(false);
  };

  const handleClearChat = () => {
    setMessages([]);
    setConversationId(null);
  };

  // ======================== THEME ========================

  const handleToggleTheme = () => {
    const next = theme === 'light' ? 'dark' : 'light';
    setTheme(next);
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('theme', next);
  };

  // ======================== HELPERS ========================

  const addSystemMessage = (content) => {
    setMessages((prev) => [...prev, { role: 'system', content }]);
  };

  const isAuthError = (err) => err.message === 'UNAUTHORIZED';

  // ======================== RENDER ========================

  if (!isAuthenticated) {
    return <AuthPage onLogin={handleLogin} />;
  }

  return (
    <div className="app">
      <Sidebar
        documents={documents}
        selectedDocs={selectedDocs}
        currentUser={currentUser}
        theme={theme}
        isLoading={isLoading}
        uploadProgress={uploadProgress}
        onUpload={handleUpload}
        onDeleteDoc={handleDeleteDoc}
        onDeleteAll={handleDeleteAll}
        onToggleDoc={handleToggleDoc}
        onClearChat={handleClearChat}
        onToggleTheme={handleToggleTheme}
        onLogout={handleLogout}
      />
      <ChatInterface
        messages={messages}
        input={input}
        isLoading={isLoading}
        selectedDocs={selectedDocs}
        persona={persona}
        language={language}
        onInputChange={setInput}
        onSend={handleSend}
        onPersonaChange={setPersona}
        onLanguageChange={setLanguage}
        onClearDocSelection={() => setSelectedDocs([])}
      />
    </div>
  );
}
