import { splitMentions } from '../utils/mentionTokens';

/**
 * Renders a comment body. A mention token (`@[Display Name](user:<uid>)`) becomes a
 * chip showing the member's CURRENT display name from `mentionNames` (the
 * comment-threads response carries `mentions: {uid: displayName}`).
 *
 * The name inside the token is only a snapshot, and anyone can type a token with
 * any name and any uid, so it is never shown as a chip: a uid the server did not
 * resolve renders as plain `@snapshot` text. A legacy `@login` is plain text too;
 * there is no profile page to link to.
 */
export default function CommentBody({ body, mentionNames = {} }) {
  if (!body) return null;
  const parts = splitMentions(body).map((segment, i) => {
    if (segment.type === 'text') return segment.text;
    const currentName = mentionNames[segment.uid];
    if (!currentName) return `@${segment.snapshot}`;
    return (
      <span key={`m-${i}-${segment.uid}`} className="comment-mention-chip" data-user-id={segment.uid}>
        @{currentName}
      </span>
    );
  });
  return <span className="comment-body">{parts}</span>;
}
