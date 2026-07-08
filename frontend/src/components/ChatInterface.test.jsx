import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import ChatInterface from './ChatInterface';

// react-markdown v10 is ESM-only, which CRA's Jest can't transform
jest.mock('react-markdown', () => (props) => <div>{props.children}</div>);

const defaultProps = {
  messages: [],
  input: '',
  isLoading: false,
  selectedDocs: [],
  persona: 'Analyst',
  language: 'English',
  onInputChange: jest.fn(),
  onSend: jest.fn(),
  onPersonaChange: jest.fn(),
  onLanguageChange: jest.fn(),
  onClearDocSelection: jest.fn(),
};

describe('ChatInterface', () => {
  beforeEach(() => jest.clearAllMocks());

  test('shows the welcome screen when there are no messages', () => {
    render(<ChatInterface {...defaultProps} />);
    expect(screen.getByText('How can I help you today?')).toBeInTheDocument();
  });

  test('renders assistant sources as citation chips with telemetry', () => {
    const messages = [
      { role: 'user', content: 'What is the refund policy?' },
      {
        role: 'assistant',
        content: 'Refunds are processed within 14 days.',
        sources: ['policy.pdf#chunk-3', 'policy.pdf#chunk-7'],
        retrievalLatencyMs: 42,
        llmInferenceLatencyMs: 950,
      },
    ];
    render(<ChatInterface {...defaultProps} messages={messages} />);

    expect(screen.getByText('Sources:')).toBeInTheDocument();
    expect(screen.getByText('policy.pdf#chunk-3')).toBeInTheDocument();
    expect(screen.getByText('policy.pdf#chunk-7')).toBeInTheDocument();
    expect(screen.getByText(/Qdrant: 42ms/)).toBeInTheDocument();
    expect(screen.getByText(/Ollama: 950ms/)).toBeInTheDocument();
  });

  test('send button is disabled for empty input and while loading', () => {
    const { rerender } = render(<ChatInterface {...defaultProps} input="   " />);
    const sendButton = () => document.querySelector('.send-btn');
    expect(sendButton()).toBeDisabled();

    rerender(<ChatInterface {...defaultProps} input="hello" />);
    expect(sendButton()).toBeEnabled();

    rerender(<ChatInterface {...defaultProps} input="hello" isLoading={true} />);
    expect(sendButton()).toBeDisabled();
  });

  test('Enter sends the message, Shift+Enter does not', () => {
    const onSend = jest.fn();
    render(<ChatInterface {...defaultProps} input="hello" onSend={onSend} />);
    const textarea = screen.getByPlaceholderText('Message DocGPT...');

    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: true });
    expect(onSend).not.toHaveBeenCalled();

    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(onSend).toHaveBeenCalledTimes(1);
  });

  test('context bar shows selected document count and clears on click', () => {
    const onClearDocSelection = jest.fn();
    render(
      <ChatInterface
        {...defaultProps}
        selectedDocs={[1, 2]}
        onClearDocSelection={onClearDocSelection}
      />
    );

    expect(screen.getByText('Searching 2 document(s)')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Clear'));
    expect(onClearDocSelection).toHaveBeenCalledTimes(1);
  });

  test('shows the typing indicator while loading', () => {
    render(<ChatInterface {...defaultProps} isLoading={true} />);
    expect(document.querySelector('.typing-indicator')).toBeInTheDocument();
  });
});
