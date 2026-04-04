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
