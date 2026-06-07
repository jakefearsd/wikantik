import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, SITE_ORIGIN, htmlFiles, relPath, canonicalForPath, read } from "./_util.mjs";

const sitemap = read(join(ROOT, "sitemap.xml"));
const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1].trim());
const pages = htmlFiles().map((f) => canonicalForPath(relPath(f)));

test("every page is in the sitemap", () => {
  for (const p of pages) assert.ok(locs.includes(p), `missing from sitemap: ${p}`);
});
test("every sitemap loc maps to a page", () => {
  for (const l of locs) assert.ok(pages.includes(l), `sitemap loc has no page: ${l}`);
});
test("all locs are absolute www URLs", () => {
  for (const l of locs) assert.ok(l.startsWith(`${SITE_ORIGIN}/`), `non-absolute loc: ${l}`);
});
