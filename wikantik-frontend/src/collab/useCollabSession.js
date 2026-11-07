/**
 * React hook owning one collaborative-editing session for a page: joins on mount, leaves on unmount and on `pagehide`
 * (via `navigator.sendBeacon`), and exposes the pieces the editor needs.
 *
 * Returns `{ status, participants, presence, events, resync, publish, discard, docAtJoin, startVersion, clientID,
 * sessionId, metadata, draft, basePageVersion, extensions }`. `extensions` is the `collab()` + `collabPeer()` pair to
 * mount in a CodeMirror view initialised from `docAtJoin` (A4: the editor is uncontrolled in collab mode; A10: on
 * `status === 'resync'` the host shows `resync.unsyncedText` in a recovery panel and remounts with a fresh join).
 *
 * `status`: idle | joining | live | reconnecting | resync | frozen | auth-required, or a refused join: disabled |
 * unavailable (derived page) | full | forbidden | error.
 */
import { useEffect, useRef, useState } from 'react';
import { collab } from '@codemirror/collab';
import { createCollabTransport } from './collabTransport.js';
import { collabPeer, createPeerHandle } from './collabPeer.js';

const MAX_PUBLISH_ATTEMPTS = 3;

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
          transport.leave({ sessionId: j.sessionId })
            .catch((e) => console.warn('[collab] leave after a cancelled join failed', e));
          return;
        }
        sessionRef.current = { sessionId: j.sessionId, clientID: j.clientID };
        const extensions = [
          collab({ startVersion: j.version, clientID: j.clientID }),
          collabPeer({
            transport,
            handle,
            session: { sessionId: j.sessionId, clientID: j.clientID, participantId: j.participantId },
            onStatus: (status) => patch({ status }),
            onResync: (payload) => setResync(payload),
            onPresence: (map) => setPresence(map),
            onEvents: (batch) => {
              setEvents((prev) => [...prev, ...batch]);
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
              sessionRef.current = { sessionId: rj.sessionId, clientID: rj.clientID };
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
      if (sessionRef.current) transport.leaveBeacon({ sessionId: sessionRef.current.sessionId });
    };
    window.addEventListener('pagehide', onPageHide);

    return () => {
      cancelled = true;
      window.removeEventListener('pagehide', onPageHide);
      const s = sessionRef.current;
      sessionRef.current = null;
      if (s) transport.leave({ sessionId: s.sessionId }).catch((e) => console.warn('[collab] leave failed', e));
    };
  }, [pageName, enabled, transportOverride, key]);

  async function publish(changeNote = '') {
    const transport = transportRef.current;
    const handle = handleRef.current;
    for (let attempt = 0; attempt < MAX_PUBLISH_ATTEMPTS; attempt += 1) {
      const s = sessionRef.current;
      if (!transport || !s) return { ok: false, reason: 'not-live' };
      if (!(await handle.whenSynced())) return { ok: false, reason: 'not-live' };
      const version = handle.getSyncedVersion() ?? state.startVersion ?? 0;
      try {
        const res = await transport.publish({ sessionId: sessionRef.current?.sessionId ?? s.sessionId, version, changeNote });
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
    await transportRef.current.discard({ sessionId: s.sessionId });
    return { ok: true };
  }

  const current = state.key === key ? state : { key, status: 'joining' };
  return {
    ...current,
    status: enabled ? current.status : 'idle',
    participants, presence, events, resync, publish, discard,
  };
}
