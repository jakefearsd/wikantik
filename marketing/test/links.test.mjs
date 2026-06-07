import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, htmlFiles, relPath, read } from "./_util.mjs";

const allowed = new Set(JSON.parse(read(join(ROOT, "verified-wiki-links.json"))).allowed);

for (const file of htmlFiles()) {
  const rel = relPath(file);
  const html = read(file);
  test(`outbound wiki links are verified: ${rel}`, () => {
    const links = [...html.matchAll(/href=["'](https:\/\/wiki\.wikantik\.com[^"']*)["']/g)].map((m) => m[1]);
    for (const l of links) {
      // allow exact match, or product sub-pages we don't deep-link as authority
      const ok = allowed.has(l) || l === "https://wiki.wikantik.com/" ||
                 l.startsWith("https://wiki.wikantik.com/privacy-policy") ||
                 l.startsWith("https://wiki.wikantik.com/terms-of-service");
      assert.ok(ok, `unverified wiki link in ${rel}: ${l} (add to verified-wiki-links.json after confirming 200)`);
    }
  });
}
