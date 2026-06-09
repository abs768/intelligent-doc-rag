import React, { useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';

export default function ChatInterface({
  messages,
  input,
  isLoading,
  selectedDocs,
  persona,
  language,
  onInputChange,
  onSend,
  onPersonaChange,
  onLanguageChange,
  onClearDocSelection,
}) {
  const messagesEndRef = useRef(null);
  const textareaRef = useRef(null);

  // Auto-scroll to newest message
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Auto-grow textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 200) + 'px';
    }
  }, [input]);

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  };

  return (
    <main className="main-content">
      <header className="header">
        <h1>DocGPT</h1>
      </header>

      {selectedDocs.length > 0 && (
        <div className="context-bar">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="8" />
            <path d="M21 21l-4.35-4.35" />
          </svg>
          <span>Searching {selectedDocs.length} document(s)</span>
          <button onClick={onClearDocSelection} className="clear-context">
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
                      <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
                    </svg>
                  ) : msg.role === 'system' ? (
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
                    </svg>
                  ) : (
                    <div className="claude-avatar-gradient"></div>
                  )}
                </div>
                <div className="message-content">
                  <div className="message-text">
                    {msg.role === 'user' || msg.role === 'system'
                      ? msg.content
                      : <ReactMarkdown>{msg.content}</ReactMarkdown>
                    }
                  </div>

                  {/* Module 2: Sources / Citations */}
                  {msg.role === 'assistant' && msg.sources && msg.sources.length > 0 && (
                    <div className="message-sources">
                      <span className="sources-label">Sources:</span>
                      {msg.sources.map((src, i) => (
                        <span key={i} className="source-chip">{src}</span>
                      ))}
                    </div>
                  )}

                  {/* Module 1: MLOps Telemetry */}
                  {msg.role === 'assistant' && (msg.retrievalLatencyMs > 0 || msg.llmInferenceLatencyMs > 0) && (
                    <div className="message-telemetry">
                      {msg.retrievalLatencyMs > 0 && (
                        <span>&#9889; Qdrant: {msg.retrievalLatencyMs}ms</span>
                      )}
                      {msg.llmInferenceLatencyMs > 0 && (
                        <span>&#129504; Ollama: {msg.llmInferenceLatencyMs}ms</span>
                      )}
                    </div>
                  )}
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
          {/* Module 3: Persona + Language Dropdowns */}
          <div className="input-controls">
            <div className="control-group">
              <label htmlFor="persona-select">Persona</label>
              <select
                id="persona-select"
                value={persona}
                onChange={(e) => onPersonaChange(e.target.value)}
              >
                <option value="Analyst">Analyst</option>
                <option value="Commercial Lead">Commercial Lead</option>
                <option value="Technical Lead">Technical Lead</option>
                <option value="External Merchant">External Merchant</option>
              </select>
            </div>
            <div className="control-group">
              <label htmlFor="language-select">Language</label>
              <select
                id="language-select"
                value={language}
                onChange={(e) => onLanguageChange(e.target.value)}
              >
                <option value="English">English</option>
                <option value="Español">Español</option>
              </select>
            </div>
          </div>

          <div className="input-wrapper">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={(e) => onInputChange(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Message DocGPT..."
              disabled={isLoading}
              rows="1"
            />
            <button
              onClick={onSend}
              disabled={!input.trim() || isLoading}
              className="send-btn"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
              </svg>
            </button>
          </div>
        </div>
      </div>
    </main>
  );
}
