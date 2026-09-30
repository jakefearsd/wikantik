/** decodeURIComponent that returns the raw string (and warns with context) when the escape is malformed. */
export function safeDecode(s) {
  try {
    return decodeURIComponent(s);
  } catch (err) {
    console.warn('[link-targets] undecodable link target', s, err?.message || err);
    return s;
  }
}
