import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

export const ROOT = new URL("..", import.meta.url).pathname; // marketing/
export const SITE_ORIGIN = "https://www.wikantik.com";
const EXCLUDE_DIRS = new Set(["test", "templates", "form-backend", "assets", "node_modules"]);

export function htmlFiles(dir = ROOT) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (!EXCLUDE_DIRS.has(name)) out.push(...htmlFiles(full));
    } else if (name.endsWith(".html")) {
      out.push(full);
    }
  }
  return out;
}

export function relPath(file) { return relative(ROOT, file); }

export function canonicalForPath(rel) {
  if (rel === "index.html") return `${SITE_ORIGIN}/`;
  if (rel.endsWith("/index.html")) return `${SITE_ORIGIN}/${rel.slice(0, -"index.html".length)}`;
  return `${SITE_ORIGIN}/${rel}`;
}

export function read(file) { return readFileSync(file, "utf8"); }
