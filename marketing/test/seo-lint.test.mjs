import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { ROOT, htmlFiles, relPath, canonicalForPath, read } from "./_util.mjs";

const sitemap = read(join(ROOT, "sitemap.xml"));

for (const file of htmlFiles()) {
  const rel = relPath(file);
  const html = read(file);
  test(`SEO invariants: ${rel}`, () => {
    const h1 = html.match(/<h1[\s>]/gi) || [];
    assert.equal(h1.length, 1, `expected exactly one <h1>, found ${h1.length}`);

    const title = html.match(/<title>([^<]+)<\/title>/i);
    assert.ok(title && title[1].trim(), "missing <title>");

    const desc = html.match(/<meta\s+name=["']description["']\s+content=["']([^"']+)["']/i);
    assert.ok(desc && desc[1].trim(), "missing meta description");

    const canon = html.match(/<link\s+rel=["']canonical["']\s+href=["']([^"']+)["']/i);
    assert.ok(canon, "missing canonical");
    assert.equal(canon[1], canonicalForPath(rel), "canonical mismatch");

    assert.match(html, /property=["']og:title["']/i, "missing og:title");
    assert.match(html, /property=["']og:description["']/i, "missing og:description");
    assert.match(html, /class="nav"/i, "missing nav");
    assert.match(html, /<footer/i, "missing footer");

    const ld = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/gi)];
    assert.ok(ld.length >= 1, "missing JSON-LD");
    for (const m of ld) JSON.parse(m[1]); // throws on invalid JSON

    assert.ok(sitemap.includes(canonicalForPath(rel)), `not in sitemap.xml: ${rel}`);
  });
}
