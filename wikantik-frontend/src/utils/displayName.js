// Client-side mirror of the display-name rules in com.wikantik.auth.ProfileNameRules
// (displayNameError). The server is authoritative: it also rejects reserved names, names equal
// to another account's login and names already taken, and its 400/409 message is shown to the
// user as-is. These checks only save a round trip for the rules the browser can evaluate.

export const DISPLAY_NAME_MIN = 2;
export const DISPLAY_NAME_MAX = 64;

// ISO control characters: C0 (U+0000-U+001F) and DEL + C1 (U+007F-U+009F). A loop rather than a
// regex keeps eslint's no-control-regex quiet.
function hasControlCharacter(text) {
  for (const ch of text) {
    const code = ch.codePointAt(0);
    if (code < 0x20 || (code >= 0x7f && code <= 0x9f)) return true;
  }
  return false;
}

/** The first failing rule as a user-facing message, or null when the name is acceptable. */
export function displayNameError(value) {
  const name = (value ?? '').trim();
  if (!name) return 'Display name is required';
  if (name.length < DISPLAY_NAME_MIN) {
    return `Display name must be at least ${DISPLAY_NAME_MIN} characters`;
  }
  if (name.length > DISPLAY_NAME_MAX) {
    return `Display name must be ${DISPLAY_NAME_MAX} characters or fewer`;
  }
  if (hasControlCharacter(name)) return 'Display name cannot contain control characters';
  if (name.includes('@')) return "Display name cannot contain '@'";
  return null;
}

/** True when {@code next} differs from {@code current} once both are trimmed (the server's own test). */
export function displayNameChanged(current, next) {
  return (next ?? '').trim() !== (current ?? '').trim();
}

/**
 * Whether a failed save should put its message under the Display name field rather than in the
 * page banner. A 409 is always a name conflict (the only conflict a profile save reports). A 400
 * is attributed to the name only when the name is the sole thing the request tried to change, so
 * a password-policy 400 is never mislabelled.
 */
export function isDisplayNameRejection(err, nameIsOnlyChange) {
  return err?.status === 409 || (nameIsOnlyChange && err?.status === 400);
}
