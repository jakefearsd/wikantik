// Dev validator (not a test file; not run by `node --test`).
//   node marketing/test/pagecheck.mjs <subdir-relative-to-marketing>
// Validates per-file SEO invariants WITHOUT the sitemap-membership check, so a
// cluster's pages can be validated while it is being built and before the
// central sitemap.xml is assembled. The authoritative gate remains the full
// `node --test marketing/test/*.test.mjs` suite run after assembly.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname; // marketing/
const SITE_ORIGIN = "https://www.wikantik.com";
const allowed = new Set(JSON.parse(readFileSync(join(ROOT, "verified-wiki-links.json"), "utf8")).allowed);

function htmlFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...htmlFiles(full));
    else if (name.endsWith(".html")) out.push(full);
  }
  return out;
}
function canonicalForPath(rel) {
  if (rel === "index.html") return `${SITE_ORIGIN}/`;
  if (rel.endsWith("/index.html")) return `${SITE_ORIGIN}/${rel.slice(0, -"index.html".length)}`;
  return `${SITE_ORIGIN}/${rel}`;
}

const sub = process.argv[2];
if (!sub) { console.error("usage: node test/pagecheck.mjs <subdir>"); process.exit(2); }

const files = htmlFiles(join(ROOT, sub));
let failures = 0;
for (const file of files) {
  const rel = relative(ROOT, file);
  const html = readFileSync(file, "utf8");
  const errs = [];

  const h1 = (html.match(/<h1[\s>]/gi) || []).length;
  if (h1 !== 1) errs.push(`h1 count ${h1} (want 1)`);
  const title = html.match(/<title>([^<]+)<\/title>/i);
  if (!(title && title[1].trim())) errs.push("missing <title>");
  const desc = html.match(/<meta\s+name=["']description["']\s+content=["']([^"']+)["']/i);
  if (!(desc && desc[1].trim())) errs.push("missing meta description");
  const canon = html.match(/<link\s+rel=["']canonical["']\s+href=["']([^"']+)["']/i);
  if (!canon) errs.push("missing canonical");
  else if (canon[1] !== canonicalForPath(rel)) errs.push(`canonical ${canon[1]} != expected ${canonicalForPath(rel)}`);
  if (!/property=["']og:title["']/i.test(html)) errs.push("missing og:title");
  if (!/property=["']og:description["']/i.test(html)) errs.push("missing og:description");
  if (!/class="nav"/i.test(html)) errs.push("missing nav");
  if (!/<footer/i.test(html)) errs.push("missing footer");

  const ld = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/gi)];
  if (ld.length < 1) errs.push("missing JSON-LD");
  for (const m of ld) { try { JSON.parse(m[1]); } catch { errs.push("invalid JSON-LD block"); } }

  for (const m of html.matchAll(/href=["'](https:\/\/wiki\.wikantik\.com[^"']*)["']/g)) {
    const l = m[1];
    const ok = allowed.has(l) || l === "https://wiki.wikantik.com/" ||
               l.startsWith("https://wiki.wikantik.com/privacy-policy") ||
               l.startsWith("https://wiki.wikantik.com/terms-of-service");
    if (!ok) errs.push(`unverified wiki link: ${l}`);
  }

  if (errs.length) { failures++; console.log(`FAIL ${rel}\n  - ${errs.join("\n  - ")}`); }
  else console.log(`ok   ${rel}`);
}
console.log(`\n${files.length} files checked, ${failures} failing`);
process.exit(failures ? 1 : 0);
