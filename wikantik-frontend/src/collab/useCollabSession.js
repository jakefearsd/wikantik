/**
 * React hook owning one collaborative-editing session for a page: joins on mount, leaves on unmount and on `pagehide`
 * (via `navigator.sendBeacon`), and exposes the pieces the editor needs.
 *
 * Returns `{ status, participants, presence, events, resync, dismissResync, restart, resume, publish, discard, docAtJoin, startVersion,
 * clientID, sessionId, metadata, draft, published, notice, oversizeLimit, draftSummary, unsyncedParticipants, basePageVersion,
 * extensions, meta, presenceNow, evictedReason, externalChange }`. `resume()` restarts the peer after `auth-required`
 * once the user has signed in again, keeping every unsent edit (A13). `extensions` is the `collab()` + `collabPeer()` pair to
 * mount in a CodeMirror view initialised from `docAtJoin` (A4: the editor is uncontrolled in collab mode; A10: on
 * `status === 'resync'` the host shows `resync.unsyncedText` in a recovery panel and calls `restart()`: it awaits the leave of
 * the stale participant (and of a rejoin ghost, both done by the peer) and only then joins afresh, so the old participant is
 * gone from the server before the new one exists). `events` keeps the last 200. `participants` is self (from join) plus the presence entries; `renamedTo` is set after a `page-renamed` event, which retargets the transport in place; it belongs to that session (a later navigation to another page clears it).
 *
 * `status`: idle | joining | live | reconnecting | resync | frozen | auth-required, or a refused join: disabled |
 * unavailable (derived page) | full | forbidden | error. A `page-deleted` event makes it `frozen`; an eviction for lost
 * edit rights makes it `forbidden` with `evictedReason` set (a signed-out eviction is `auth-required`, edits kept, A13).
 *
 * `meta` (A14, null until joined) co-edits the frontmatter: `view()` is the metadata to show (the server's, with my
 * unconfirmed edits on top, and a focused field held at my value); `setField(key, value)` routes a field widget's value
 * (set fields as add/remove, others as a debounced SET via `edit`); `add`/`remove`; `change(prev, next)` diffs a whole
 * object into field ops; `flush()` sends what the debounce still holds; `focus(key)`/`blur(key)` implement §8.2 (a
 * remote op on my focused field leaves my input alone and lands in `remoteChanged[key]` = the author's name; on blur my
 * value is re-sent if I changed it since focus, else the remote value shows); `conflicts[key]` names an outside writer
 * whose change to that field was not applied over mine; `pending` is true while an edit is not yet acknowledged.
 * `presenceNow()` reads the live presence map (for click-time checks between renders). After `page-renamed` the hook keeps
 * the session when `pageName` becomes the new name (no leave, no rejoin).
 *
 * Draft state (Phase 3, display names only): `draft` is `{ unpublished, contributors, lastEditAt }`, seeded from the join and
 * replaced by each `draft-state` event; the server sends that only when the contributor set or `unpublished` changes, so
 * `lastEditAt` is advanced here on every received update or meta op. `published` is `{ by, pageVersion, seq }` from the
 * latest `published` event; `notice` is `{ type, by?, bytes?, limit?, seq }` from the latest `publish-failed`,
 * `draft-discarded` or `oversize`; `oversizeLimit` is the limit an `oversize` event reported. Events at or below the join's
 * `eventSeq` predate the join: the peer's pull starts there (R11), and one that still arrives sets none of these. A join that
 * answers `frozen` is frozen at once. `draftSummary()` flushes metadata, waits for the peer to sync and fetches the
 * publish dialog's summary: `{ ok: true, summary } | { ok: false, status, reason }`. `unsyncedParticipants()` names the
 * participants whose presence reports unconfirmed edits (§16 decision 1). `publish(changeNote, { markupSyntax })` sends
 * `markupSyntax` only when given.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { collab } from '@codemirror/collab';
import { Transaction } from '@codemirror/state';
import { ViewPlugin } from '@codemirror/view';
import { createCollabTransport } from './collabTransport.js';
import { collabPeer, createPeerHandle, unsentRemainder } from './collabPeer.js';
import { displayNames, labelOf, remoteCursors, setRemotePresence } from './remoteCursors.js';
import { externalHighlights, addExternalHighlight } from './externalHighlights.js';
import { createMetaQueue, createMetaState, diffMeta, SET_FIELDS } from './collabMeta.js';

const MAX_PUBLISH_ATTEMPTS = 3;
const MAX_EVENTS = 200;
const META_DEBOUNCE_MS = 400;
const LAST_EDIT_RESOLUTION_MS = 30_000;
/** A first join answered 503 `store` (the draft store blinked, or a rename into the page is in flight) is retried. */
const JOIN_STORE_RETRIES = 3;
const JOIN_RETRY_BASE_MS = 500;

const isStoreDown = (err) => err?.status === 503 && err?.body?.reason === 'store';

const jsonOf = (v) => JSON.stringify(v === undefined ? null : v);
const isEmptyValue = (v) => v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);

/** True when applying the wire edit `e` to `meta` would change nothing (the session already holds it). */
function isAppliedIn(meta, e) {
  const probe = createMetaState({ meta });
  if ('add' in e) probe.add(e.field, e.add);
  else if ('remove' in e) probe.remove(e.field, e.remove);
  else probe.edit(e.field, e.value);
  return diffMeta(meta, probe.view()).length === 0;
}

/** One wire edit as a line a person can read (recovery panels, copied text). */
export function describeMetaEdit(e) {
  const show = (v) => (typeof v === 'string' ? v : JSON.stringify(v));
  if ('add' in e) return `${e.field}: add ${show(e.add)}`;
  if ('remove' in e) return `${e.field}: remove ${show(e.remove)}`;
  return e.value === null || e.value === undefined ? `${e.field}: (removed)` : `${e.field}: ${show(e.value)}`;
}

/** The display/participant shape of a join's participant list, minus self: a presence map without cursors yet. */
function presenceFromJoin(j) {
  const map = {};
  for (const p of j.participants ?? []) {
    if (p.participantId === j.participantId) continue;
    map[p.participantId] = { displayName: p.displayName, color: p.color, anchor: null, head: null, version: null, unconfirmed: null };
  }
  return map;
}

/** A join's `draft` block or a `draft-state` event as `{ unpublished, contributors, lastEditAt }`. */
function draftOf(raw) {
  return {
    unpublished: !!raw?.unpublished,
    contributors: displayNames(raw?.contributors),
    lastEditAt: typeof raw?.lastEditAt === 'string' ? raw.lastEditAt : null,
  };
}

/** `GET …/draft` as the publish dialog reads it. `diff` is server-rendered HTML: show it only through `DiffHtml`. */
function summaryOf(body) {
  return {
    ...draftOf(body),
    basePageVersion: typeof body?.basePageVersion === 'number' ? body.basePageVersion : null,
    version: typeof body?.version === 'number' ? body.version : null,
    diff: typeof body?.diff === 'string' ? body.diff : '',
    metaChanges: (Array.isArray(body?.metaChanges) ? body.metaChanges : [])
      .filter((c) => c && typeof c.field === 'string')
      .map((c) => ({ field: c.field, before: c.before ?? null, after: c.after ?? null })),
  };
}

const NOTICE_TYPES = ['publish-failed', 'draft-discarded', 'oversize'];
const finite = (n) => (typeof n === 'number' && Number.isFinite(n) ? n : null);

function joinFailureStatus(err) {
  const reason = err?.body?.reason;
  if (reason === 'collab-disabled') return 'disabled';
  if (reason === 'derived-page') return 'unavailable';
  if (reason === 'session-full') return 'full';
  if (err?.status === 401 || err?.status === 403) return 'forbidden';
  return 'error';
}

export function useCollabSession(pageName, { transport: transportOverride, initialText, initialMeta, enabled = true } = {}) {
  // A rename keeps the session: every name it has held maps back to the name it was joined under (the effect's key).
  const [aliases, setAliases] = useState(null); // { joinedAs, names: Set }
  if (aliases && !aliases.names.has(pageName)) setAliases(null);
  const sessionName = aliases?.names.has(pageName) ? aliases.joinedAs : pageName;
  const key = `${sessionName}`;
  const nameRef = useRef(pageName);
  useEffect(() => { nameRef.current = pageName; });
  const [state, setState] = useState({ key, status: 'joining' });
  const [self, setSelf] = useState(null);
  const [renamed, setRenamed] = useState(null); // { key, to }: scoped to the session it happened in
  const [deleted, setDeleted] = useState(null); // the key whose page was deleted under the session
  const [evicted, setEvicted] = useState(null); // { key, reason, unsyncedText, metaEdits }
  const [externalChange, setExternalChange] = useState(null);
  // meta co-editing (A14): one state + debounce queue per join, kept in a ref; `metaTick` re-renders on every change
  const metaRef = useRef(null);
  const [metaTick, setMetaTick] = useState(0);
  const [remoteChanged, setRemoteChanged] = useState({});
  const [conflicts, setConflicts] = useState({});
  // the focused field: the ref for pull callbacks ({field, local, dirty, sent: Set<json>}), the state for rendering
  const focusRef = useRef(null);
  const [focus, setFocus] = useState(null);
  const carryMetaRef = useRef([]); // edits a restart abandoned, offered (never auto-sent) in the next session
  const [carried, setCarried] = useState(null); // { key, edits } — the carried edits the new session does not hold yet
  const viewRef = useRef(null);
  const presenceRef = useRef({});
  const [presence, setPresence] = useState({});
  const [events, setEvents] = useState([]);
  const [resync, setResync] = useState(null);
  // set by restart(): the next join narrows the recovery payload to what that join's doc does not hold
  const narrowOnJoinRef = useRef(false);
  const transportRef = useRef(null);
  const sessionRef = useRef(null);
  const handleRef = useRef(null);
  const [generation, setGeneration] = useState(0);
  const initialRef = useRef({ initialText, initialMeta });
  // the join payload is read once per join; later prop changes must not rejoin
  useEffect(() => { initialRef.current = { initialText, initialMeta }; });

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    // a rejoin after a rename addresses the page by its current name
    const transport = transportOverride ?? createCollabTransport(nameRef.current);
    transportRef.current = transport;
    const handle = createPeerHandle();
    handleRef.current = handle;
    const patch = (p) => setState((s) => (s.key === key ? { ...s, ...p } : s));
    let metaSession = null;

    const payload = { initialText: initialRef.current.initialText ?? null, initialMeta: initialRef.current.initialMeta ?? null };
    // a join refused 503 store is retried with backoff (500 ms, 1 s, 2 s) before it is reported as an error
    const attemptJoin = (n) => transport.join(payload).catch((err) => {
      if (cancelled || n >= JOIN_STORE_RETRIES || !isStoreDown(err)) throw err;
      return new Promise((resolve) => { setTimeout(resolve, JOIN_RETRY_BASE_MS * 2 ** n); })
        .then(() => (cancelled ? Promise.reject(err) : attemptJoin(n + 1)));
    });
    attemptJoin(0)
      .then((j) => {
        if (cancelled) {
          transport.leave({ sessionId: j.sessionId, participantId: j.participantId })
            .catch((e) => console.warn('[collab] leave after a cancelled join failed', e));
          return;
        }
        sessionRef.current = { sessionId: j.sessionId, clientID: j.clientID, participantId: j.participantId };
        if (narrowOnJoinRef.current) {
          // never offer text the draft already holds (a stored push whose answer was lost): pasting it would duplicate it
          narrowOnJoinRef.current = false;
          setResync((prev) => (prev ? unsentRemainder(prev, j.doc) : prev));
        }
        const bump = () => setMetaTick((t) => t + 1);
        const metaState = createMetaState({ meta: j.metadata ?? {}, metaSeq: j.metaSeq ?? 0 });
        /** Hands every unsent edit to the peer (they ride on its next push); without a mounted peer they wait. */
        const sendMeta = () => {
          if (!handle.core || metaSession?.closing) return;
          const edits = metaState.takeSendable();
          if (edits.length) handle.queueMetaEdits(edits);
          bump();
        };
        const metaQueue = createMetaQueue({ debounceMs: META_DEBOUNCE_MS, onSend: () => sendMeta() });
        metaSession = { state: metaState, queue: metaQueue, send: sendMeta, handle };
        metaRef.current = metaSession;
        // A10: never re-apply blind. A carried edit the new session already holds is dropped; the rest are offered in the
        // recovery panel (apply each, or copy), because sending them now could overwrite a newer value.
        const joinedMeta = j.metadata ?? {};
        const unapplied = carryMetaRef.current.filter((e) => !isAppliedIn(joinedMeta, e));
        carryMetaRef.current = [];
        setCarried(unapplied.length ? { key, edits: unapplied } : null);
        focusRef.current = null;
        setFocus(null);
        setRemoteChanged({});
        setConflicts({});
        const seeded = presenceFromJoin(j);
        presenceRef.current = seeded;
        setPresence(seeded);
        // the draft's page was deleted before this join: frozen now, whether or not page-deleted is still in the window
        setDeleted((d) => (j.frozen ? key : d === key ? null : d));
        // the peer's pull starts at the join's cursor (R11); events at or below it are still not news if one arrives
        const joinEventSeq = typeof j.eventSeq === 'number' ? j.eventSeq : 0;
        const isNews = (e) => !(typeof e.seq === 'number' && e.seq <= joinEventSeq);
        // coarse on purpose: "last edit" is shown to the minute, and a re-render per confirmed keystroke batch is waste
        const touchDraft = () => setState((s) => {
          if (s.key !== key || !s.draft) return s;
          const now = Date.now();
          const last = Date.parse(s.draft.lastEditAt ?? '');
          if (!Number.isNaN(last) && now >= last && now - last < LAST_EDIT_RESOLUTION_MS) return s;
          return { ...s, draft: { ...s.draft, lastEditAt: new Date(now).toISOString() } };
        });
        const dispatchToView = (effects) => {
          try {
            viewRef.current?.dispatch({ effects });
          } catch (e) {
            console.warn('[collab] could not dispatch to the editor view', e);
          }
        };
        // lets the hook reach the CodeMirror view that mounts these extensions (presence and external changes are effects)
        const bridge = ViewPlugin.define((view) => {
          viewRef.current = view;
          if (Object.keys(presenceRef.current).length) {
            // a presence map that arrived before the view mounted
            queueMicrotask(() => { if (viewRef.current === view) dispatchToView(setRemotePresence.of(presenceRef.current)); });
          }
          return {
            // a received update (its own confirmations included) is an edit to the draft: the server's lastEditAt moved
            update(u) { if (u.transactions.some((tr) => tr.annotation(Transaction.remote))) touchDraft(); },
            destroy() { if (viewRef.current === view) viewRef.current = null; },
          };
        });
        let purged = false; // an `evicted` event with reason purged addressed to this participant has arrived
        const settleEviction = (reason, unsyncedText) => {
          const metaEdits = metaState.unconfirmed();
          if (metaSession) metaSession.closing = true;
          metaQueue.dispose();
          metaState.discardPending();
          bump();
          setEvicted({ key, reason, unsyncedText, metaEdits });
        };
        const selfOf = (rj) => (rj.participants ?? []).find((p) => p.participantId === rj.participantId) ?? null;
        const extensions = [
          remoteCursors(),
          externalHighlights(),
          bridge,
          collab({ startVersion: j.version, clientID: j.clientID }),
          collabPeer({
            transport,
            handle,
            session: { sessionId: j.sessionId, clientID: j.clientID, participantId: j.participantId },
            eventsSince: joinEventSeq,
            metaSince: typeof j.metaSeq === 'number' ? j.metaSeq : 0,
            onStatus: (status) => patch({ status }),
            onResync: (payload) => {
              // an administrator purged the draft (the evicted event arrived before the 409 rejoin that stopped the peer):
              // that is the purge notice, with the unsynced text kept under it, not a lost place
              if (purged) { settleEviction('purged', payload.unsyncedText ?? ''); patch({ status: 'forbidden' }); return; }
              setResync(payload);
            },
            onMetaOps: (ops, metaSeq) => {
              metaState.applyRemote(ops, metaSeq);
              if (ops.length) touchDraft();
              const f = focusRef.current;
              if (f) {
                // a remote op on my focused field: my input stays as it is; the field says who changed it (§8.2)
                const remote = ops.find((op) => op.field === f.field && !f.sent.has(jsonOf(op.value)));
                if (remote) {
                  f.remoteBy = remote.by || 'an outside change';
                  setRemoteChanged((m) => ({ ...m, [f.field]: f.remoteBy }));
                }
              }
              bump();
            },
            onMetaAck: (edits, metaSeq) => { metaState.acked(edits, metaSeq); bump(); },
            onMetaFailed: (edits) => { metaState.failed(edits); bump(); },
            onEvicted: ({ reason }) => {
              // lost edit rights (A13): the unsynced text and field edits are not saved; keep a copy to offer, and stop
              // showing the abandoned field edits as if they were current
              const cap = handle.capture('evicted');
              settleEviction(reason, cap?.unsyncedText ?? '');
            },
            onPresence: (map) => {
              presenceRef.current = map;
              setPresence(map);
              dispatchToView(setRemotePresence.of(map));
            },
            onEvents: (batch) => {
              setEvents((prev) => [...prev, ...batch].slice(-MAX_EVENTS));
              for (const e of batch) {
                if (e.type === 'evicted' && e.reason === 'purged'
                    && (!e.participantId || e.participantId === sessionRef.current?.participantId)) {
                  // stop the peer now: it would otherwise rejoin the fresh draft and push the unsynced text into it
                  purged = true;
                  handle.abandon('purged');
                }
                // no pre-mount replay needed: the peer (and so its pull loop) only starts once the view mounts
                if (e.type === 'external-change') {
                  if (e.ranges?.length) {
                    dispatchToView(addExternalHighlight.of({ ranges: e.ranges, version: e.version, id: `ext-${e.seq ?? e.version}` }));
                  }
                  setExternalChange({ ...e, key });
                  if (e.conflictFields?.length) {
                    setConflicts((m) => ({ ...m, ...Object.fromEntries(e.conflictFields.map((f) => [f, e.source || 'an outside change'])) }));
                  }
                } else if (e.type === 'page-renamed' && e.to) {
                  // the session lives on under the new name: later calls address it, no leave and no rejoin
                  transport.retarget?.(e.to);
                  setRenamed({ key, to: e.to });
                  setAliases((a) => ({ joinedAs: a?.joinedAs ?? key, names: new Set([...(a?.names ?? [key]), e.to]) }));
                } else if (e.type === 'page-deleted') {
                  setDeleted(key);
                } else if (e.type === 'draft-state' && isNews(e)) {
                  patch({ draft: draftOf(e) });
                } else if (e.type === 'published' && isNews(e)) {
                  patch({ published: { by: labelOf(e.by), pageVersion: finite(e.pageVersion), seq: e.seq ?? null } });
                } else if (NOTICE_TYPES.includes(e.type) && isNews(e)) {
                  const notice = e.type === 'oversize'
                    ? { type: e.type, bytes: finite(e.bytes), limit: finite(e.limit), seq: e.seq ?? null }
                    // `by` is absent when the author has no display name: null, so the editor says "Someone"
                    : { type: e.type, by: typeof e.by === 'string' && e.by.trim() ? e.by.trim() : null, seq: e.seq ?? null };
                  patch(e.type === 'oversize' && notice.limit !== null ? { notice, oversizeLimit: notice.limit } : { notice });
                }
              }
            },
            onSessionChange: (rj) => {
              sessionRef.current = { sessionId: rj.sessionId, clientID: rj.clientID, participantId: rj.participantId };
              setSelf(selfOf(rj));
              presenceRef.current = {};
              dispatchToView(setRemotePresence.of({}));
              setPresence({});
            },
          }),
        ];
        setSelf(selfOf(j));
        setMetaTick((t) => t + 1);
        patch({
          status: 'live', docAtJoin: j.doc, startVersion: j.version, clientID: j.clientID, sessionId: j.sessionId,
          metadata: j.metadata ?? {}, metaSeq: j.metaSeq ?? 0, basePageVersion: j.basePageVersion ?? null,
          draft: draftOf(j.draft), published: null, notice: null, oversizeLimit: null, extensions, metaSession,
        });
      })
      .catch((err) => {
        if (cancelled) return;
        console.warn('[collab] join failed', err?.status, err?.body?.reason ?? err?.message);
        patch({ status: joinFailureStatus(err) });
      });

    const onPageHide = () => {
      if (sessionRef.current) transport.leaveBeacon({ sessionId: sessionRef.current.sessionId, participantId: sessionRef.current.participantId });
    };
    window.addEventListener('pagehide', onPageHide);

    return () => {
      cancelled = true;
      window.removeEventListener('pagehide', onPageHide);
      const s = sessionRef.current;
      sessionRef.current = null;
      // an edit still inside the debounce window is flushed, never dropped: the peer is going away, so it (and any edit
      // not yet handed to the peer) goes out on one last meta-only push before the leave
      let finalEdits = [];
      if (metaSession) {
        const m = metaSession;
        m.closing = true; // the queue's flush must not hand edits to the peer that is about to be destroyed
        m.queue.dispose();
        finalEdits = m.state.takeSendable();
        if (metaRef.current === m) metaRef.current = null;
      }
      const version = handle.getSyncedVersion();
      handle.destroy();
      const leave = (p) => transport.leave(p).catch((e) => console.warn('[collab] leave failed', e));
      if (!s) {
        // a restart (resync) already left: the edits go to the next session instead
        if (finalEdits.length) carryMetaRef.current = [...carryMetaRef.current, ...finalEdits];
        return;
      }
      const ids = { sessionId: s.sessionId, participantId: s.participantId };
      if (!finalEdits.length) { leave(ids); return; }
      transport.push({ ...ids, version: version ?? 0, updates: [], metaOps: finalEdits })
        .catch((e) => console.warn('[collab] final metadata push failed', e?.status, e?.message))
        .then(() => leave(ids));
    };
  }, [enabled, transportOverride, key, generation]);

  const dismissResync = useCallback(() => setResync(null), []);
  const resume = useCallback(() => { handleRef.current?.resume(); }, []);

  async function publish(changeNote = '', { markupSyntax } = {}) {
    const transport = transportRef.current;
    const handle = handleRef.current;
    // metadata still inside its debounce window goes out first; whenSynced then covers it with the text
    metaRef.current?.queue.flush();
    metaRef.current?.send();
    // a deleted page's draft is published as it stands on the server (recreating the page): the stopped peer cannot sync
    const recreating = status === 'frozen';
    for (let attempt = 0; attempt < MAX_PUBLISH_ATTEMPTS; attempt += 1) {
      const s = sessionRef.current;
      if (!transport || !s) return { ok: false, reason: 'not-live' };
      if (!recreating && !(await handle.whenSynced())) return { ok: false, reason: 'not-live' };
      const version = handle.getSyncedVersion() ?? state.startVersion ?? 0;
      try {
        const res = await transport.publish({
          sessionId: s.sessionId, participantId: s.participantId, version, changeNote,
          ...(markupSyntax != null ? { markupSyntax } : {}),
        });
        return { ok: true, ...res };
      } catch (err) {
        const reason = err?.body?.reason;
        // the session merged an outside save while publishing, or the head moved: sync again and retry
        if (reason === 'publish-merged-retry' || reason === 'not-synced') continue;
        return { ok: false, status: err?.status, reason, body: err?.body, message: err?.message };
      }
    }
    return { ok: false, reason: 'retries-exhausted' };
  }

  /** The publish dialog's summary, fetched once every local edit (text and flushed metadata) has reached the server. */
  async function draftSummary() {
    const transport = transportRef.current;
    const handle = handleRef.current;
    metaRef.current?.queue.flush();
    metaRef.current?.send();
    if (!transport || !sessionRef.current) return { ok: false, reason: 'not-live' };
    // a frozen draft's stopped peer can never sync: summarise what the server holds
    if (status !== 'frozen' && handle && !(await handle.whenSynced())) return { ok: false, reason: 'not-live' };
    const s = sessionRef.current;
    if (!s) return { ok: false, reason: 'not-live' };
    try {
      const body = await transport.draft({ sessionId: s.sessionId, participantId: s.participantId });
      return { ok: true, summary: summaryOf(body) };
    } catch (err) {
      console.warn('[collab] draft summary failed', err?.status, err?.body?.reason ?? err?.message);
      return { ok: false, status: err?.status, reason: err?.body?.reason };
    }
  }

  async function discard() {
    const s = sessionRef.current;
    if (!transportRef.current || !s) return { ok: false, reason: 'not-live' };
    try {
      await transportRef.current.discard({ sessionId: s.sessionId, participantId: s.participantId });
      return { ok: true };
    } catch (err) {
      return { ok: false, status: err?.status, reason: err?.body?.reason, body: err?.body, message: err?.message };
    }
  }

  /** After a resync (or on demand): leave the stale participant, await that, then join afresh with a new view mount. */
  async function restart() {
    const transport = transportRef.current;
    const handle = handleRef.current;
    const s = sessionRef.current;
    sessionRef.current = null;
    // unsynced text goes to the recovery path first (a peer already in resync has handed it over); the peer then stops
    // accepting local edits for the server, so nothing typed during the awaited leaves can be pushed or lost
    handle?.abandon('restart');
    await handle?.whenLeft();
    if (transport && s && !handle?.hasLeft(s.participantId)) {
      try {
        await transport.leave({ sessionId: s.sessionId, participantId: s.participantId });
      } catch (e) {
        console.warn('[collab] leave before restart failed', e?.status, e?.message);
      }
    }
    // capture again right before the remount: whatever was typed while the leaves were awaited is in the document too
    const fresh = handle?.capture('restart');
    // the recovery payload survives the remount until dismissResync(); with nothing unsynced there is nothing to recover
    // (a null capture, view already destroyed, never replaces a payload onResync already delivered)
    setResync((prev) => {
      // a capture with nothing unsynced (none at all, or an empty one) never replaces a payload already delivered
      if (!fresh?.unsyncedText) return prev?.unsyncedText ? prev : null;
      return { ...fresh, reason: prev?.reason ?? fresh.reason };
    });
    try {
      viewRef.current?.dispatch({ effects: setRemotePresence.of({}) });
    } catch (e) {
      console.warn('[collab] could not clear carets before restart', e);
    }
    handle?.destroy();
    narrowOnJoinRef.current = true;
    setEvents([]);
    setPresence({});
    presenceRef.current = {};
    setState({ key, status: 'joining' });
    setGeneration((g) => g + 1);
  }

  const participants = useMemo(() => [
    ...(self ? [self] : []),
    ...Object.entries(presence).map(([participantId, p]) => ({ participantId, displayName: p.displayName, color: p.color })),
  ], [self, presence]);

  const current = state.key === key ? state : { key, status: 'joining' };
  const evictedReason = evicted?.key === key && current.status === 'forbidden' ? evicted.reason : null;
  const evictedRecovery = evictedReason ? { unsyncedText: evicted.unsyncedText, metaEdits: evicted.metaEdits } : null;
  const renamedTo = renamed?.key === key ? renamed.to : null;
  const carriedEdits = carried?.key === key && current.extensions ? carried.edits : null;
  let status = enabled ? current.status : 'idle';
  // the page was deleted under the session: read-only (pushes answer frozen anyway), Publish recreates it
  if (deleted === key && current.extensions && !['resync', 'idle'].includes(status) && !evictedReason) status = 'frozen';

  const metaSession = current.extensions ? current.metaSession ?? null : null;
  const metaView = useMemo(() => {
    if (!metaSession) return null;
    const out = metaSession.state.view();
    if (focus) {
      if (focus.local === null || focus.local === undefined) delete out[focus.field]; else out[focus.field] = focus.local;
    }
    return out;
    // metaTick: every meta change bumps it (the meta state is mutable)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [metaSession, metaTick, focus]);

  const meta = useMemo(() => {
    if (!metaSession) return null;
    const m = metaSession;
    const bump = () => setMetaTick((t) => t + 1);
    const noteLocal = (field, value) => {
      const f = focusRef.current;
      if (f?.field === field) {
        f.local = value;
        f.dirty = true;
        f.sent.add(jsonOf(value));
        setFocus({ field, local: value });
      }
      setConflicts((c) => { if (!(field in c)) return c; const next = { ...c }; delete next[field]; return next; });
    };
    const edit = (field, value) => {
      const v = isEmptyValue(value) ? null : value;
      noteLocal(field, v);
      m.state.edit(field, v);
      m.queue.edit(field, v);
      bump();
    };
    const add = (field, item) => { noteLocal(field, undefined); m.state.add(field, item); m.send(); };
    const remove = (field, item) => { noteLocal(field, undefined); m.state.remove(field, item); m.send(); };
    return {
      view: () => metaView,
      edit,
      add,
      remove,
      /** One field widget's new value: set fields become add/remove ops, any other field a debounced SET. */
      setField(field, value) {
        const prev = metaView?.[field];
        if (SET_FIELDS.includes(field) && (Array.isArray(value) || isEmptyValue(value))) {
          for (const op of diffMeta({ [field]: prev }, { [field]: isEmptyValue(value) ? [] : value })) {
            if ('add' in op) add(field, op.add); else if ('remove' in op) remove(field, op.remove); else edit(field, op.value);
          }
        } else edit(field, value);
      },
      /** A whole-object edit (Raw YAML, Advanced): only what differs between `prev` and `next` is sent (A14). */
      change(prev, next) {
        const ops = diffMeta(prev ?? {}, next ?? {});
        for (const op of ops) noteLocal(op.field, 'value' in op ? op.value : undefined);
        m.state.replace(prev ?? {}, next ?? {});
        m.send();
      },
      flush() { m.queue.flush(); m.send(); },
      focus(field) {
        if (SET_FIELDS.includes(field)) return; // set ops merge: concurrent edits never need holding back
        const local = metaView?.[field] ?? null;
        focusRef.current = { field, local, dirty: false, sent: new Set([jsonOf(local)]) };
        setFocus({ field, local });
      },
      blur(field) {
        const f = focusRef.current;
        if (!f || f.field !== field) return;
        focusRef.current = null;
        setFocus(null);
        setRemoteChanged((r) => {
          if (!(field in r)) return r;
          const next = { ...r };
          delete next[field];
          return next;
        });
        // §8.2: changed since focus → my value is sent again (last writer wins); untouched → the remote value shows
        if (f.dirty && f.remoteBy) m.state.edit(field, f.local);
        m.queue.flush();
        m.send();
      },
      remoteChanged,
      conflicts,
      /** Field edits a resync carried over that this session does not hold: shown for the user to apply or copy. */
      carried: carriedEdits ?? [],
      applyCarried(e) {
        if ('add' in e) add(e.field, e.add);
        else if ('remove' in e) remove(e.field, e.remove);
        else { edit(e.field, e.value); m.queue.flush(); m.send(); }
        setCarried((c) => {
          if (!c) return c;
          const edits = c.edits.filter((x) => x !== e);
          return edits.length ? { ...c, edits } : null;
        });
      },
      dismissCarried() { setCarried(null); },
      /** The edits the server has not confirmed: for a recovery that has to show them (recreating a deleted page). */
      unconfirmed: () => m.state.unconfirmed(),
      pending: m.queue.hasWaiting() || !!m.handle.core?.hasPendingMeta() || (!m.handle.core && m.state.pending().length > 0)
        || !!carriedEdits?.length,
    };
    // metaTick: `pending` reads ref-held state
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [metaSession, metaView, remoteChanged, conflicts, metaTick, carriedEdits]);

  const presenceNow = useCallback(() => presenceRef.current, []);
  /** Who may still be typing (§16 decision 1): participants whose presence reports edits the server has not confirmed. */
  const unsyncedParticipants = useCallback(() => displayNames(Object.values(presenceRef.current)
    .filter((p) => typeof p?.unconfirmed === 'number' && p.unconfirmed > 0)
    .map((p) => labelOf(p.displayName))), []);
  /** The editor's local state the server does not have: unsynced text and unconfirmed field edits. */
  const captureLocal = useCallback(() => ({
    unsyncedText: handleRef.current?.capture('local')?.unsyncedText ?? '',
    metaEdits: metaRef.current?.state.unconfirmed() ?? [],
  }), []);

  return {
    ...current,
    status,
    participants, self, presence, presenceNow, renamedTo, events, resync, dismissResync, restart, resume, publish, discard,
    draft: current.draft ?? null, published: current.published ?? null, notice: current.notice ?? null,
    oversizeLimit: current.oversizeLimit ?? null, draftSummary, unsyncedParticipants,
    meta, evictedReason, evictedRecovery, captureLocal, externalChange: externalChange?.key === key ? externalChange : null,
  };
}
