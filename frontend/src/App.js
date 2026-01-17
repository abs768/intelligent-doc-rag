import React, { useState, useRef, useEffect } from 'react';
import './App.css';

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [currentUser, setCurrentUser] = useState(null);
  const [authView, setAuthView] = useState('login');
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [file, setFile] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [selectedDocs, setSelectedDocs] = useState([]);
  const [theme, setTheme] = useState('light');
  const [authError, setAuthError] = useState('');

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');

  const messagesEndRef = useRef(null);
  const fileInputRef = useRef(null);
  const textareaRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

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
  }, []);

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 200) + 'px';
    }
  }, [input]);

  const toggleTheme = () => {
    const newTheme = theme === 'light' ? 'dark' : 'light';
    setTheme(newTheme);
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
  };

  const handleRegister = async (e) => {
    e.preventDefault();
    setAuthError('');
    setIsLoading(true);

    try {
      const response = await fetch('http://localhost:8080/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, name })
      });

      if (response.ok) {
        const data = await response.json();
        localStorage.setItem('token', data.token);
        localStorage.setItem('user', JSON.stringify({ email: data.email, name: data.name }));
        setIsAuthenticated(true);
        setCurrentUser({ email: data.email, name: data.name });
        await loadDocuments();
      } else {
        setAuthError('Registration failed. Email may already be taken.');
      }
    } catch (error) {
      setAuthError('Unable to connect to server');
    }

    setIsLoading(false);
  };

  const handleLogin = async (e) => {
    e.preventDefault();
    setAuthError('');
    setIsLoading(true);

    try {
      const response = await fetch('http://localhost:8080/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password })
      });

      if (response.ok) {
        const data = await response.json();
        localStorage.setItem('token', data.token);
        localStorage.setItem('user', JSON.stringify({ email: data.email, name: data.name }));
        setIsAuthenticated(true);
        setCurrentUser({ email: data.email, name: data.name });
        await loadDocuments();
      } else {
        setAuthError('Invalid email or password');
      }
    } catch (error) {
      setAuthError('Unable to connect to server');
    }

    setIsLoading(false);
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
  };

  const getAuthHeaders = () => {
    const token = localStorage.getItem('token');
    return {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    };
  };

  const loadDocuments = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/documents/list', {
        headers: getAuthHeaders()
      });
      if (response.ok) {
        const docs = await response.json();
        setDocuments(docs);
      }
    } catch (error) {
      console.error('Failed to load documents:', error);
    }
  };

  const sendMessage = async () => {
    if (!input.trim() || isLoading) return;

    const userMessage = { role: 'user', content: input };
    setMessages(prev => [...prev, userMessage]);
    const currentInput = input;
    setInput('');
    setIsLoading(true);

    try {
      const useRag = selectedDocs.length > 0;
      
      const requestBody = {
        conversationId: useRag ? null : conversationId,
        message: currentInput,
        selectedDocuments: useRag ? selectedDocs : [],
        useRag: useRag
      };
      
      console.log('Sending chat request:', requestBody);

      const response = await fetch('http://localhost:8080/api/chat', {
        method: 'POST',
        headers: getAuthHeaders(),
        body: JSON.stringify(requestBody)
      });

      if (response.ok) {
        const data = await response.json();
        console.log('Chat response:', data);

        if (!useRag && data.conversationId) {
          setConversationId(data.conversationId);
        }

        const assistantMessage = { role: 'assistant', content: data.response };
        setMessages(prev => [...prev, assistantMessage]);
      } else if (response.status === 401 || response.status === 403) {
        handleLogout();
      } else {
        const errorMessage = {
          role: 'system',
          content: `⚠️ Error: ${response.statusText}. If asking about a document, make sure it's selected (orange) in the sidebar.`
        };
        setMessages(prev => [...prev, errorMessage]);
      }
    } catch (error) {
      console.error('Chat error:', error);
      const errorMessage = {
        role: 'assistant',
        content: '⚠️ Unable to connect to server. Please check if the backend is running.'
      };
      setMessages(prev => [...prev, errorMessage]);
    }

    setIsLoading(false);
  };

  const uploadDocument = async () => {
    if (!file) return;

    const uploadingMessage = {
      role: 'system',
      content: `⏳ Uploading "${file.name}"... Embedding in progress, this may take 10-30 seconds.`
    };
    setMessages(prev => [...prev, uploadingMessage]);
    
    setIsLoading(true);
    const formData = new FormData();
    formData.append('file', file);

    try {
      const token = localStorage.getItem('token');
      const response = await fetch('http://localhost:8080/api/documents/upload', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: formData
      });

      if (response.ok) {
        const data = await response.json();
        const systemMessage = {
          role: 'system',
          content: `✓ Document "${data.filename}" uploaded successfully! You can now select it and ask questions.`
        };
        setMessages(prev => [...prev, systemMessage]);
        setFile(null);

        if (fileInputRef.current) {
          fileInputRef.current.value = '';
        }

        await loadDocuments();
      } else if (response.status === 401 || response.status === 403) {
        handleLogout();
      } else {
        const errorData = await response.json().catch(() => ({}));
        const errorMessage = {
          role: 'system',
          content: `✗ Upload failed: ${errorData.error || response.statusText}`
        };
        setMessages(prev => [...prev, errorMessage]);
      }
    } catch (error) {
      const errorMessage = {
        role: 'system',
        content: `✗ Upload failed: ${error.message || 'Network error'}`
      };
      setMessages(prev => [...prev, errorMessage]);
    }

    setIsLoading(false);
  };

  const deleteDocument = async (documentId, e) => {
    e.stopPropagation();

    const doc = documents.find(d => d.documentId === documentId);
    if (!window.confirm(`Delete "${doc?.filename}"?`)) return;

    try {
      const response = await fetch(`http://localhost:8080/api/documents/${documentId}`, {
        method: 'DELETE',
        headers: getAuthHeaders()
      });

      if (response.ok) {
        setSelectedDocs(prev => prev.filter(id => id !== documentId));
        await loadDocuments();
      } else if (response.status === 401 || response.status === 403) {
        handleLogout();
      }
    } catch (error) {
      console.error('Delete failed:', error);
    }
  };

  const deleteAllDocuments = async () => {
    if (!window.confirm('Delete ALL documents? This cannot be undone.')) return;

    setIsLoading(true);

    try {
      const response = await fetch('http://localhost:8080/api/documents/all', {
        method: 'DELETE',
        headers: getAuthHeaders()
      });

      if (response.ok) {
        setSelectedDocs([]);
        await loadDocuments();
      } else if (response.status === 401 || response.status === 403) {
        handleLogout();
      }
    } catch (error) {
      console.error('Delete all failed:', error);
    }

    setIsLoading(false);
  };

  const toggleDocSelection = (documentId) => {
    setSelectedDocs(prev =>
      prev.includes(documentId)
        ? prev.filter(id => id !== documentId)
        : [...prev, documentId]
    );
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const clearChat = () => {
    setMessages([]);
    setConversationId(null);
  };

  const clearDocSelection = () => {
    setSelectedDocs([]);
  };

  if (!isAuthenticated) {
    return (
      <div className="auth-container">
        <div className="auth-box">
          <div className="auth-header">
            <div className="auth-logo">
              <div className="claude-logo"></div>
            </div>
            <h1>DocGPT</h1>
            <p>AI-powered document chat assistant</p>
          </div>

          <div className="auth-tabs">
            <button
              className={authView === 'login' ? 'active' : ''}
              onClick={() => { setAuthView('login'); setAuthError(''); }}
            >
              Login
            </button>
            <button
              className={authView === 'register' ? 'active' : ''}
              onClick={() => { setAuthView('register'); setAuthError(''); }}
            >
              Register
            </button>
          </div>

          {authError && <div className="auth-error">{authError}</div>}

          {authView === 'login' ? (
            <form onSubmit={handleLogin} className="auth-form">
              <div className="form-group">
                <label>Email</label>
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  autoFocus
                />
              </div>
              <div className="form-group">
                <label>Password</label>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>
              <button type="submit" className="auth-submit" disabled={isLoading}>
                {isLoading ? 'Logging in...' : 'Login'}
              </button>
            </form>
          ) : (
            <form onSubmit={handleRegister} className="auth-form">
              <div className="form-group">
                <label>Name</label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  autoFocus
                />
              </div>
              <div className="form-group">
                <label>Email</label>
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              <div className="form-group">
                <label>Password</label>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>
              <button type="submit" className="auth-submit" disabled={isLoading}>
                {isLoading ? 'Creating account...' : 'Register'}
              </button>
            </form>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-header">
          <button onClick={clearChat} className="new-chat-btn">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 5v14M5 12h14"/>
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
                onChange={(e) => setFile(e.target.files[0])}
                className="file-input"
                id="file-upload"
              />
              <label htmlFor="file-upload" className="file-label">
                {file ? file.name : 'Choose file...'}
              </label>
            </div>
            <button
              onClick={uploadDocument}
              disabled={!file || isLoading}
              className="upload-btn"
            >
              Upload
            </button>
          </div>

          {documents.length > 0 && (
            <div className="documents-section">
              <div className="docs-header">
                <span>{documents.length} document(s)</span>
                <button onClick={deleteAllDocuments} className="delete-all-btn" title="Delete all">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
                  </svg>
                </button>
              </div>

              {documents.map(doc => (
                <div
                  key={doc.documentId}
                  className={`doc-item ${selectedDocs.includes(doc.documentId) ? 'selected' : ''}`}
                  onClick={() => toggleDocSelection(doc.documentId)}
                  title={doc.filename}
                >
                  <div className="doc-info">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                      <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8"/>
                    </svg>
                    <span className="doc-name">{doc.filename}</span>
                  </div>
                  <div className="doc-actions">
                    {selectedDocs.includes(doc.documentId) && (
                      <svg className="check-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
                        <path d="M20 6L9 17l-5-5"/>
                      </svg>
                    )}
                    <button
                      className="delete-doc-btn"
                      onClick={(e) => deleteDocument(doc.documentId, e)}
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
                      </svg>
                    </button>
                  </div>
                </div>
              ))}
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
          <button className="theme-toggle" onClick={toggleTheme}>
            {theme === 'light' ? (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/>
              </svg>
            ) : (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="5"/>
                <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/>
              </svg>
            )}
            {theme === 'light' ? 'Dark' : 'Light'}
          </button>
          <button className="logout-btn" onClick={handleLogout}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/>
            </svg>
            Logout
          </button>
        </div>
      </aside>

      <main className="main-content">
        <header className="header">
          <h1>DocGPT</h1>
        </header>

        {selectedDocs.length > 0 && (
          <div className="context-bar">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="8"/>
              <path d="M21 21l-4.35-4.35"/>
            </svg>
            <span>Searching {selectedDocs.length} document(s)</span>
            <button onClick={clearDocSelection} className="clear-context">
              Clear
            </button>
          </div>
        )}

        <div className="chat-container">
          <div className="messages">
            {messages.length === 0 && (
              <div className="welcome">
                <div className="logo-container">
                  <div className="claude-logo"></div>
                </div>
                <h2>How can I help you today?</h2>
              </div>
            )}

            {messages.map((msg, index) => (
              <div key={index} className={`message-row ${msg.role}`}>
                <div className="message-wrapper">
                  <div className={`message-avatar ${msg.role}`}>
                    {msg.role === 'user' ? (
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                      </svg>
                    ) : msg.role === 'system' ? (
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                      </svg>
                    ) : (
                      <div className="claude-avatar-gradient"></div>
                    )}
                  </div>
                  <div className="message-content">
                    <div className="message-text">{msg.content}</div>
                  </div>
                </div>
              </div>
            ))}

            {isLoading && (
              <div className="message-row assistant">
                <div className="message-wrapper">
                  <div className="message-avatar assistant">
                    <div className="claude-avatar-gradient"></div>
                  </div>
                  <div className="message-content">
                    <div className="typing-indicator">
                      <span></span>
                      <span></span>
                      <span></span>
                    </div>
                  </div>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          <div className="input-container">
            <div className="input-wrapper">
              <textarea
                ref={textareaRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyPress={handleKeyPress}
                placeholder="Message DocGPT..."
                disabled={isLoading}
                rows="1"
              />
              <button
                onClick={sendMessage}
                disabled={!input.trim() || isLoading}
                className="send-btn"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
                </svg>
              </button>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

export default App;
