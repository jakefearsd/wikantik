/**
 * React hook owning one collaborative-editing session for a page: joins on mount, leaves on unmount and on `pagehide`
 * (via `navigator.sendBeacon`), and exposes the pieces the editor needs.
 *
 * Returns `{ status, participants, presence, events, resync, dismissResync: () => setResync(null), restart, publish, discard, docAtJoin, startVersion, clientID,
 * sessionId, metadata, draft, basePageVersion, extensions }`. `extensions` is the `collab()` + `collabPeer()` pair to
 * mount in a CodeMirror view initialised from `docAtJoin` (A4: the editor is uncontrolled in collab mode; A10: on
 * `status === 'resync'` the host shows `resync.unsyncedText` in a recovery panel and calls `restart()`: it awaits the leave of
 * the stale participant (and of a rejoin ghost, both done by the peer) and only then joins afresh, so the old participant is
 * gone from the server before the new one exists). `events` keeps the last 200.
 *
 * `status`: idle | joining | live | reconnecting | resync | frozen | auth-required, or a refused join: disabled |
 * unavailable (derived page) | full | forbidden | error.
 */
import { useEffect, useRef, useState } from 'react';
import { collab } from '@codemirror/collab';
import { createCollabTransport } from './collabTransport.js';
import { collabPeer, createPeerHandle } from './collabPeer.js';

const MAX_PUBLISH_ATTEMPTS = 3;
const MAX_EVENTS = 200;

function joinFailureStatus(err) {
  const reason = err?.body?.reason;
  if (reason === 'collab-disabled') return 'disabled';
  if (reason === 'derived-page') return 'unavailable';
  if (reason === 'session-full') return 'full';
  if (err?.status === 401 || err?.status === 403) return 'forbidden';
  return 'error';
}

export function useCollabSession(pageName, { transport: transportOverride, initialText, initialMeta, enabled = true } = {}) {
  const key = `${pageName}`;
  const [state, setState] = useState({ key, status: 'joining' });
  const [participants, setParticipants] = useState([]);
  const [presence, setPresence] = useState({});
  const [events, setEvents] = useState([]);
  const [resync, setResync] = useState(null);
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
    const transport = transportOverride ?? createCollabTransport(pageName);
    transportRef.current = transport;
    const handle = createPeerHandle();
    handleRef.current = handle;
    const patch = (p) => setState((s) => (s.key === key ? { ...s, ...p } : s));

    transport.join({ initialText: initialRef.current.initialText ?? null, initialMeta: initialRef.current.initialMeta ?? null })
      .then((j) => {
        if (cancelled) {
          transport.leave({ sessionId: j.sessionId, participantId: j.participantId })
            .catch((e) => console.warn('[collab] leave after a cancelled join failed', e));
          return;
        }
        sessionRef.current = { sessionId: j.sessionId, clientID: j.clientID, participantId: j.participantId };
        const extensions = [
          collab({ startVersion: j.version, clientID: j.clientID }),
          collabPeer({
            transport,
            handle,
            session: { sessionId: j.sessionId, clientID: j.clientID, participantId: j.participantId },
            onStatus: (status) => patch({ status }),
            onResync: (payload) => { setResync(payload); },
            onPresence: (map) => setPresence(map),
            onEvents: (batch) => {
              setEvents((prev) => [...prev, ...batch].slice(-MAX_EVENTS));
              setParticipants((prev) => {
                let next = prev;
                for (const e of batch) {
                  if (e.type === 'participant-joined' && !next.some((p) => p.participantId === e.participant.participantId)) {
                    next = [...next, e.participant];
                  } else if (e.type === 'participant-left') {
                    next = next.filter((p) => p.participantId !== e.participantId);
                  }
                }
                return next;
              });
            },
            onSessionChange: (rj) => {
              sessionRef.current = { sessionId: rj.sessionId, clientID: rj.clientID, participantId: rj.participantId };
              setParticipants(rj.participants ?? []);
            },
          }),
        ];
        setParticipants(j.participants ?? []);
        patch({
          status: 'live', docAtJoin: j.doc, startVersion: j.version, clientID: j.clientID, sessionId: j.sessionId,
          metadata: j.metadata ?? {}, metaSeq: j.metaSeq ?? 0, basePageVersion: j.basePageVersion ?? null,
          draft: j.draft ?? null, extensions,
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
      handle.destroy();
      if (s) transport.leave({ sessionId: s.sessionId, participantId: s.participantId }).catch((e) => console.warn('[collab] leave failed', e));
    };
  }, [pageName, enabled, transportOverride, key, generation]);

  async function publish(changeNote = '') {
    const transport = transportRef.current;
    const handle = handleRef.current;
    for (let attempt = 0; attempt < MAX_PUBLISH_ATTEMPTS; attempt += 1) {
      const s = sessionRef.current;
      if (!transport || !s) return { ok: false, reason: 'not-live' };
      if (!(await handle.whenSynced())) return { ok: false, reason: 'not-live' };
      const version = handle.getSyncedVersion() ?? state.startVersion ?? 0;
      try {
        const res = await transport.publish({ sessionId: s.sessionId, participantId: s.participantId, version, changeNote });
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
    setResync((prev) => (fresh?.unsyncedText ? { ...fresh, reason: prev?.reason ?? fresh.reason } : null));
    handle?.destroy();
    setEvents([]);
    setState({ key, status: 'joining' });
    setGeneration((g) => g + 1);
  }

  const current = state.key === key ? state : { key, status: 'joining' };
  return {
    ...current,
    status: enabled ? current.status : 'idle',
    participants, presence, events, resync, dismissResync: () => setResync(null), restart, publish, discard,
  };
}
