// schemaClient.js — fetch and cache the server-authoritative frontmatter schema once per session.
import { api } from '../../api/client';

let cached = null;

/** Returns a cached promise of the schema descriptor `{ fields: [...] }`. */
export function getSchema() {
  if (!cached) {
    cached = Promise.resolve(api.getFrontmatterSchema()).catch((e) => {
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
