/**
 * In-memory collab authority for tests: implements the JSON contract recorded in
 * `__fixtures__/collab-protocol.json` (see fakeCollabServer.test.js) on top of @codemirror/state.
 *
 * Two layers:
 *  - `server.handle(call)` is the HTTP layer: `{method, path, query?, body?, user?}` -> Promise<{status, body}>.
 *  - `server.transportFor(user)` adapts it to the collabTransport interface (throws `{status, body}` errors
 *    like api/client.js does) with seeded random request/response latency, so delivery is reordered
 *    deterministically under fake timers.
 *
 * Time comes only from setTimeout/Date.now so vitest fake timers drive everything.
 */
import { ChangeSet, Text } from '@codemirror/state';

/** mulberry32: small seeded PRNG. */
export function seededRandom(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const COLORS = ['#1b6ec2', '#c2410c', '#15803d', '#7e22ce', '#be123c'];
const utf8Bytes = (s) => new TextEncoder().encode(s).length;
const MAX_META_VALUE_BYTES = 64 * 1024;
const LIST_FIELDS = new Set(['tags', 'related', 'aliases']);
const jsonEq = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
const normalise = (v) => (v === undefined ? null : JSON.parse(JSON.stringify(v)));
const abortError = () => Object.assign(new Error('aborted'), { name: 'AbortError' });

export function createFakeCollabServer({
  initialText = 'Hello world',
  initialMeta = {},
  pollTimeoutMs = 25000,
  maxPageBytes = 1_000_000,
  pushesPerSecond = Infinity,
  seed = 1,
  maxLatencyMs = 0,
} = {}) {
  const rand = seededRandom(seed);
  let maxLatency = maxLatencyMs;
  let doc = Text.of(initialText.split('\n'));
  let version = 0;
  let ringStart = 0;
  const log = []; // log[i] is update number ringStart + i + 1
  let metadata = { ...initialMeta };
  let metaSeq = 0;
  const metaLog = [];
  let presenceSeq = 0;
  let eventSeq = 0;
  const events = [];
  const participants = new Map(); // participantId -> participant (two tabs of one user are two participants)
  const clientOwner = new Map(); // clientID -> author key
  let nextPid = 1;
  let nextClient = 1;
  const waiters = new Set();
  const config = {
    forbidJoin: false, derived: false, full: false, disabled: false, frozen: false,
    publishing: false, publishMergedRetry: false, publishInvalid: false, canCreate: true,
  };
  let currentName = 'PageName';
  const heldNames = new Set([currentName]);
  const calls = [];
  const drops = [];
  const pushTimes = new Map();
  let basePageVersion = 7;

  const text = () => doc.toString();
  const reply = (status, body) => ({ status, body: body === undefined ? null : body });

  function wake() {
    for (const w of [...waiters]) w.check();
  }
  function addEvent(e) {
    events.push({ seq: ++eventSeq, ...e });
    wake();
  }
  /** Resolves the request's participant: it must exist, belong to the author key (A6) and not be expired. */
  function resolve(user, sessionId, participantId) {
    if (!participantId) return { error: reply(400, { reason: 'rejected', message: 'participantId required' }) };
    const p = participants.get(participantId);
    if (sessionId && sessionId !== 's-1f3a') return { error: reply(409, { rejoin: true, reason: 'rejoin' }) };
    if (!p) return { error: reply(409, { rejoin: true, reason: 'rejoin' }) };
    if (p.owner !== user) return { error: reply(403, { reason: 'not-your-participant' }) };
    if (p.evicted) return { error: reply(403, { reason: 'evicted', detail: p.evicted }) };
    if (p.expired) return { error: reply(409, { rejoin: true, reason: 'rejoin' }) };
    p.lastSeen = Date.now();
    return { p };
  }
  const publicParticipant = (p) => ({ participantId: p.participantId, displayName: p.displayName, color: p.color });

  function join(user, body) {
    if (config.disabled) return reply(503, { reason: 'collab-disabled' });
    if (config.forbidJoin) return reply(403, { error: 'forbidden', message: 'No edit permission on PageName' });
    if (config.derived) return reply(409, { reason: 'derived-page' });
    if (config.full) return reply(429, { reason: 'session-full' });
    if (participants.size === 0 && version === 0 && body?.initialText != null) {
      doc = Text.of(String(body.initialText).split('\n'));
    }
    if (participants.size === 0 && body?.initialMeta) metadata = { ...body.initialMeta };
    const prior = body?.priorClientId;
    const held = [...participants.values()].some((x) => x.clientID === prior && !x.expired);
    const clientID = prior && clientOwner.get(prior) === user && !held ? prior : `c${nextClient++}`;
    clientOwner.set(clientID, user);
    const p = {
      participantId: `pt-${nextPid++}`,
      owner: user,
      displayName: user,
      color: COLORS[(nextPid - 2) % COLORS.length],
      clientID, lastSeen: Date.now(), expired: false, selection: null,
    };
    participants.set(p.participantId, p);
    presenceSeq += 1; // the presence map lists every live participant, so a join changes it
    addEvent({ type: 'participant-joined', participant: publicParticipant(p) });
    return reply(200, {
      sessionId: 's-1f3a', participantId: p.participantId, clientID,
      version, doc: text(), metaSeq, metadata: { ...metadata }, basePageVersion,
      participants: [...participants.values()].filter((x) => !x.expired).map(publicParticipant),
      draft: { unpublished: version > 0, contributors: [], lastEditAt: null },
    });
  }

  /** Validates the ops and resolves them against a scratch copy to server assignments; idempotent no-ops drop out. */
  function resolveMetaOps(ops) {
    const scratch = { ...metadata };
    const out = [];
    const bad = (message) => ({ error: reply(400, { reason: 'rejected', message }) });
    for (const op of ops) {
      if (!op || typeof op.field !== 'string' || !op.field) return bad('meta op needs a field');
      const present = ['value', 'add', 'remove'].filter((k) => k in op);
      if (present.length !== 1) return bad('exactly one of value, add and remove is required');
      let value;
      if (present[0] === 'value') value = normalise(op.value);
      else {
        if (!LIST_FIELDS.has(op.field)) return bad('add and remove are allowed only on tags, related and aliases');
        const item = op[present[0]];
        if (typeof item !== 'string') return bad('add and remove take a string');
        const cur = scratch[op.field] ?? null;
        // Q7: set items compare as strings (a numeric 2024 equals "2024"); a scalar is a one-item list.
        const list = Array.isArray(cur) ? [...cur] : cur !== null ? [cur] : [];
        const has = list.some((x) => String(x) === item);
        if (present[0] === 'add') {
          if (has) continue;
          value = [...list.map(String), item];
        } else {
          // Q6: REMOVE drops every equal item; an absent item or field stores nothing.
          if (!has) continue;
          value = list.filter((x) => String(x) !== item).map(String);
        }
      }
      // Q8: a single value over 64 KiB refuses the whole push.
      if (value !== null && utf8Bytes(JSON.stringify(value)) > MAX_META_VALUE_BYTES) {
        return bad(`meta value for ${op.field} exceeds ${MAX_META_VALUE_BYTES} bytes`);
      }
      if (jsonEq(value, scratch[op.field])) continue;
      if (value === null) delete scratch[op.field]; else scratch[op.field] = value;
      out.push({ field: op.field, value });
    }
    return { ops: out };
  }
  function applyMeta({ field, value }, by) {
    if (value === null) delete metadata[field]; else metadata[field] = value;
    metaSeq += 1;
    metaLog.push({ field, value, ...(by ? { by } : {}), seq: metaSeq });
  }

  function push(user, body) {
    const r = resolve(user, body.sessionId, body.participantId);
    if (r.error) return r.error;
    const { p } = r;
    if (config.frozen) return reply(409, { reason: 'frozen' });
    if (pushesPerSecond !== Infinity) {
      const now = Date.now();
      const recent = (pushTimes.get(p.participantId) ?? []).filter((t) => now - t < 1000);
      recent.push(now);
      pushTimes.set(p.participantId, recent);
      if (recent.length > pushesPerSecond) return reply(429, { reason: 'rate-limited', retryAfterMs: 500 });
    }
    const updates = body.updates ?? [];
    if (updates.length > 0 && body.version !== version) {
      return reply(409, { accepted: false, reason: 'stale-base', version });
    }
    let next = doc;
    const parsed = [];
    for (const u of updates) {
      if (u.clientID !== p.clientID) return reply(400, { reason: 'rejected', message: 'clientID is not this participant\'s' });
      let cs;
      try { cs = ChangeSet.fromJSON(u.changes); } catch (e) {
        return reply(400, { reason: 'rejected', message: `malformed changes: ${e.message}` });
      }
      if (cs.length !== next.length) {
        return reply(400, { reason: 'rejected', message: `changes length ${cs.length} does not match document length ${next.length}` });
      }
      next = cs.apply(next);
      if (utf8Bytes(next.toString()) > Math.max(maxPageBytes, utf8Bytes(doc.toString()))) {
        return reply(413, { reason: 'oversize', message: 'document would exceed maxPageBytes' });
      }
      parsed.push({ clientID: u.clientID, changes: cs.toJSON() });
    }
    const resolved = resolveMetaOps(body.metaOps ?? []);
    if (resolved.error) return resolved.error;
    doc = next;
    for (const u of parsed) log.push(u);
    version += parsed.length;
    for (const op of resolved.ops) applyMeta(op, p.displayName);
    if (body.selection) {
      const { anchor, head, version: v, unconfirmed } = body.selection;
      p.selection = { anchor, head, version: v, unconfirmed };
      presenceSeq += 1;
    }
    wake();
    return reply(200, { accepted: true, version, metaSeq });
  }

  const cursors = (q) => ({
    since: Number(q.since),
    metaSince: Number(q.metaSince ?? 0),
    presenceSince: Number(q.presenceSince ?? 0),
    eventsSince: Number(q.eventsSince ?? 0),
  });
  function buildPull(p, s) {
    const presence = {};
    if (presenceSeq > s.presenceSince) {
      for (const o of participants.values()) {
        // every other live participant; one without a cursor has all-null selection fields (server fix 93b0e48ed2)
        if (o === p || o.expired) continue;
        presence[o.participantId] = {
          displayName: o.displayName, color: o.color,
          anchor: null, head: null, version: null, unconfirmed: null, ...(o.selection ?? {}),
        };
      }
    }
    return reply(200, {
      updates: log.slice(s.since - ringStart).map((u) => ({ clientID: u.clientID, changes: u.changes })),
      metaOps: metaLog.filter((m) => m.seq > s.metaSince).map(({ seq: _seq, ...op }) => op),
      metaSeq,
      presence, presenceSeq,
      events: events.filter((e) => e.seq > s.eventsSince),
      eventSeq,
    });
  }
  const hasNews = (s) => s.since < version || metaSeq > s.metaSince || presenceSeq > s.presenceSince || eventSeq > s.eventsSince;
  function pull(user, q) {
    const r = resolve(user, q.sessionId, q.participantId);
    if (r.error) return Promise.resolve(r.error);
    const { p } = r;
    const s = cursors(q);
    if (!(s.since >= 0) || s.since > version) return Promise.resolve(reply(400, { reason: 'bad-since' }));
    if (s.since < ringStart) return Promise.resolve(reply(410, { resync: true, reason: 'resync' }));
    if (hasNews(s)) return Promise.resolve(buildPull(p, s));
    return new Promise((resolve) => {
      const w = { check() { if (p.evicted) done(reply(403, { reason: 'evicted', detail: p.evicted })); else if (hasNews(s)) done(buildPull(p, s)); } };
      const timer = setTimeout(() => done(reply(200, {
        updates: [], metaOps: [], metaSeq, presence: {}, presenceSeq, events: [], eventSeq,
      })), pollTimeoutMs);
      function done(res) {
        clearTimeout(timer);
        waiters.delete(w);
        resolve(res);
      }
      waiters.add(w);
    });
  }

  function publish(user, body) {
    const r = resolve(user, body.sessionId, body.participantId);
    if (r.error) return r.error;
    const { p } = r;
    if (config.frozen && !config.canCreate) return reply(403, { reason: 'no-create' });
    if (config.publishing) return reply(423, { reason: 'publish-in-progress' });
    if (config.publishMergedRetry) {
      config.publishMergedRetry = false;
      return reply(409, { reason: 'publish-merged-retry' });
    }
    // the server publishes head (section 7.3): only a version it never issued is not-synced
    if (!Number.isInteger(body.version) || body.version < 0 || body.version > version) return reply(409, { reason: 'not-synced' });
    if (config.publishInvalid) {
      return reply(422, { error: 'frontmatter_validation_failed', violations: [{ field: 'tags', severity: 'ERROR', message: 'tags must be a list' }] });
    }
    basePageVersion += 1;
    addEvent({ type: 'published', by: p.displayName, pageVersion: basePageVersion });
    return reply(200, { pageVersion: basePageVersion, warnings: [], mathWarnings: [] });
  }

  async function handle({ method, path, pageName, query = {}, body = {}, user = 'ada' }) {
    const entry = { method, path, user, query, body, status: null, reason: null };
    calls.push(entry);
    const key = `${method} ${path}`;
    let res;
    if (pageName !== undefined && key !== 'POST /join' && !heldNames.has(pageName)) res = reply(404, { error: 'not_found' });
    else if (key === 'POST /join') res = join(user, body);
    else if (key === 'POST /updates') res = push(user, body);
    else if (key === 'GET /updates') res = await pull(user, query);
    else if (key === 'POST /publish') res = publish(user, body);
    else if (key === 'POST /discard') res = resolve(user, body.sessionId, body.participantId).error ?? reply(204);
    else if (key === 'POST /leave') {
      const p = participants.get(body.participantId);
      if (p && p.owner !== user) res = reply(403, { reason: 'not-your-participant' });
      else if (p?.evicted) res = reply(403, { reason: 'evicted', detail: p.evicted });
      else {
        if (p && !p.expired) {
          p.expired = true;
          presenceSeq += 1;
          addEvent({ type: 'participant-left', participantId: p.participantId, reason: 'left' });
        }
        res = reply(204);
      }
    } else res = reply(404, { error: 'not_found' });
    const dropIdx = drops.findIndex((d) => d.method === method && d.path === path);
    if (dropIdx >= 0) {
      drops.splice(dropIdx, 1);
      res = { dropped: true };
    }
    entry.status = res.dropped ? 'dropped' : res.status;
    entry.reason = res.body?.reason;
    return res;
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const latency = () => (maxLatency > 0 ? Math.floor(rand() * (maxLatency + 1)) : 0);

  function transportFor(user) {
    async function call(method, path, { query, body, signal } = {}) {
      if (signal?.aborted) throw abortError();
      const pre = latency();
      if (pre) await sleep(pre);
      const resPromise = handle({ method, path, query, body, user });
      const res = signal
        ? await Promise.race([
          resPromise,
          new Promise((_, rej) => signal.addEventListener('abort', () => rej(abortError()), { once: true })),
        ])
        : await resPromise;
      const post = latency();
      if (post) await sleep(post);
      if (res.dropped) throw new TypeError('Failed to fetch');
      if (res.status >= 400) {
        throw Object.assign(new Error(res.body?.message || res.body?.reason || `HTTP ${res.status}`), { status: res.status, body: res.body });
      }
      return res.body;
    }
    return {
      join: (b) => call('POST', '/join', { body: { priorClientId: b?.priorClientId ?? null, initialText: b?.initialText ?? null, initialMeta: b?.initialMeta ?? null } }),
      push: (b) => call('POST', '/updates', { body: b }),
      pull: ({ signal, ...q }) => call('GET', '/updates', { query: q, signal }),
      publish: (b) => call('POST', '/publish', { body: b }),
      discard: (b) => call('POST', '/discard', { body: b }),
      leave: (b) => call('POST', '/leave', { body: b }),
      leaveBeacon: (b) => { handle({ method: 'POST', path: '/leave', body: b, user }); return true; },
    };
  }

  return {
    handle, transportFor, config, calls,
    /** Changes the random per-request/response latency bound (set it after the editors joined under fake timers). */
    setLatency(ms) { maxLatency = ms; },
    get doc() { return text(); },
    get version() { return version; },
    get metadata() { return { ...metadata }; },
    /** An update from outside the editors (an agent or a disk save merged by the session). */
    externalReplace(from, to, insert) {
      const cs = ChangeSet.of({ from, to, insert }, doc.length);
      doc = cs.apply(doc);
      log.push({ clientID: 'server', changes: cs.toJSON() });
      version += 1;
      wake();
    },
    /** Drop the response of the next matching call (the server still applies it): a lost acknowledgement. */
    dropNextResponse(method, path) { drops.push({ method, path }); },
    expire(user) { for (const p of participants.values()) if (p.owner === user) p.expired = true; },
    expireParticipant(id) { const p = participants.get(id); if (p) p.expired = true; },
    liveParticipantIds: () => [...participants.values()].filter((p) => !p.expired).map((p) => p.participantId),
    isLive: (id) => { const p = participants.get(id); return !!p && !p.expired; },
    /** Truncate the in-memory ring below `v`, so a pull from an older version gets 410. */
    truncateBelow(v) { log.splice(0, v - ringStart); ringStart = v; },
    /** The page is renamed: the session lives on and answers to every name it has held. */
    renamePage(to) {
      currentName = to;
      heldNames.add(to);
      addEvent({ type: 'page-renamed', to });
    },
    get pageName() { return currentName; },
    /** The draft is frozen (its page was deleted under it): pushes answer 409 frozen. */
    freeze() { config.frozen = true; addEvent({ type: 'page-deleted' }); },
    setCanCreate(v) { config.canCreate = !!v; },
    /** The server evicts a participant: its next post-join call answers 403 evicted. */
    evict(participantId, reason) {
      const p = participants.get(participantId);
      if (!p) throw new Error(`evict: unknown participant ${participantId}`);
      p.evicted = reason;
      p.expired = true;
      presenceSeq += 1;
      addEvent({ type: 'evicted', participantId, reason });
      addEvent({ type: 'participant-left', participantId, reason: 'evicted' });
      wake();
    },
    /**
     * A change from outside the editors (disk save, agent) merged by the session: `text` replaces the document
     * (as one span replacement), `meta` assigns fields (server ops, no author). Emits an external-change event.
     */
    externalChange({ source, text: nextText, meta = {}, conflictFields = [], recreated } = {}) {
      let ranges = [];
      if (nextText != null && nextText !== text()) {
        const old = text();
        let pre = 0;
        while (pre < old.length && pre < nextText.length && old[pre] === nextText[pre]) pre += 1;
        let suf = 0;
        while (suf < old.length - pre && suf < nextText.length - pre
          && old[old.length - 1 - suf] === nextText[nextText.length - 1 - suf]) suf += 1;
        const cs = ChangeSet.of({ from: pre, to: old.length - suf, insert: nextText.slice(pre, nextText.length - suf) }, doc.length);
        doc = cs.apply(doc);
        log.push({ clientID: 'server', changes: cs.toJSON() });
        version += 1;
        ranges = [[pre, nextText.length - suf]];
      }
      for (const [field, value] of Object.entries(meta)) {
        const v = normalise(value);
        if (!jsonEq(v, metadata[field])) applyMeta({ field, value: v }, null);
      }
      basePageVersion += 1;
      addEvent({
        type: 'external-change', source, pageVersion: basePageVersion, version, ranges, conflictFields,
        ...(recreated ? { recreated: true } : {}),
      });
    },
    forgetClientIds() { clientOwner.clear(); },
  };
}
