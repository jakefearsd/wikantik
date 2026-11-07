/**
 * HTTP transport for the collaborative-editing protocol (docs/MultiEdit.MD 6.2; wire shapes are pinned by
 * `__fixtures__/collab-protocol.json`). Every method resolves the response body, or rejects with an Error that carries
 * `status` and the parsed `body` (so callers branch on `err.body.reason`); a network failure rejects without `status`.
 * Built on api/client.js, so credentials, CSRF/Origin and the context path behave exactly as for the rest of the SPA.
 *
 * The interface (join/push/pull/draft/publish/discard/leave/leaveBeacon) is deliberately transport-agnostic: a WebSocket
 * implementation can drop in behind it (spec D2).
 */
import { apiUrl, collabRequest } from '../api/client.js';

export function createCollabTransport(pageName) {
  let base = `/api/collab/${encodeURIComponent(pageName)}`;
  const post = (action, body) => collabRequest(`${base}/${action}`, { method: 'POST', body: JSON.stringify(body ?? {}) });

  return {
    /** The page was renamed under the live session: later calls use the new name (the session id still identifies it). */
    retarget: (newName) => { base = `/api/collab/${encodeURIComponent(newName)}`; },
    /** `{priorClientId, initialText, initialMeta}`: the server reissues priorClientId when it still knows it (7.2). */
    join: ({ priorClientId = null, initialText = null, initialMeta = null } = {}) =>
      post('join', { priorClientId, initialText, initialMeta }),
    /** `{sessionId, participantId, version, updates, metaOps, selection?}`; a push with no updates is a presence-only push (A8). */
    push: (body) => post('updates', body),
    /** Long-poll: `{sessionId, participantId, since, metaSince, presenceSince, eventsSince, signal}`. */
    pull: ({ signal, sessionId, participantId, since, metaSince = 0, presenceSince = 0, eventsSince = 0 }) => {
      const qs = new URLSearchParams({
        sessionId, participantId, since: String(since), metaSince: String(metaSince),
        presenceSince: String(presenceSince), eventsSince: String(eventsSince),
      });
      return collabRequest(`${base}/updates?${qs}`, { signal });
    },
    /** The draft summary for the publish dialog: `{basePageVersion, version, unpublished, contributors, lastEditAt, diff, metaChanges}`. */
    draft: ({ sessionId, participantId }) => {
      const qs = new URLSearchParams({ sessionId, participantId });
      return collabRequest(`${base}/draft?${qs}`);
    },
    /** `{sessionId, participantId, version, changeNote, markupSyntax?}`: the body goes out as given. */
    publish: (body) => post('publish', body),
    discard: (body) => post('discard', body),
    leave: (body) => post('leave', body),
    /** Best-effort leave for `pagehide`; true when the browser queued it. */
    leaveBeacon: (body) => {
      if (typeof navigator === 'undefined' || typeof navigator.sendBeacon !== 'function') return false;
      return navigator.sendBeacon(apiUrl(`${base}/leave`), new Blob([JSON.stringify(body ?? {})], { type: 'application/json' }));
    },
  };
}
