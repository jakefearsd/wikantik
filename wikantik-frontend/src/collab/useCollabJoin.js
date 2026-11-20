import { useState, useEffect, useCallback } from 'react';
import { stripFrontmatter } from '../utils/frontmatterUtils';

/** Offline (localStorage) drafts already offered in this browser tab: each stored draft is offered once (§8.2). */
const offeredOfflineDrafts = new Set();
const offlineDraftKey = (login, page, savedAt) => `${login}\u0000${page}\u0000${savedAt}`;

/**
 * What a collab (re)join hands the editor (docs/MultiEdit.MD §8.2, §8.3): on each join the editor remounts from the
 * joined document, and on the first join of a page the hook works out what to offer the user.
 *
 * The join is adjusted during render (React's "adjust state when a prop changes" idiom), never in an effect: the
 * caller must call this hook at the point of its render where the joined body, unsynced flag and synced version may
 * change, since the setters it is handed are called there. Arguments:
 *   collab            the useCollabSession result (extensions, docAtJoin, startVersion, draft);
 *   collabMode        collaborative mode is on (collabView's `mode`);
 *   collabSeed        what a new session started from: { name, initialText, restoreFrom } or null;
 *   name, login       the page being edited and the signed-in login (keys the offline draft);
 *   draft             the offline (localStorage) draft of this page, or null;
 *   setBody, setUnsynced, setSyncedVersion   the editor state a join resets.
 * Returns:
 *   restoreOffer        { text, from }: a restored version that differs from a session others were already editing;
 *   offlineDraftOffer   { text, savedAt, key }: an offline draft differing from the joined text, offered once per draft;
 *   draftBannerOpen     the first join reported unpublished changes, until dismissed or published;
 *   collabDraft         the session's draft state in collab mode, else null;
 *   dismissRestoreOffer, dismissOfflineDraftOffer, dismissDraftBanner   clear the matching offer or banner.
 */
export function useCollabJoin({
  collab, collabMode, collabSeed, name, login, draft, setBody, setUnsynced, setSyncedVersion,
}) {
  const [restoreOffer, setRestoreOffer] = useState(null);
  const [offlineDraftOffer, setOfflineDraftOffer] = useState(null);
  const [joinedExtensions, setJoinedExtensions] = useState(null);
  // The draft banner (D3, §8.3): shown on open when the join reported unpublished changes, until dismissed or published.
  const [draftBannerOpen, setDraftBannerOpen] = useState(false);
  if (collabMode && collab.extensions && collab.extensions !== joinedExtensions) {
    setJoinedExtensions(collab.extensions);
    const joinedDoc = String(collab.docAtJoin ?? '');
    setBody(joinedDoc);
    setUnsynced(false);
    setSyncedVersion(collab.startVersion ?? null);
    if (!joinedExtensions) setDraftBannerOpen(!!collab.draft?.unpublished);
    // A restore into a session that already existed (others are editing): offer it instead of clobbering their work.
    const seedText = collabSeed?.name === name && collabSeed.restoreFrom ? String(collabSeed.initialText ?? '').replace(/\r\n?/g, '\n') : null;
    // (the server may end a session's text in a newline (A9): that alone does not make it someone else's session)
    const differs = seedText != null && seedText.replace(/\n+$/, '') !== joinedDoc.replace(/\n+$/, '');
    setRestoreOffer(differs && !joinedExtensions ? { text: seedText, from: collabSeed.restoreFrom } : null);
    // An offline (localStorage) draft of this page, offered once per stored draft on entering collab mode (§8.2).
    const offlineKey = draft?.content ? offlineDraftKey(login, name, draft.savedAt) : null;
    if (!joinedExtensions && offlineKey && !offeredOfflineDrafts.has(offlineKey)) {
      const offline = stripFrontmatter(draft.content).replace(/\r\n?/g, '\n');
      setOfflineDraftOffer(offline !== joinedDoc ? { text: offline, savedAt: draft.savedAt, key: offlineKey } : null);
    }
  }
  // remembered once shown (not during render: a discarded render must not use up the offer)
  useEffect(() => {
    if (offlineDraftOffer?.key) offeredOfflineDrafts.add(offlineDraftOffer.key);
  }, [offlineDraftOffer]);
  const collabDraft = collabMode ? collab.draft : null;
  if (draftBannerOpen && collabDraft && !collabDraft.unpublished) setDraftBannerOpen(false);

  const dismissRestoreOffer = useCallback(() => setRestoreOffer(null), []);
  const dismissOfflineDraftOffer = useCallback(() => setOfflineDraftOffer(null), []);
  const dismissDraftBanner = useCallback(() => setDraftBannerOpen(false), []);
  return {
    restoreOffer, offlineDraftOffer, draftBannerOpen, collabDraft,
    dismissRestoreOffer, dismissOfflineDraftOffer, dismissDraftBanner,
  };
}
