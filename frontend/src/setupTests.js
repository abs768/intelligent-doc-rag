import '@testing-library/jest-dom';

// jsdom doesn't implement scrollIntoView (used for auto-scroll in ChatInterface)
window.HTMLElement.prototype.scrollIntoView = jest.fn();
