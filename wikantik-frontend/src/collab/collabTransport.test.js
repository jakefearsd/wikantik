import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createCollabTransport } from './collabTransport.js';

function jsonResponse(status, body) {
  return new Response(body === null ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('collabTransport', () => {
  let fetchMock;
  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('join posts the priorClientId/initialText/initialMeta body to the encoded page path with same-origin credentials', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { sessionId: 's1', clientID: 'c1', version: 0, doc: '' }));
    const t = createCollabTransport('My Page/Odd');
    const res = await t.join({ priorClientId: 'c0', initialText: 'hi', initialMeta: { a: 1 } });
    expect(res.sessionId).toBe('s1');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/collab/My%20Page%2FOdd/join');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    expect(JSON.parse(init.body)).toEqual({ priorClientId: 'c0', initialText: 'hi', initialMeta: { a: 1 } });
  });

  it('push posts {sessionId, version, updates, metaOps, selection}', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { accepted: true, version: 1, metaSeq: 0 }));
    const t = createCollabTransport('P');
    const body = { sessionId: 's1', version: 0, updates: [{ clientID: 'c1', changes: [0, [0, 'x']] }], metaOps: [], selection: { anchor: 1, head: 1, version: 0, unconfirmed: 1 } };
    await expect(t.push(body)).resolves.toEqual({ accepted: true, version: 1, metaSeq: 0 });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/collab/P/updates');
    expect(JSON.parse(init.body)).toEqual(body);
  });

  it('pull is a GET with the cursors as query parameters and forwards the abort signal', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { updates: [] }));
    const t = createCollabTransport('P');
    const ctl = new AbortController();
    await t.pull({ sessionId: 's1', participantId: 'pt1', since: 3, metaSince: 1, presenceSince: 2, eventsSince: 4, signal: ctl.signal });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/collab/P/updates?sessionId=s1&participantId=pt1&since=3&metaSince=1&presenceSince=2&eventsSince=4');
    expect(init.method ?? 'GET').toBe('GET');
    expect(init.signal).toBe(ctl.signal);
  });

  it('publish, discard and leave post to their endpoints; 204 resolves null', async () => {
    fetchMock.mockImplementation(async (url) => (url.endsWith('/publish') ? jsonResponse(200, { pageVersion: 8 }) : new Response(null, { status: 204 })));
    const t = createCollabTransport('P');
    await expect(t.publish({ sessionId: 's1', version: 2, changeNote: 'n' })).resolves.toEqual({ pageVersion: 8 });
    await expect(t.discard({ sessionId: 's1' })).resolves.toBeNull();
    await expect(t.leave({ sessionId: 's1' })).resolves.toBeNull();
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(['/api/collab/P/publish', '/api/collab/P/discard', '/api/collab/P/leave']);
  });

  it('non-2xx responses reject with status and the parsed body (reason survives)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { accepted: false, reason: 'stale-base', version: 5 }));
    const t = createCollabTransport('P');
    await expect(t.push({ sessionId: 's', version: 0, updates: [], metaOps: [] })).rejects.toMatchObject({
      status: 409, body: { reason: 'stale-base', version: 5 },
    });
  });

  it('a network failure rejects without a status', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const t = createCollabTransport('P');
    const err = await t.join({}).catch((e) => e);
    expect(err).toBeInstanceOf(TypeError);
    expect(err.status).toBeUndefined();
  });

  it('leaveBeacon uses navigator.sendBeacon with a JSON blob and reports whether it was queued', async () => {
    const beacon = vi.fn().mockReturnValue(true);
    vi.stubGlobal('navigator', { ...globalThis.navigator, sendBeacon: beacon });
    const t = createCollabTransport('P');
    expect(t.leaveBeacon({ sessionId: 's1', participantId: 'pt1' })).toBe(true);
    const [url, blob] = beacon.mock.calls[0];
    expect(url).toBe('/api/collab/P/leave');
    expect(blob.type).toBe('application/json');
    expect(JSON.parse(await blob.text())).toEqual({ sessionId: 's1', participantId: 'pt1' });
  });

  it('leaveBeacon returns false where sendBeacon is missing', () => {
    vi.stubGlobal('navigator', { ...globalThis.navigator, sendBeacon: undefined });
    expect(createCollabTransport('P').leaveBeacon({ sessionId: 's' })).toBe(false);
  });

  it('retarget sends every later call to the new page path, keeping the session', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { accepted: true, version: 1, metaSeq: 0 }));
    const t = createCollabTransport('Old');
    t.retarget('New Name');
    await t.push({ sessionId: 's', participantId: 'p', version: 0, updates: [], metaOps: [] });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/collab/New%20Name/updates');
  });

  it('pull uses the retargeted path too', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { updates: [], metaOps: [], events: [], presence: {} }));
    const t = createCollabTransport('Old');
    t.retarget('New');
    await t.pull({ sessionId: 's', participantId: 'p', since: 0 });
    expect(fetchMock.mock.calls[0][0]).toMatch(/^\/api\/collab\/New\/updates\?/);
  });
});
