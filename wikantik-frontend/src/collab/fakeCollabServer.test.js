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
    // `by` on a pulled meta op is present only when a participant authored it, so it is optional against older cases
    for (const k of Object.keys(actual)) if (!(k in expected) && !(k === 'by' && path.includes('.metaOps['))) diffs.push(`${path}.${k}: unexpected`);
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
const push = (server, body, user = 'ada', participantId = 'pt-1') => server.handle({ method: 'POST', path: '/updates', body: { sessionId: 's-1f3a', participantId, metaOps: [], ...body }, user });

describe('fixture file', () => {
  it('covers every endpoint and status the ruling lists', () => {
    const ids = fixture.cases.map((c) => c.id);
    for (const want of [
      'join.200', 'join.403', 'join.409.derived-page', 'join.429.session-full', 'join.503.collab-disabled',
      'updates.post.200', 'updates.post.409.stale-base', 'updates.post.409.rejoin', 'updates.post.429.rate-limited',
      'updates.post.400.rejected', 'updates.post.413', 'updates.get.200', 'updates.get.200.empty', 'updates.get.410',
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
    server.expire('ada'); // a live participant keeps its clientID; only an expired one's is reissued
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
  async function replay(srv, id, { clientID, user = 'ada', version, participantId = 'pt-1' } = {}) {
    const c = byId[id];
    const body = structuredClone(c.request.body ?? {});
    body.sessionId = 's-1f3a';
    body.participantId = participantId;
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
    await replay(server, 'updates.post.400.rejected', { clientID: j.clientID, version: 1 });
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
    expect(rl.status).toBe(429);
    expect(shapeDiff(rl.body, byId['updates.post.429.rate-limited'].response.body)).toEqual([]);
  });

  it('GET /updates: 200 with content, empty on timeout, 410, rejoin', async () => {
    const s = createFakeCollabServer({ pollTimeoutMs: 1000 });
    const ada = await joinSample(s, 'ada');
    const raj = await joinSample(s, 'raj');
    await s.handle({ method: 'POST', path: '/updates', user: 'raj', body: {
      sessionId: 's-1f3a', participantId: raj.participantId, version: 0, updates: [{ clientID: raj.clientID, changes: [11, [0, '!']] }],
      metaOps: [{ field: 'tags', value: ['a', 'b'] }], selection: { anchor: 3, head: 5, version: 1, unconfirmed: 0 },
    } });
    const got = await s.handle({ method: 'GET', path: '/updates', user: 'ada', query: byId['updates.get.200'].request.query });
    expect(got.status).toBe(200);
    expect(shapeDiff(got.body, byId['updates.get.200'].response.body)).toEqual([]);
    expect(got.body.updates).toHaveLength(1);

    const parked = s.handle({ method: 'GET', path: '/updates', user: 'ada', query: { sessionId: 's-1f3a', participantId: 'pt-1', since: s.version, metaSince: got.body.metaSeq, presenceSince: got.body.presenceSeq, eventsSince: got.body.eventSeq } });
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

  it('GET /updates 400 bad-since when since is beyond the head', async () => {
    await joinSample(server);
    const res = await server.handle({ method: 'GET', path: '/updates', user: 'ada', query: byId['updates.get.400.bad-since'].request.query });
    expect(res.status).toBe(400);
    expect(shapeDiff(res.body, byId['updates.get.400.bad-since'].response.body)).toEqual([]);
  });

  it('another user\'s participantId is 403 not-your-participant on every endpoint, and a missing one is 400', async () => {
    await joinSample(server, 'ada');
    const bob = await joinSample(server, 'bob');
    const res = await push(server, { version: 0, updates: [] }, 'bob', 'pt-1');
    expect(res.status).toBe(403);
    expect(shapeDiff(res.body, byId['updates.post.403.not-your-participant'].response.body)).toEqual([]);
    for (const [method, path, extra] of [['POST', '/publish', { version: 0 }], ['POST', '/discard', {}], ['POST', '/leave', {}]]) {
      const r = await server.handle({ method, path, user: 'bob', body: { sessionId: 's-1f3a', participantId: 'pt-1', ...extra } });
      expect(r.status, path).toBe(403);
    }
    const g = await server.handle({ method: 'GET', path: '/updates', user: 'bob', query: { sessionId: 's-1f3a', participantId: 'pt-1', since: 0 } });
    expect(g.status).toBe(403);
    const none = await server.handle({ method: 'POST', path: '/updates', user: 'bob', body: { sessionId: 's-1f3a', version: 0, updates: [] } });
    expect(none.status).toBe(400);
    expect(server.isLive('pt-1')).toBe(true);
    expect(bob.participantId).toBe('pt-2');
  });

  it('two joins by one user are two participants; leaving one leaves the other live', async () => {
    const a = await joinSample(server, 'ada');
    const b = await joinSample(server, 'ada');
    expect(a.participantId).not.toBe(b.participantId);
    expect(a.clientID).not.toBe(b.clientID);
    await server.handle({ method: 'POST', path: '/leave', user: 'ada', body: { sessionId: 's-1f3a', participantId: a.participantId } });
    expect(server.isLive(a.participantId)).toBe(false);
    expect(server.isLive(b.participantId)).toBe(true);
    const ok = await push(server, { version: 0, updates: [] }, 'ada', b.participantId);
    expect(ok.status).toBe(200);
  });

  it('a parked pull is woken by a push', async () => {
    const a = await joinSample(server, 'ada');
    const b = await joinSample(server, 'bob');
    const parked = server.handle({ method: 'GET', path: '/updates', user: 'ada', query: { sessionId: 's-1f3a', participantId: 'pt-1', since: 0, presenceSince: 99, metaSince: 99, eventsSince: 99 } });
    await push(server, { version: 0, updates: [{ clientID: b.clientID, changes: [11, [0, '?']] }] }, 'bob', b.participantId);
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

describe('Phase 2 fixture cases against the fake', () => {
  let server;
  beforeEach(() => { vi.useFakeTimers(); server = createFakeCollabServer({ initialMeta: { tags: ['a'], related: ['OldPage'], summary: 's' } }); });
  afterEach(() => { vi.useRealTimers(); });
  const pullAll = (srv, user = 'ada', participantId = 'pt-1', extra = {}) => srv.handle({
    method: 'GET', path: '/updates', user,
    query: { sessionId: 's-1f3a', participantId, since: 0, metaSince: 0, presenceSince: 0, eventsSince: 0, ...extra },
  });
  const replayCase = async (srv, id, { version, user = 'ada', participantId = 'pt-1' } = {}) => {
    const c = byId[id];
    const body = { ...structuredClone(c.request.body), sessionId: 's-1f3a', participantId };
    if (version !== undefined) body.version = version;
    const res = await srv.handle({ method: c.method, path: c.path, body, query: c.request.query, user });
    expect(res.status, id).toBe(c.response.status);
    expect(shapeDiff(res.body, c.response.body), id).toEqual([]);
    return res;
  };

  it('fixture lists the new cases and notes', () => {
    for (const id of ['updates.post.200.meta-set-ops', 'updates.post.200.meta-only', 'updates.post.400.bad-meta-op',
      'updates.get.200.meta-by', 'updates.get.200.lifecycle-events', 'updates.post.403.evicted', 'updates.get.403.evicted',
      'publish.403.no-create']) expect(byId[id], id).toBeDefined();
    for (const n of ['metaEdits', 'metaIdempotent', 'renamedSession', 'evicted']) expect(fixture.notes[n], n).toBeTypeOf('string');
  });

  it('meta set, add, remove and null-removal resolve to assignments', async () => {
    await joinSample(server);
    await replayCase(server, 'updates.post.200.meta-set-ops', { version: 0 });
    expect(server.metadata).toEqual({ tags: ['a', 'c'], related: [], status: 'draft' });
    const pulled = await pullAll(server);
    expect(pulled.body.metaOps).toEqual([
      { field: 'tags', value: ['a', 'c'], by: 'ada' },
      { field: 'related', value: [], by: 'ada' },
      { field: 'status', value: 'draft', by: 'ada' },
      { field: 'summary', value: null, by: 'ada' },
    ]);
    expect(pulled.body.metaSeq).toBe(4);
  });

  it('meta edits are idempotent: a repeat changes nothing', async () => {
    await joinSample(server);
    const ops = [{ field: 'tags', add: 'a' }, { field: 'related', remove: 'Nope' }, { field: 'summary', value: 's' }];
    const res = await push(server, { version: 0, updates: [], metaOps: ops });
    expect(res.status).toBe(200);
    expect(res.body.metaSeq).toBe(0);
    expect((await pullAll(server)).body.metaOps).toEqual([]);
    const add = { field: 'tags', add: 'z' };
    await push(server, { version: 0, updates: [], metaOps: [add] });
    const again = await push(server, { version: 0, updates: [], metaOps: [add] });
    expect(again.body.metaSeq).toBe(1);
    expect(server.metadata.tags).toEqual(['a', 'z']);
  });

  it('a meta-only push skips the version check', async () => {
    await joinSample(server);
    await push(server, { version: 0, updates: [{ clientID: 'c1', changes: [11, [0, '!']] }] });
    await replayCase(server, 'updates.post.200.meta-only', { version: 0 });
    expect(server.metadata.status).toBe('draft');
  });

  it('a bad meta op is 400 rejected and applies nothing', async () => {
    const j = await joinSample(server);
    await replayCase(server, 'updates.post.400.bad-meta-op', { version: 0 });
    const bads = [{ field: 'status', add: 'x' }, { field: 'tags', add: 5 }, { field: 'tags', value: [], add: 'x' }, { field: 'tags' }, { field: 'tags', remove: ['x'] }];
    for (const bad of bads) {
      const res = await push(server, {
        version: 0, updates: [{ clientID: j.clientID, changes: [11, [0, '!']] }],
        metaOps: [{ field: 'status', value: 'ok' }, bad], selection: { anchor: 1, head: 1, version: 0, unconfirmed: 0 },
      });
      expect(res.status, JSON.stringify(bad)).toBe(400);
      expect(res.body.reason).toBe('rejected');
    }
    expect(server.doc).toBe('Hello world');
    expect(server.version).toBe(0);
    expect(server.metadata).toEqual({ tags: ['a'], related: ['OldPage'], summary: 's' });
  });

  it('a pulled meta op carries by only when a participant authored it', async () => {
    await joinSample(server, 'ada');
    const raj = await joinSample(server, 'raj');
    await push(server, { version: 0, updates: [], metaOps: [{ field: 'status', value: 'draft' }] }, 'raj', raj.participantId);
    server.externalChange({ source: 'disk', text: 'Hello world', meta: { owner: 'x' } });
    const res = await pullAll(server);
    expect(shapeDiff(res.body, byId['updates.get.200.meta-by'].response.body)).toEqual([]);
    expect(res.body.metaOps[0]).toEqual({ field: 'status', value: 'draft', by: 'raj' });
    expect(res.body.metaOps[1]).toEqual({ field: 'owner', value: 'x' });
  });

  it('emits every lifecycle event in the recorded shape', async () => {
    await joinSample(server, 'ada');
    const raj = await joinSample(server, 'raj');
    await server.handle({ method: 'POST', path: '/leave', user: 'raj', body: { sessionId: 's-1f3a', participantId: raj.participantId } });
    const kim = await joinSample(server, 'kim');
    server.externalChange({ source: 'disk', text: 'Howdy world', meta: { status: 'x' }, conflictFields: ['status'] });
    server.renamePage('NewName');
    server.evict(kim.participantId, 'lost-edit');
    const res = await pullAll(server);
    const recorded = Object.fromEntries(byId['updates.get.200.lifecycle-events'].response.body.events.map((e) => [e.type, e]));
    const got = {};
    for (const e of res.body.events) got[e.type] ??= e;
    for (const t of ['participant-joined', 'participant-left', 'external-change', 'page-renamed', 'evicted']) {
      expect(shapeDiff(got[t], recorded[t]), t).toEqual([]);
    }
    expect(got['participant-left'].reason).toBe('left');
    expect(got['external-change']).toMatchObject({ source: 'disk', version: 1, pageVersion: 8, conflictFields: ['status'] });
    expect(got['external-change'].ranges).toEqual([[1, 5]]);
    expect(got['page-renamed'].to).toBe('NewName');
    expect(got.evicted).toMatchObject({ participantId: kim.participantId, reason: 'lost-edit' });
    expect(res.body.events.map((e) => e.seq)).toEqual(res.body.events.map((_, i) => i + 1));
    expect(server.doc).toBe('Howdy world');
    expect(server.version).toBe(1);
  });

  it('an evicted participant gets 403 evicted on every post-join call', async () => {
    const j = await joinSample(server);
    server.evict(j.participantId, 'signed-out');
    const post = await push(server, { version: 0, updates: [], metaOps: [] });
    expect(post.status).toBe(403);
    expect(post.body).toEqual({ reason: 'evicted', detail: 'signed-out' });
    expect(shapeDiff(post.body, byId['updates.post.403.evicted'].response.body)).toEqual([]);
    const get = await pullAll(server, 'ada', 'pt-1', { since: 0 });
    expect(get.status).toBe(403);
    expect(shapeDiff(get.body, byId['updates.get.403.evicted'].response.body)).toEqual([]);
    for (const [method, path] of [['POST', '/publish'], ['POST', '/discard'], ['POST', '/leave']]) {
      const r = await server.handle({ method, path, user: 'ada', body: { sessionId: 's-1f3a', participantId: 'pt-1', version: 0 } });
      expect(r.status, path).toBe(403);
      expect(r.body.reason).toBe('evicted');
    }
    expect(server.isLive(j.participantId)).toBe(false);
  });

  it('publishing a frozen draft without createPages is 403 no-create', async () => {
    await joinSample(server);
    server.freeze();
    server.setCanCreate(false);
    await replayCase(server, 'publish.403.no-create', { version: 0 });
    server.setCanCreate(true);
    const ok = await server.handle({ method: 'POST', path: '/publish', user: 'ada', body: { sessionId: 's-1f3a', participantId: 'pt-1', version: 0 } });
    expect(ok.status).toBe(200);
  });

  it('a renamed session answers to its current and earlier names, and 404 to any other', async () => {
    await joinSample(server);
    const call = (pageName) => server.handle({
      method: 'POST', path: '/updates', pageName, user: 'ada',
      body: { sessionId: 's-1f3a', participantId: 'pt-1', version: 0, updates: [], metaOps: [] },
    });
    expect((await call('PageName')).status).toBe(200);
    server.renamePage('Second');
    server.renamePage('Third');
    for (const n of ['PageName', 'Second', 'Third', undefined]) expect((await call(n)).status, String(n)).toBe(200);
    expect((await call('Other')).status).toBe(404);
  });
});
