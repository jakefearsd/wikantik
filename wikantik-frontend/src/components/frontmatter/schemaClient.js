// schemaClient.js — fetch and cache the server-authoritative frontmatter schema once per session.
import { api } from '../../api/client';

let cached = null;

/** Returns a cached promise of the schema descriptor `{ fields: [...] }`. */
export function getSchema() {
  if (!cached) {
    // Call inside the promise chain so a missing/throwing api method rejects
    // (caught by the caller) rather than throwing synchronously at the call site.
    cached = Promise.resolve()
      .then(() => api.getFrontmatterSchema())
      .catch((e) => {
        cached = null; // allow a retry on a later call
        throw e;
      });
  }
  return cached;
}

/** Test hook: drop the cache so each test fetches fresh. */
export function _resetSchemaCache() {
  cached = null;
}
