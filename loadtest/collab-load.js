/*
 * Collaborative-editing load scenario (MultiEdit design section 13.4). Standalone k6 script; run it with
 *
 *   k6 run -e BASE_URL=http://localhost:8080 -e LOADTEST_ADMIN_USER=testbot -e LOADTEST_ADMIN_PASS=... loadtest/collab-load.js
 *
 * against a LOCAL deployment started with wikantik.collab.enabled=true (never prod: it creates pages, drafts and
 * publishes). N simulated editors (default 15) are spread over M pages (default 5). Each editor joins its page's shared
 * draft, then pushes a small insertion at typing cadence (about one push per second, jittered), resyncing from the
 * server when its base is stale (409 stale-base is normal under contention and is counted, not failed). A few
 * listener VUs per page only long-poll, as idle viewers do. Roughly 1 push in 50 is followed by a publish.
 *
 * Reports: push latency (p95 target below 150 ms), the 409 and 429 rates of pushes, and publish latency.
 *
 * Environment: BASE_URL, LOADTEST_ADMIN_USER, LOADTEST_ADMIN_PASS (the editors share one account, each with its own
 * participant; at most 4 seats per page, see below), EDITORS (15), PAGES (5), LISTENERS_PER_PAGE (1), DURATION (2m), PUBLISH_EVERY (50 pushes, 0 = never).
 */
import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const USER = __ENV.LOADTEST_ADMIN_USER || 'testbot';
const PASS = __ENV.LOADTEST_ADMIN_PASS || '';
// one account holds at most 4 seats per session (CollabSessionRegistry.MAX_SEATS_PER_AUTHOR): with the shared login,
// EDITORS / PAGES + LISTENERS_PER_PAGE must stay at or under 4, or the extra joins answer 429 session-full
const EDITORS = parseInt(__ENV.EDITORS || '15', 10);
const PAGES = parseInt(__ENV.PAGES || '5', 10);
const LISTENERS_PER_PAGE = parseInt(__ENV.LISTENERS_PER_PAGE || '1', 10);
const DURATION = __ENV.DURATION || '2m';
const PUBLISH_EVERY = parseInt(__ENV.PUBLISH_EVERY || '50', 10);
// module scope runs once per VU, so the run id is drawn in setup() and handed to every VU (and teardown) as data
const pageName = (run, i) => `CollabLoad${run}P${i}`;

const pushLatency = new Trend('collab_push_latency', true);
const publishLatency = new Trend('collab_publish_latency', true);
const pullLatency = new Trend('collab_pull_latency', true);
const pushConflict = new Rate('collab_push_409_rate');
const pushThrottled = new Rate('collab_push_429_rate');
const pushFailed = new Rate('collab_push_failed_rate');
const publishes = new Counter('collab_publishes');

export const options = {
  // each VU logs in once and keeps its session: k6 otherwise clears the cookie jar every iteration (all 401s)
  noCookiesReset: true,
  scenarios: {
    editors: { executor: 'constant-vus', vus: EDITORS, duration: DURATION, exec: 'editor' },
    listeners: {
      executor: 'constant-vus', vus: Math.max(0, LISTENERS_PER_PAGE * PAGES), duration: DURATION, exec: 'listener',
    },
  },
  thresholds: {
    collab_push_latency: ['p(95)<150'],
    collab_push_failed_rate: ['rate<0.01'],
    collab_push_429_rate: ['rate<0.01'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };

/** Seeds the pages once; each VU logs in for itself (k6 gives every VU its own cookie jar). */
export function setup() {
  const run = Math.random().toString(36).slice(2, 8);
  const jar = http.cookieJar();
  const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username: USER, password: PASS }), { headers: JSON_HEADERS });
  if (login.status !== 200) fail(`setup login as ${USER} answered ${login.status}: ${login.body}`);
  const caps = http.get(`${BASE}/api/capabilities`, { headers: JSON_HEADERS });
  if (caps.status !== 200 || !caps.json('collab.enabled')) {
    fail('collaboration is not enabled on this wiki (start it with wikantik.collab.enabled=true)');
  }
  for (let i = 0; i < PAGES; i++) {
    const r = http.put(`${BASE}/api/pages/${pageName(run, i)}`,
      JSON.stringify({ content: `Collab load page ${i}.\n`, metadata: { type: 'article' } }), { headers: JSON_HEADERS });
    if (r.status !== 200) fail(`seeding ${pageName(run, i)} answered ${r.status}: ${r.body}`);
  }
  jar.clear(BASE);
  return { run };
}

export function teardown(data) {
  http.post(`${BASE}/api/auth/login`, JSON.stringify({ username: USER, password: PASS }), { headers: JSON_HEADERS });
  for (let i = 0; i < PAGES; i++) {
    http.del(`${BASE}/api/pages/${pageName(data.run, i)}`, null, { headers: JSON_HEADERS });
  }
}

// Per-VU state (module scope is per VU in k6).
let loggedIn = false;
let st = null;

function ensureLogin() {
  if (loggedIn) return;
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username: USER, password: PASS }), { headers: JSON_HEADERS });
  if (r.status !== 200) fail(`VU login answered ${r.status}`);
  loggedIn = true;
}

/** Length of a document after a CodeMirror ChangeSet JSON: kept units plus inserted text. */
function lengthAfter(changes) {
  let n = 0;
  for (const s of changes) {
    if (typeof s === 'number') n += s;
    else {
      const lines = s.slice(1);
      n += lines.reduce((a, l) => a + l.length, 0) + Math.max(0, lines.length - 1);
    }
  }
  return n;
}

function insertion(length, pos, text) {
  const sections = [];
  if (pos > 0) sections.push(pos);
  sections.push([0, ...text.split('\n')]);
  if (length - pos > 0) sections.push(length - pos);
  return sections;
}

function join(page) {
  const r = http.post(`${BASE}/api/collab/${page}/join`,
    JSON.stringify({ priorClientId: null, initialText: null, initialMeta: null }), { headers: JSON_HEADERS, tags: { name: 'join' } });
  if (r.status !== 200) {
    sleep(1);   // a refused join must not spin: the next iteration retries it
    fail(`join ${page} answered ${r.status}: ${r.body}`);
  }
  const j = r.json();
  return {
    page, sessionId: j.sessionId, participantId: j.participantId, clientId: j.clientID, version: j.version,
    length: j.doc.length, metaSeq: j.metaSeq, presenceSeq: 0, eventSeq: j.eventSeq || 0,
  };
}

function pullUrl(s) {
  return `${BASE}/api/collab/${s.page}/updates?sessionId=${encodeURIComponent(s.sessionId)}`
    + `&participantId=${encodeURIComponent(s.participantId)}&since=${s.version}&metaSince=${s.metaSeq}`
    + `&presenceSince=${s.presenceSeq}&eventsSince=${s.eventSeq}`;
}

/** One pull; applies what it returns (own pushes included) to the cursors. Returns false when the session is gone. */
function pull(s, timeout) {
  const r = http.get(pullUrl(s), { headers: JSON_HEADERS, timeout, tags: { name: 'pull' } });
  if (r.status !== 200) return false;
  pullLatency.add(r.timings.duration);
  const j = r.json();
  for (const u of j.updates) {
    s.length = lengthAfter(u.changes);
    s.version++;
  }
  s.metaSeq = j.metaSeq;
  s.presenceSeq = j.presenceSeq;
  s.eventSeq = j.eventSeq;
  return true;
}

function push(s, text) {
  const body = JSON.stringify({
    sessionId: s.sessionId, participantId: s.participantId, version: s.version, metaOps: [],
    updates: [{ clientID: s.clientId, changes: insertion(s.length, 0, text) }],
  });
  const r = http.post(`${BASE}/api/collab/${s.page}/updates`, body, { headers: JSON_HEADERS, tags: { name: 'push' } });
  pushLatency.add(r.timings.duration);
  pushConflict.add(r.status === 409);
  pushThrottled.add(r.status === 429);
  pushFailed.add(r.status !== 200 && r.status !== 409 && r.status !== 429);
  if (r.status === 200) {
    s.length += text.length;
    s.version++;
  }
  return r.status;
}

export function editor(data) {
  ensureLogin();
  if (!st) {
    st = join(pageName(data.run, (exec.vu.idInTest - 1) % PAGES));
    st.pushes = 0;
  }
  const status = push(st, 'x');
  if (status === 409) {
    // stale base: resync from the server, then carry on typing
    if (!pull(st, '10s')) st = null;
  } else if (status === 200) {
    st.pushes++;
    // no pull here: with nobody else ahead it would park for the whole poll timeout and stall the typing cadence. A
    // later push that is behind answers 409, and the pull above catches up at once (others are ahead by then).
    if (PUBLISH_EVERY > 0 && st.pushes % PUBLISH_EVERY === 0) {
      const p = http.post(`${BASE}/api/collab/${st.page}/publish`,
        JSON.stringify({ sessionId: st.sessionId, participantId: st.participantId, version: st.version, changeNote: 'load' }),
        { headers: JSON_HEADERS, tags: { name: 'publish' } });
      publishLatency.add(p.timings.duration);
      publishes.add(1);
      check(p, { 'publish 200 or conflict': (x) => x.status === 200 || x.status === 409 || x.status === 423 });
    }
  } else if (status === 403 || status === 404) {
    st = null;   // evicted or gone: rejoin next iteration
  }
  sleep(0.6 + Math.random() * 0.8);   // typing cadence
}

export function listener(data) {
  ensureLogin();
  if (!st) st = join(pageName(data.run, (exec.vu.idInTest - 1) % PAGES));
  // the real long-poll: parked until a peer pushes or the server's poll timeout elapses
  if (!pull(st, '60s')) st = null;
}
