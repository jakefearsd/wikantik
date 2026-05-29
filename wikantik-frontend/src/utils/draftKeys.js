const BASE = 'wikantik.draft';

export function draftPrefix(login) {
  return `${BASE}.${login}.`;
}

export function draftKey(login, pageId) {
  return `${draftPrefix(login)}${pageId}`;
}

export function parseDraftKey(login, key) {
  const prefix = draftPrefix(login);
  return key.startsWith(prefix) ? key.slice(prefix.length) : null;
}
