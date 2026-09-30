const MAX_LENGTH = 40;
const VALID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_-]*\.[a-zA-Z0-9]+$/;

export function isValidAttachmentName(name) {
  if (!name || name.length === 0 || name.length > MAX_LENGTH) return false;
  if (!VALID_PATTERN.test(name)) return false;
  if (name.indexOf('.') !== name.lastIndexOf('.')) return false;
  const dotIndex = name.indexOf('.');
  const beforeDot = name[dotIndex - 1];
  return beforeDot !== '-' && beforeDot !== '_';
}

export function getExtension(name) {
  if (!name) return '';
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.substring(dot + 1).toLowerCase() : '';
}

export function extensionsMatch(originalName, desiredName) {
  return getExtension(originalName) === getExtension(desiredName);
}

const MIME_EXTENSIONS = {
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif',
  'image/webp': 'webp', 'image/svg+xml': 'svg', 'image/bmp': 'bmp',
};
const pad2 = (n) => String(n).padStart(2, '0');

/** Name for a pasted clipboard image: pasted-YYYYMMDD-HHMMSS.<ext> (local time; png when the MIME type is unknown). */
export function pastedImageName(mimeType, now = new Date()) {
  const ext = MIME_EXTENSIONS[mimeType] || 'png';
  const date = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`;
  const time = `${pad2(now.getHours())}${pad2(now.getMinutes())}${pad2(now.getSeconds())}`;
  return `pasted-${date}-${time}.${ext}`;
}

function cleanStem(stem) {
  return stem
    .replace(/[\s.]+/g, '-')
    .replace(/[^a-zA-Z0-9_-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^[-_]+|[-_]+$/g, '');
}

/**
 * Normalise a file name to the server's attachment-name rules (isValidAttachmentName):
 * spaces and inner periods become '-', other disallowed characters are dropped, the stem is trimmed
 * so the whole name is ≤ 40 chars. Returns null when there is no usable extension.
 */
export function normalizeAttachmentName(original) {
  const raw = String(original || '');
  const dot = raw.lastIndexOf('.');
  if (dot <= 0 || dot === raw.length - 1) return null;
  const ext = raw.slice(dot + 1).replace(/[^a-zA-Z0-9]/g, '');
  if (!ext) return null;
  const room = MAX_LENGTH - 1 - ext.length;
  if (room < 1) return null;
  const stem = cleanStem(cleanStem(raw.slice(0, dot)).slice(0, room)) || 'file';
  const name = `${stem}.${ext}`;
  return isValidAttachmentName(name) ? name : null;
}

/** `name`, or its stem suffixed -2, -3, … until it does not collide (case-insensitively) with `existing`. */
export function uniqueAttachmentName(name, existing = []) {
  const taken = new Set(existing.map((n) => n.toLowerCase()));
  if (!taken.has(name.toLowerCase())) return name;
  const dot = name.lastIndexOf('.');
  const stem = name.slice(0, dot);
  const ext = name.slice(dot + 1);
  for (let n = 2; ; n++) {
    const suffix = `-${n}`;
    const room = MAX_LENGTH - 1 - ext.length - suffix.length;
    const candidate = `${cleanStem(stem.slice(0, room))}${suffix}.${ext}`;
    if (!taken.has(candidate.toLowerCase())) return candidate;
  }
}

/** The markup an attachment-row drag produces: ![stem](name) for images, [stem](name) otherwise. */
export function attachmentMarkup(name, isImage) {
  const stem = name.slice(0, name.lastIndexOf('.'));
  return isImage ? `![${stem}](${name})` : `[${stem}](${name})`;
}
