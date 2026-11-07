/**
 * The client half of the collaboration protocol: `@codemirror/collab`'s documented peer pattern over a transport.
 *
 *  - one in-flight push of `sendableUpdates` (debounced ~100 ms; the selection piggybacks, A8/A12);
 *  - a long-poll pull loop feeding `receiveUpdates` (own updates come back as confirmations, so a lost push response
 *    can never duplicate text, 7.2);
 *  - 409 stale-base: wait for the pull to reach the server's head, then retry (the rebase is receiveUpdates');
 *  - 409 rejoin: rejoin with the prior clientID and keep the unsent edits (7.2); a different clientID cannot adopt them,
 *    and is never used to resend them: the peer first confirms the ones the server already accepted under the old
 *    clientID (a lost ack), so the recovery holds only the true remainder, then resyncs;
 *  - 410 / 413 / 400 and an unadoptable rejoin: `onResync` receives the unsynced text and the peer stops. The peer NEVER
 *    re-applies it (A10: re-applying cannot be mapped and duplicates text after a lost ack). Resync lifecycle: on resync the
 *    peer leaves its (now stale) participant, and on `rejoin-new-client` the participant that rejoin's join just created (a
 *    ghost otherwise); `whenLeft()` resolves when that leave finished, so a host can await it before any fresh join;
 *  - 401/403: keep every unsent edit and stop in `auth-required` until `resume()` (A13); frozen pages stop in `frozen`;
 *  - network errors / 5xx: exponential backoff 500 ms doubling, capped at 10 s; rate-limited: wait `retryAfterMs`.
 *
 * `createCollabPeerCore` is host-agnostic (a `{getState, dispatch}` pair) so the whole loop is testable with plain
 * EditorStates; `collabPeer` wraps it in the CodeMirror `ViewPlugin`.
 */
import { ChangeSet, Transaction } from '@codemirror/state';
import { ViewPlugin } from '@codemirror/view';
import { getSyncedVersion, receiveUpdates, sendableUpdates } from '@codemirror/collab';

const LOG = (...a) => console.warn('[collab]', ...a);

/** Status values the peer reports: live | reconnecting | resync | frozen | auth-required | disabled | unavailable. */
export function createCollabPeerCore({
  transport,
  session: initialSession,
  eventsSince: initialEventsSince = 0,
  metaSince: initialMetaSince = 0,
  host,
  getSelection,
  debounceMs = 100,
  presenceMinIntervalMs = 150,
  backoffBaseMs = 500,
  backoffCapMs = 10000,
  recoveryPullTimeoutMs = 5000,
  onStatus = () => {},
  onResync = () => {},
  onPresence = () => {},
  onEvents = () => {},
  onMetaOps = () => {},
  onMetaAck = () => {},
  onEvicted = () => {},
  onMetaFailed = () => {},
  onSessionChange = () => {},
}) {
  const session = { ...initialSession };
  let status = null;
  let destroyed = false;
  let stopped = false;
  let pushing = false;
  let pushTimer = null;
  let gateVersion = 0;
  let pushFailures = 0;
  let pullFailures = 0;
  let pullRunning = false;
  let pullAbort = null;
  // the join's meta cursor: the join's metadata already holds every op up to it (re-pulling them is not news)
  let metaSince = typeof initialMetaSince === 'number' ? initialMetaSince : 0;
  let presenceSince = 0;
  // the join's cursor (R11): a joiner never replays older events; a rejoin keeps it, continuing the same feed
  let eventsSince = typeof initialEventsSince === 'number' ? initialEventsSince : 0;
  let lastSentSelection = null;
  let lastPushAt = -Infinity;
  let rejoinPromise = null;
  let leavePromise = Promise.resolve();
  const leftIds = new Set();
  const timers = new Set();
  let syncWaiters = [];
  let metaPending = []; // wire edits not yet acked; they stay queued across stale-base, network failure and 429
  let evictedReason = null;

  const backoff = (n) => Math.min(backoffCapMs, backoffBaseMs * 2 ** Math.max(0, n - 1));

  function setStatus(next) {
    if (next === status) return;
    status = next;
    onStatus(next);
  }

  /** Resolves true after `ms`, false when the peer was destroyed meanwhile. */
  function delay(ms) {
    return new Promise((resolve) => {
      const rec = { id: null, resolve };
      rec.id = setTimeout(() => { timers.delete(rec); resolve(!destroyed); }, ms);
      timers.add(rec);
    });
  }

  function readSelection() {
    try {
      if (getSelection) return getSelection();
      const { anchor, head } = host.getState().selection.main;
      return { anchor, head };
    } catch (e) {
      LOG('could not read the selection', e);
      return null;
    }
  }
  const sameSelection = (a, b) => !!a && !!b && a.anchor === b.anchor && a.head === b.head;

  function isSynced() {
    return sendableUpdates(host.getState()).length === 0 && metaPending.length === 0 && !pushing;
  }
  function checkSynced() {
    if (!syncWaiters.length || !isSynced()) return;
    const ws = syncWaiters;
    syncWaiters = [];
    ws.forEach((w) => w(true));
  }

  /** The unsynced inserts as `[from, to]` ranges of the current document (they end in it), in document order. */
  function unsyncedRanges(state) {
    const updates = sendableUpdates(state);
    if (!updates.length) return [];
    const composed = updates.map((u) => u.changes).reduce((a, b) => a.compose(b));
    const ranges = [];
    composed.iterChanges((_fa, _ta, fromB, toB, inserted) => { if (inserted.length) ranges.push([fromB, toB]); });
    return ranges;
  }

  function unsyncedText(state) {
    const doc = state.doc.toString();
    return unsyncedRanges(state).map(([from, to]) => doc.slice(from, to)).join('\n');
  }

  /** The peer is abandoning its queued meta (resync, permission loss): hand it back so the host can `failed()` it. */
  function abandonMeta() {
    if (!metaPending.length) return;
    const edits = metaPending;
    metaPending = [];
    onMetaFailed(edits);
  }

  function stop(next) {
    stopped = true;
    pullAbort?.abort();
    setStatus(next);
    const ws = syncWaiters;
    syncWaiters = [];
    ws.forEach((w) => w(false));
  }

  function leaveQuietly(participantId) {
    return Promise.resolve()
      .then(() => transport.leave({ sessionId: session.sessionId, participantId }))
      .then(() => { leftIds.add(participantId); })
      .catch((e) => LOG('leave failed', e?.status, e?.message));
  }

  /** The recovery payload for the document as it is now (A10: handed to the host, never re-applied). */
  function capture(reason) {
    const state = host.getState();
    return { reason, docText: state.doc.toString(), unsyncedText: unsyncedText(state), unsyncedRanges: unsyncedRanges(state) };
  }

  function resync(reason, { ghostParticipantId } = {}) {
    if (stopped) return;
    const payload = capture(reason);
    stop('resync');
    abandonMeta();
    // leave the stale participant (and a ghost created by an unadoptable rejoin) before the host may join afresh
    const ids = new Set([session.participantId, ghostParticipantId].filter(Boolean));
    leavePromise = Promise.all([...ids].map(leaveQuietly));
    onResync(payload);
  }

  function schedulePush(ms = debounceMs) {
    if (destroyed || stopped || pushTimer) return;
    pushTimer = setTimeout(() => { pushTimer = null; pushNow(); }, ms);
  }

  /**
   * Before a `rejoin-new-client` resync: the server may already hold some of my unconfirmed updates (a push accepted
   * whose ack was lost, its confirming pull never arrived). They echo the old clientID, so pulling from my synced version
   * through the participant the rejoin created confirms them, and the recovery then offers only what the server never
   * got (pasting an accepted edit back would write it twice). Nothing is re-applied (A10): `receiveUpdates` only confirms
   * and rebases. When the server cannot show the range (410 after a restart, any error), every unconfirmed edit stays.
   */
  async function confirmAcceptedBeforeResync(j) {
    let since = getSyncedVersion(host.getState());
    for (let page = 0; page < 8 && since < j.version && sendableUpdates(host.getState()).length; page++) {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), recoveryPullTimeoutMs);
      let res;
      try {
        res = await transport.pull({ sessionId: j.sessionId, participantId: j.participantId, since, metaSince, presenceSince, eventsSince, signal: ctl.signal });
      } catch (e) {
        LOG('could not read what the server accepted before the resync; the recovery keeps every unconfirmed edit', e?.status, e?.body?.reason ?? e?.message);
        return;
      } finally {
        clearTimeout(timer);
      }
      if (destroyed || stopped || !res?.updates?.length) return;
      const state = host.getState();
      if (getSyncedVersion(state) !== since) return;
      host.dispatch(receiveUpdates(state, res.updates.map((u) => ({ clientID: u.clientID, changes: ChangeSet.fromJSON(u.changes) }))));
      since = getSyncedVersion(host.getState());
    }
  }

  async function rejoin() {
    if (rejoinPromise) return rejoinPromise;
    rejoinPromise = (async () => {
      let failures = 0;
      while (!destroyed && !stopped) {
        try {
          const j = await transport.join({ priorClientId: session.clientID });
          if (destroyed || stopped) { leaveQuietly(j.participantId); return; }
          if (j.clientID !== session.clientID) {
            // never resend under the new clientID: the fresh join's doc is truth, the recovery the true remainder
            await confirmAcceptedBeforeResync(j);
            if (destroyed || stopped) { leaveQuietly(j.participantId); return; }
            resync('rejoin-new-client', { ghostParticipantId: j.participantId });
            return;
          }
          const synced = getSyncedVersion(host.getState());
          if (j.version < synced) { resync('rejoin-behind', { ghostParticipantId: j.participantId }); return; }
          session.sessionId = j.sessionId;
          session.participantId = j.participantId;
          presenceSince = 0;
          lastSentSelection = null;
          onSessionChange(j);
          return true;
        } catch (err) {
          const code = err?.body?.reason;
          if (err?.status === 401 || err?.status === 403) { stop('auth-required'); return; }
          if (code === 'collab-disabled') { stop('disabled'); return; }
          if (code === 'derived-page') { stop('unavailable'); return; }
          failures += 1;
          setStatus('reconnecting');
          if (!(await delay(backoff(failures)))) return;
        }
      }
    })().finally(() => { rejoinPromise = null; });
    return rejoinPromise;
  }

  /** 403 evicted: by `detail`. Permission loss discards the unsynced text (A13); a signed-out session keeps every edit. */
  function evict(detail) {
    evictedReason = detail ?? null;
    if (detail === 'signed-out') { stop('auth-required'); return; }
    stop('forbidden');
    abandonMeta();
    onEvicted({ reason: evictedReason });
  }

  /** Common classification of an error from either loop; true when the caller should simply loop again. */
  async function handleError(err, { where, sentVersion }) {
    const status4 = err?.status;
    const reason = err?.body?.reason;
    const stale = reason === 'stale-base' || (status4 === 409 && err?.body?.accepted === false);
    if (stale && Number.isInteger(err?.body?.version)) {
      const head = err.body.version;
      if (sentVersion !== undefined && head < sentVersion) { resync('server-behind'); return; }
      gateVersion = Math.max(gateVersion, head);
      return;
    }
    // a stale-base without a numeric version is malformed: fall through to the generic backoff (no immediate retry loop)
    if (status4 === 429) {
      await delay(err.body?.retryAfterMs ?? backoff(where === 'push' ? ++pushFailures : ++pullFailures));
      return;
    }
    if (reason === 'rejoin' || err?.body?.rejoin) { await rejoin(); return; }
    if (reason === 'frozen') { stop('frozen'); return; }
    if (status4 === 403 && reason === 'evicted') { evict(err.body?.detail); return; }
    if (status4 === 401 || status4 === 403) { stop('auth-required'); return; }
    if (status4 === 410 || err?.body?.resync) { resync('resync'); return; }
    if (status4 === 413 || reason === 'oversize') { resync('oversize'); return; }
    if (reason === 'bad-since') { resync('bad-since'); return; }
    if (status4 === 400) { resync('rejected'); return; }
    if (err?.name === 'AbortError') return;
    const n = where === 'push' ? ++pushFailures : ++pullFailures;
    LOG(`${where} failed (attempt ${n}); backing off`, err?.message ?? err);
    setStatus('reconnecting');
    await delay(backoff(n));
  }

  async function pushNow() {
    if (destroyed || stopped || pushing) return;
    const state = host.getState();
    const updates = sendableUpdates(state);
    const selection = readSelection();
    const selectionChanged = !!selection && !sameSelection(selection, lastSentSelection);
    const metaOps = metaPending.slice();
    if (!updates.length && !selectionChanged && !metaOps.length) { checkSynced(); return; }
    const version = getSyncedVersion(state);
    if (updates.length && version < gateVersion) return; // the pull has not caught up with the server's head yet
    if (!updates.length && !metaOps.length) {
      const wait = presenceMinIntervalMs - (Date.now() - lastPushAt);
      if (wait > 0) { schedulePush(wait); return; }
    }
    pushing = true;
    lastPushAt = Date.now();
    try {
      const res = await transport.push({
        sessionId: session.sessionId,
        participantId: session.participantId,
        version,
        updates: updates.map((u) => ({ clientID: u.clientID, changes: u.changes.toJSON() })),
        metaOps,
        ...(selection ? { selection: { anchor: selection.anchor, head: selection.head, version, unconfirmed: updates.length } } : {}),
      });
      if (destroyed) return;
      pushFailures = 0;
      if (metaOps.length) {
        metaPending = metaPending.slice(metaOps.length);
        onMetaAck(metaOps, res?.metaSeq);
      }
      if (selection) lastSentSelection = selection;
      if (updates.length && typeof res?.version === 'number') gateVersion = Math.max(gateVersion, res.version);
      if (status === 'reconnecting') setStatus('live');
    } catch (err) {
      if (destroyed) return;
      await handleError(err, { where: 'push', sentVersion: version });
    } finally {
      pushing = false;
    }
    if (destroyed || stopped) return;
    checkSynced();
    const next = sendableUpdates(host.getState()).length;
    if (metaPending.length && next === 0) schedulePush(0);
    else if (next && getSyncedVersion(host.getState()) >= gateVersion) schedulePush(0);
    else if (next === 0 && readSelection() && !sameSelection(readSelection(), lastSentSelection)) schedulePush(presenceMinIntervalMs);
  }

  function applyPull(res, since) {
    const state = host.getState();
    if (getSyncedVersion(state) !== since) return; // cannot happen (only this loop receives); stay safe
    if (res.updates?.length) {
      const updates = res.updates.map((u) => ({ clientID: u.clientID, changes: ChangeSet.fromJSON(u.changes) }));
      host.dispatch(receiveUpdates(state, updates));
    }
    // the host needs the pull's metaSeq to retire acked edits, even when no op travelled with it
    const metaAdvanced = typeof res.metaSeq === 'number' && res.metaSeq > metaSince;
    if (typeof res.metaSeq === 'number') metaSince = Math.max(metaSince, res.metaSeq);
    if (res.metaOps?.length || metaAdvanced) onMetaOps(res.metaOps ?? [], res.metaSeq);
    if (typeof res.presenceSeq === 'number' && res.presenceSeq > presenceSince) {
      presenceSince = res.presenceSeq;
      onPresence(res.presence ?? {});
    }
    if (typeof res.eventSeq === 'number') eventsSince = res.eventSeq;
    if (res.events?.length) onEvents(res.events);
  }

  async function pullLoop() {
    if (pullRunning) return;
    pullRunning = true;
    try {
      while (!destroyed && !stopped) {
        const since = getSyncedVersion(host.getState());
        pullAbort = new AbortController();
        const startedAt = Date.now();
        let res;
        try {
          res = await transport.pull({ sessionId: session.sessionId, participantId: session.participantId, since, metaSince, presenceSince, eventsSince, signal: pullAbort.signal });
        } catch (err) {
          if (destroyed || stopped) break;
          await handleError(err, { where: 'pull' });
          if (!stopped && !destroyed && getSyncedVersion(host.getState()) >= gateVersion) schedulePush(0);
          continue;
        }
        if (destroyed || stopped) break;
        pullFailures = 0;
        setStatus('live');
        const presenceBefore = presenceSince;
        applyPull(res, since);
        checkSynced();
        if ((sendableUpdates(host.getState()).length && getSyncedVersion(host.getState()) >= gateVersion) || metaPending.length) schedulePush(0);
        // a presence move is news too (a joiner's first pull often carries only that, now that it skips older events)
        const empty = !res.updates?.length && !res.events?.length && !res.metaOps?.length && presenceSince === presenceBefore;
        if (empty && Date.now() - startedAt < 50 && !(await delay(500))) break;
      }
    } finally {
      pullRunning = false;
    }
  }

  return {
    start() { if (!destroyed) pullLoop(); },
    notifyChange() { schedulePush(); },
    /** Queues metadata edits (`{field,value}`/`{field,add}`/`{field,remove}`); they ride on the next push, text or not. */
    queueMetaEdits(edits) {
      if (destroyed || !edits?.length) return;
      metaPending.push(...edits);
      schedulePush(0);
    },
    hasPendingMeta: () => metaPending.length > 0,
    getEvictedReason: () => evictedReason,
    notifySelection() {
      const sel = readSelection();
      if (sel && !sameSelection(sel, lastSentSelection)) schedulePush(Math.max(debounceMs, presenceMinIntervalMs - (Date.now() - lastPushAt)));
    },
    /** Resolves true once every local edit is confirmed by the server; false if the peer stops first. */
    whenSynced() {
      if (destroyed || stopped) return Promise.resolve(false);
      if (isSynced()) return Promise.resolve(true);
      return new Promise((resolve) => { syncWaiters.push(resolve); });
    },
    /** Resolves once the leave started by a resync has finished (immediately when there was none). */
    whenLeft: () => leavePromise,
    /** Fresh recovery payload (current doc + unsynced text), for a host about to remount. */
    capture,
    /** Host-initiated restart: routes the unsynced text to the resync path (once) and stops the peer. */
    abandon(reason = 'restart') { resync(reason); },
    hasLeft: (participantId) => leftIds.has(participantId),
    getStatus: () => status,
    getSyncedVersion: () => getSyncedVersion(host.getState()),
    getSession: () => ({ ...session }),
    /** After `auth-required` (the user logged in again): restart both loops, keeping every unsent edit (A13). */
    resume() {
      if (destroyed || status !== 'auth-required') return;
      stopped = false;
      pushFailures = 0;
      pullFailures = 0;
      setStatus('reconnecting');
      const go = () => {
        if (destroyed || stopped) return;
        pullLoop();
        schedulePush(0);
      };
      // a signed-out eviction ended the participant on the server: rejoin (same clientID, so the edits are adoptable)
      // first. The eviction stands until a rejoin succeeds: a resume while still signed out stops in auth-required again.
      if (evictedReason !== 'signed-out') { go(); return; }
      rejoin().then((ok) => {
        if (!ok) return;
        evictedReason = null;
        go();
      });
    },
    destroy() {
      destroyed = true;
      if (pushTimer) clearTimeout(pushTimer);
      pushTimer = null;
      pullAbort?.abort();
      timers.forEach((t) => { clearTimeout(t.id); t.resolve(false); });
      timers.clear();
      const ws = syncWaiters;
      syncWaiters = [];
      ws.forEach((w) => w(false));
    },
  };
}

/** Lets React code reach the peer that CodeMirror instantiates inside the view. */
/**
 * The part of a captured recovery payload that the draft does not hold (final review item 2): `capture` is
 * `{ docText, unsyncedRanges, unsyncedText }`, `doc` the text a fresh join returned. A push the server stored whose answer
 * was lost is still unconfirmed in the capture; offering it as lost text would duplicate it on paste. The joined doc is
 * aligned with the captured one by their common prefix and suffix (tried both ways round, as a repeated character can
 * place the difference either side of it); when the difference lies wholly within the unsynced inserts, it is exactly
 * what did not arrive. Otherwise others changed the draft too, and every unsynced insert inside the difference is kept
 * (never fewer than may be missing). Returns the capture with `unsyncedText` (and its ranges) narrowed.
 */
export function unsentRemainder(capture, doc) {
  if (!capture || typeof capture.docText !== 'string' || !Array.isArray(capture.unsyncedRanges) || typeof doc !== 'string') {
    return capture;
  }
  const text = capture.docText;
  const ranges = capture.unsyncedRanges;
  const regions = [prefixFirst(text, doc), suffixFirst(text, doc)];
  const covered = (r) => r[0] >= r[1] || ranges.some(([a, b]) => a <= r[0] && r[1] <= b);
  const exact = regions.find(covered);
  const region = exact ?? [Math.min(regions[0][0], regions[1][0]), Math.max(regions[0][1], regions[1][1])];
  const kept = ranges
    .map(([a, b]) => [Math.max(a, region[0]), Math.min(b, region[1])])
    .filter(([a, b]) => a < b);
  return { ...capture, unsyncedRanges: kept, unsyncedText: kept.map(([a, b]) => text.slice(a, b)).join('\n') };
}

/** `[from, to]` of `text` that `doc` lacks, taking the longest common prefix first. */
function prefixFirst(text, doc) {
  const max = Math.min(text.length, doc.length);
  let p = 0;
  while (p < max && text[p] === doc[p]) p += 1;
  let q = 0;
  while (q < max - p && text[text.length - 1 - q] === doc[doc.length - 1 - q]) q += 1;
  return [p, text.length - q];
}

/** As {@link prefixFirst}, taking the longest common suffix first. */
function suffixFirst(text, doc) {
  const max = Math.min(text.length, doc.length);
  let q = 0;
  while (q < max && text[text.length - 1 - q] === doc[doc.length - 1 - q]) q += 1;
  let p = 0;
  while (p < max - q && text[p] === doc[p]) p += 1;
  return [p, text.length - q];
}

export function createPeerHandle() {
  const handle = {
    core: null,
    lastCore: null,
    attach(core) { handle.core = core; handle.lastCore = core; },
    detach() { handle.core = null; },
    destroy() { handle.core?.destroy(); },
    abandon(reason) { handle.core?.abandon(reason); },
    capture: (reason) => handle.core?.capture(reason) ?? null,
    hasLeft: (id) => !!handle.lastCore?.hasLeft(id),
    /** Resolves when the last peer's resync leave finished. */
    whenLeft: () => (handle.lastCore ? handle.lastCore.whenLeft() : Promise.resolve()),
    whenSynced: () => (handle.core ? handle.core.whenSynced() : Promise.resolve(true)),
    getSyncedVersion: () => (handle.core ? handle.core.getSyncedVersion() : null),
    resume: () => handle.core?.resume(),
    queueMetaEdits: (edits) => handle.core?.queueMetaEdits(edits),
  };
  return handle;
}

/** The CodeMirror extension: one peer per view. `config` is createCollabPeerCore's options minus `host`, plus `handle`. */
export function collabPeer({ handle, ...config }) {
  return ViewPlugin.fromClass(class {
    constructor(view) {
      this.core = createCollabPeerCore({
        ...config,
        host: { getState: () => view.state, dispatch: (tr) => view.dispatch(tr) },
      });
      handle?.attach(this.core);
      this.core.start();
    }

    update(u) {
      const local = u.transactions.some((tr) => tr.docChanged && !tr.annotation(Transaction.remote));
      if (local) this.core.notifyChange();
      else if (u.selectionSet) this.core.notifySelection();
    }

    destroy() {
      handle?.detach();
      this.core.destroy();
    }
  });
}
