const MAC_SYMBOLS = { Mod: '⌘', Ctrl: '⌃', Alt: '⌥', Shift: '⇧' };
const PC_NAMES = { Mod: 'Ctrl', Ctrl: 'Ctrl', Alt: 'Alt', Shift: 'Shift' };

/** True on macOS / iOS. Prefers User-Agent Client Hints; falls back to {@code navigator.platform}. */
export function isMacPlatform(nav = typeof navigator !== 'undefined' ? navigator : undefined) {
  const platform = nav?.userAgentData?.platform || nav?.platform || '';
  return /mac|iphone|ipad|ipod/i.test(platform);
}

/**
 * Turns a CodeMirror-style binding into a readable hint: {@code Mod-Shift-p} → "Ctrl+Shift+P" on
 * Windows/Linux and "⌘⇧P" on a Mac. Single letters are upper-cased; other keys ("[", "Enter") pass through.
 */
export function formatKeys(keys, { mac = isMacPlatform() } = {}) {
  if (!keys) return '';
  const parts = keys.split(/-(?!$)/).map((part) => {
    const names = mac ? MAC_SYMBOLS : PC_NAMES;
    if (names[part]) return names[part];
    return part.length === 1 ? part.toUpperCase() : part;
  });
  return parts.join(mac ? '' : '+');
}
