import React from 'react';

// Same regex used by the server-side MentionExtractor: requires a non-word
// char (or start-of-string) before the @, captures the login.
const TOKEN_RE = /(?:^|\W)@([A-Za-z0-9._-]+)/g;

export default function CommentBody({ body }) {
  if (!body) return null;
  const parts = [];
  let lastIndex = 0;
  let match;
  TOKEN_RE.lastIndex = 0;
  while ((match = TOKEN_RE.exec(body)) !== null) {
    const login = match[1];
    // The @ position inside the match: if the match starts at 0 and the body
    // starts with @, the @ is at match.index; otherwise the leading char is
    // the non-word char (e.g. ' '), so the @ is at match.index + 1.
    const atIndex = body.charAt(match.index) === '@' ? match.index : match.index + 1;
    // Append plain text before this mention.
    if (atIndex > lastIndex) parts.push(body.substring(lastIndex, atIndex));
    parts.push(
      <a
        key={`m-${parts.length}-${login}`}
        href={`/wiki/Users/${encodeURIComponent(login)}`}
        className="comment-mention-chip"
      >
        @{login}
      </a>
    );
    lastIndex = atIndex + 1 + login.length;
  }
  if (lastIndex < body.length) parts.push(body.substring(lastIndex));
  return <span className="comment-body">{parts}</span>;
}
