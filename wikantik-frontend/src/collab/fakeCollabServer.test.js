/**
 * The fake authority must answer with the shapes recorded in __fixtures__/collab-protocol.json, which the Java
 * resource asserts against too: this is the frontend half of that contract.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ChangeSet } from '@codemirror/state';
import fixture from './__fixtures__/collab-protocol.json';
import { createFakeCollabServer } from './fakeCollabServer.js';

const byId = Object.fromEntries(fixture.cases.map((c) => [c.id, c]));

/** Structural comparison: same keys, same JSON types; arrays by their first element; object maps by first value. */
function shapeDiff(actual, expected, path = '$') {
  const type = (v) => (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v);
  if (type(actual) !== type(expected)) {
    // a nullable field may legitimately be null in one and a value in the other
    if (actual === null || expected === null) return [];
    return [`${path}: type ${type(actual)} != ${type(expected)}`];
  }
  if (type(actual) === 'array') {
    if (actual.length && expected.length) return shapeDiff(actual[0], expected[0], `${path}[0]`);
    return [];
  }
  if (type(actual) === 'object') {
    const diffs = [];
    if (path.endsWith('.metadata')) return []; // a free-form map
    if (path.endsWith('.presence')) {
      const a = Object.values(actual)[0];
      const e = Object.values(expected)[0];
      return a && e ? shapeDiff(a, e, `${path}.*`) : [];
    }
    for (const k of Object.keys(expected)) {
      if (!(k in actual)) diffs.push(`${path}.${k}: missing`);
      else diffs.push(...shapeDiff(actual[k], expected[k], `${path}.${k}`));
    }
    for (const k of Object.keys(actual)) if (!(k in expected)) diffs.push(`${path}.${k}: unexpected`);
    return diffs;
  }
  return [];
}

async function expectCase(server, id, { user = 'ada' } = {}) {
  const c = byId[id];
  const res = await server.handle({ method: c.method, path: c.path, query: c.request.query, body: c.request.body, user });
  expect(res.status, id).toBe(c.response.status);
  expect(shapeDiff(res.body, c.response.body), id).toEqual([]);
  return res;
}

async function joinSample(server, user = 'ada') {
  return (await server.handle({ method: 'POST', path: '/join', body: {}, user })).body;
}
const push = (server, body, user = 'ada') => server.handle({ method: 'POST', path: '/updates', body: { sessionId: 's-1f3a', metaOps: [], ...body }, user });

describe('fixture file', () => {
  it('covers every endpoint and status the ruling lists', () => {
    const ids = fixture.cases.map((c) => c.id);
    for (const want of [
      'join.200', 'join.403', 'join.409.derived-page', 'join.429.session-full', 'join.503.collab-disabled',
      'updates.post.200', 'updates.post.409.stale-base', 'updates.post.409.rejoin', 'updates.post.409.rate-limited',
      'updates.post.400', 'updates.post.413', 'updates.get.200', 'updates.get.200.empty', 'updates.get.410',
      'publish.200', 'publish.409.publish-merged-retry', 'publish.422', 'publish.423', 'discard.204', 'leave.204',
    ]) expect(ids, want).toContain(want);
  });

  it('every 409 carries a reason, and every changes value is a valid ChangeSet JSON', () => {
    for (const c of fixture.cases) {
      if (c.response.status === 409) expect(c.response.body.reason, c.id).toBeTypeOf('string');
      const all = [...(c.request.body?.updates ?? []), ...(c.response.body?.updates ?? [])];
      for (const u of all) {
        expect(u.clientID, c.id).toBeTypeOf('string');
        expect(() => ChangeSet.fromJSON(u.changes), c.id).not.toThrow();
      }
    }
  });

  it('uses the section 6.2 field names', () => {
    const join = byId['join.200'].response.body;
    for (const k of ['sessionId', 'participantId', 'clientID', 'version', 'doc', 'metaSeq', 'metadata', 'basePageVersion', 'participants', 'draft']) {
      expect(join, k).toHaveProperty(k);
    }
    expect(byId['updates.post.200'].response.body).toHaveProperty('accepted', true);
  });
});

describe('fakeCollabServer matches the fixture', () => {
  let server;
  beforeEach(() => { vi.useFakeTimers(); server = createFakeCollabServer(); });
  afterEach(() => { vi.useRealTimers(); });

  it('join 200 and its error statuses', async () => {
    await expectCase(server, 'join.200');
    expect(byId['join.200'].response.body.doc).toBe('Hello world');
    for (const [id, flag] of [['join.403', 'forbidJoin'], ['join.409.derived-page', 'derived'],
      ['join.429.session-full', 'full'], ['join.503.collab-disabled', 'disabled']]) {
      const s = createFakeCollabServer();
      s.config[flag] = true;
      await expectCase(s, id);
    }
  });

  it('join reissues a prior clientID the server still knows, and applies initialText only on creation', async () => {
    const first = await joinSample(server);
    const again = (await server.handle({ method: 'POST', path: '/join', body: { priorClientId: first.clientID }, user: 'ada' })).body;
    expect(again.clientID).toBe(first.clientID);
    expect(again.participantId).not.toBe(first.participantId);
    const fresh = createFakeCollabServer({ initialText: 'old' });
    const j = (await fresh.handle({ method: 'POST', path: '/join', body: { initialText: 'template', initialMeta: { a: 1 } }, user: 'ada' })).body;
    expect(j.doc).toBe('template');
    expect(j.metadata).toEqual({ a: 1 });
    const j2 = (await fresh.handle({ method: 'POST', path: '/join', body: { initialText: 'ignored' }, user: 'bob' })).body;
    expect(j2.doc).toBe('template');
  });

  /** Runs a recorded request against `srv`, substituting the participant's real clientID, and checks the response. */
  async function replay(srv, id, { clientID, user = 'ada', version } = {}) {
    const c = byId[id];
    const body = structuredClone(c.request.body ?? {});
    body.sessionId = 's-1f3a';
    for (const u of body.updates ?? []) if (clientID) u.clientID = clientID;
    if (version !== undefined) body.version = version;
    const res = await srv.handle({ method: c.method, path: c.path, body, user });
    expect(res.status, id).toBe(c.response.status);
    expect(shapeDiff(res.body, c.response.body), id).toEqual([]);
    return res;
  }

  it('POST /updates 200, stale-base, 400, rejoin, frozen', async () => {
    const j = await joinSample(server);
    await replay(server, 'updates.post.200', { clientID: j.clientID });
    await replay(server, 'updates.post.409.stale-base', { clientID: j.clientID });
    await replay(server, 'updates.post.400', { clientID: j.clientID, version: 1 });
    const spoof = await push(server, { version: 1, updates: [{ clientID: 'someone-else', changes: [12, [0, 'x']] }] });
    expect(spoof.status).toBe(400);
    server.config.frozen = true;
    await replay(server, 'updates.post.409.frozen', { clientID: j.clientID });
    server.config.frozen = false;
    server.expire('ada');
    await replay(server, 'updates.post.409.rejoin', { clientID: j.clientID });
  });

  it('POST /updates 413 and rate-limited', async () => {
    // the recorded 413 request is written against the 12-unit document 'Hello world!'
    const big = createFakeCollabServer({ maxPageBytes: 12 });
    const bj = await joinSample(big);
    await push(big, { version: 0, updates: [{ clientID: bj.clientID, changes: [11, [0, '!']] }] });
    await replay(big, 'updates.post.413', { clientID: bj.clientID, version: 1 });
  });

  it('rate limit answers with the recorded body', async () => {
    const lim = createFakeCollabServer({ pushesPerSecond: 1 });
    await joinSample(lim);
    await push(lim, { version: 0, updates: [] });
    const rl = await push(lim, { version: 0, updates: [] });
    expect(rl.status).toBe(byId['updates.post.409.rate-limited'].response.status);
    expect(shapeDiff(rl.body, byId['updates.post.409.rate-limited'].response.body)).toEqual([]);
  });

  it('GET /updates: 200 with content, empty on timeout, 410, rejoin', async () => {
    const s = createFakeCollabServer({ pollTimeoutMs: 1000 });
    const ada = await joinSample(s, 'ada');
    await joinSample(s, 'raj');
    await s.handle({ method: 'POST', path: '/updates', user: 'raj', body: {
      sessionId: 's-1f3a', version: 0, updates: [{ clientID: (await joinSample(s, 'raj')).clientID, changes: [11, [0, '!']] }],
      metaOps: [{ field: 'tags', value: ['a', 'b'] }], selection: { anchor: 3, head: 5, version: 1, unconfirmed: 0 },
    } });
    const got = await s.handle({ method: 'GET', path: '/updates', user: 'ada', query: byId['updates.get.200'].request.query });
    expect(got.status).toBe(200);
    expect(shapeDiff(got.body, byId['updates.get.200'].response.body)).toEqual([]);
    expect(got.body.updates).toHaveLength(1);

    const parked = s.handle({ method: 'GET', path: '/updates', user: 'ada', query: { sessionId: 's-1f3a', since: s.version, metaSince: got.body.metaSeq, presenceSince: got.body.presenceSeq, eventsSince: got.body.eventSeq } });
    await vi.advanceTimersByTimeAsync(1000);
    const empty = await parked;
    expect(empty.status).toBe(200);
    expect(shapeDiff(empty.body, byId['updates.get.200.empty'].response.body)).toEqual([]);
    expect(empty.body.updates).toEqual([]);

    s.truncateBelow(1);
    const gone = await s.handle({ method: 'GET', path: '/updates', user: 'ada', query: byId['updates.get.410'].request.query });
    expect(gone.status).toBe(410);
    expect(shapeDiff(gone.body, byId['updates.get.410'].response.body)).toEqual([]);

    s.expire('ada');
    const rj = await s.handle({ method: 'GET', path: '/updates', user: 'ada', query: byId['updates.get.409.rejoin'].request.query });
    expect(rj.status).toBe(409);
    expect(shapeDiff(rj.body, byId['updates.get.409.rejoin'].response.body)).toEqual([]);
    expect(ada.sessionId).toBe('s-1f3a');
  });

  it('a parked pull is woken by a push', async () => {
    const a = await joinSample(server, 'ada');
    const b = await joinSample(server, 'bob');
    const parked = server.handle({ method: 'GET', path: '/updates', user: 'ada', query: { sessionId: 's-1f3a', since: 0, presenceSince: 99, metaSince: 99, eventsSince: 99 } });
    await push(server, { version: 0, updates: [{ clientID: b.clientID, changes: [11, [0, '?']] }] }, 'bob');
    const res = await parked;
    expect(res.body.updates).toHaveLength(1);
    expect(a.version).toBe(0);
  });

  it('publish 200, merged-retry, not-synced, 422, 423; discard and leave 204', async () => {
    const j = await joinSample(server);
    await push(server, { version: 0, updates: [{ clientID: j.clientID, changes: [11, [0, '!']] }] });
    await replay(server, 'publish.200', { version: 1 });
    await replay(server, 'publish.409.not-synced', { version: 0 });
    server.config.publishMergedRetry = true;
    await replay(server, 'publish.409.publish-merged-retry', { version: 1 });
    server.config.publishInvalid = true;
    await replay(server, 'publish.422', { version: 1 });
    server.config.publishInvalid = false;
    server.config.publishing = true;
    await replay(server, 'publish.423', { version: 1 });
    await replay(server, 'discard.204');
    await replay(server, 'leave.204');
  });
});
