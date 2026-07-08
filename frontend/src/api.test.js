import * as api from './api';

describe('api client', () => {
  beforeEach(() => {
    global.fetch = jest.fn();
    localStorage.setItem('token', 'test-jwt');
  });

  afterEach(() => {
    localStorage.clear();
  });

  const okJson = (body, status = 200) =>
    Promise.resolve({ ok: true, status, json: () => Promise.resolve(body) });

  const failJson = (status, body = {}) =>
    Promise.resolve({ ok: false, status, json: () => Promise.resolve(body) });

  test('login posts credentials and returns auth data', async () => {
    global.fetch.mockReturnValue(okJson({ token: 'jwt-123' }));

    const data = await api.login('a@b.com', 'secret');

    expect(data).toEqual({ token: 'jwt-123' });
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toMatch(/\/api\/auth\/login$/);
    expect(JSON.parse(opts.body)).toEqual({ email: 'a@b.com', password: 'secret' });
  });

  test('login rejects with a friendly message on 401', async () => {
    global.fetch.mockReturnValue(failJson(401));
    await expect(api.login('a@b.com', 'wrong')).rejects.toThrow('Invalid email or password');
  });

  test('authenticated requests carry the Bearer token', async () => {
    global.fetch.mockReturnValue(okJson([]));

    await api.listDocuments();

    const [, opts] = global.fetch.mock.calls[0];
    expect(opts.headers.Authorization).toBe('Bearer test-jwt');
  });

  test('sendChatMessage surfaces expired sessions as UNAUTHORIZED', async () => {
    global.fetch.mockReturnValue(failJson(401));

    await expect(
      api.sendChatMessage({ conversationId: 1, message: 'hi', selectedDocuments: [], useRag: false })
    ).rejects.toThrow('UNAUTHORIZED');
  });

  test('RAG mode sends selected documents and drops the conversation id', async () => {
    global.fetch.mockReturnValue(okJson({ response: 'answer' }));

    await api.sendChatMessage({
      conversationId: 7,
      message: 'what does the doc say?',
      selectedDocuments: [3, 4],
      useRag: true,
    });

    const [, opts] = global.fetch.mock.calls[0];
    const body = JSON.parse(opts.body);
    expect(body.conversationId).toBeNull();
    expect(body.selectedDocuments).toEqual([3, 4]);
    expect(body.useRag).toBe(true);
  });

  test('non-RAG mode keeps the conversation id and sends no documents', async () => {
    global.fetch.mockReturnValue(okJson({ response: 'answer' }));

    await api.sendChatMessage({
      conversationId: 7,
      message: 'hello',
      selectedDocuments: [3, 4],
      useRag: false,
    });

    const body = JSON.parse(global.fetch.mock.calls[0][1].body);
    expect(body.conversationId).toBe(7);
    expect(body.selectedDocuments).toEqual([]);
  });

  test('small-file upload reports progress and returns the server response', async () => {
    global.fetch.mockReturnValue(okJson({ documentId: 9, filename: 'a.pdf' }, 202));
    const onProgress = jest.fn();
    const file = new File(['hello'], 'a.pdf', { type: 'application/pdf' });

    const res = await api.uploadDocument(file, onProgress);

    expect(res).toEqual({ documentId: 9, filename: 'a.pdf' });
    expect(onProgress).toHaveBeenCalledWith(50);
    expect(onProgress).toHaveBeenLastCalledWith(100);
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toMatch(/\/api\/documents\/upload$/);
    // multipart upload must not force a JSON content type
    expect(opts.headers['Content-Type']).toBeUndefined();
  });

  test('upload failure propagates the server error message', async () => {
    global.fetch.mockReturnValue(failJson(415, { message: 'Unsupported file type' }));
    const file = new File(['x'], 'a.exe');

    await expect(api.uploadDocument(file)).rejects.toThrow('Unsupported file type');
  });
});
