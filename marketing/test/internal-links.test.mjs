import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { ROOT, htmlFiles, relPath, read } from "./_util.mjs";

// Every root-absolute internal link must resolve to a file in the bundle, so deleting a
// page can't leave dangling nav/footer/related links behind.
function resolves(path) {
  const clean = path.split(/[?#]/)[0];
  const target = join(ROOT, clean);
  if (clean.endsWith("/")) return existsSync(join(target, "index.html"));
  return existsSync(target) && statSync(target).isFile();
}

for (const file of [...htmlFiles(), join(ROOT, "templates", "page.template.html")]) {
  const rel = relPath(file);
  const html = read(file);
  test(`internal links resolve: ${rel}`, () => {
    const links = [...html.matchAll(/(?:href|src)=["'](\/(?!\/)[^"']*)["']/g)].map((m) => m[1]);
    const broken = links.filter((l) => !resolves(l));
    assert.deepEqual(broken, [], `broken internal links in ${rel}`);
  });
}
