// Post-build guard, run by `npm run build` (and so by the WAR build) after `vite build`:
//   1. dist/index.html must not modulepreload any chunk holding diagram-library code. Mermaid (~1.2 MB gzip
//      in all) is loaded only by the rendered-fence pass, for a page that has a diagram; a preload would make
//      every page download it. Checked three ways: chunk file name, chunk content, and — via the module map the
//      build's chunk-modules plugin records (vite.config.js) — whether the chunk holds any MERMAID_FAMILY module.
//      Mermaid's minified chunks mostly never spell "mermaid", so the module map is the check that matters.
//   2. No chunk may exceed the 700 kB budget unless it is a known monolithic lazy library (see the
//      chunkSizeWarningLimit comment in vite.config.js) — so raising Vite's warning limit for those never hides
//      unexpected bloat elsewhere.
// Usage: node scripts/check-dist.js [distDir [chunkModulesJson]]   (defaults: dist, the build's module map).
// Exit 1 with one line per problem.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CHUNK_MODULES_FILE, MERMAID_FAMILY, UNDER } from './bundleFamilies.js';

/** Code from these libraries must never be modulepreloaded. */
export const FORBIDDEN_PRELOAD = /mermaid/i;
const FAMILY_MODULE = new RegExp(UNDER(MERMAID_FAMILY));
export const CHUNK_BUDGET_BYTES = 700 * 1000;
/** Monolithic third-party chunks allowed over the budget (lazy, loaded only by the feature that needs them). */
export const OVER_BUDGET_ALLOWED = [/^elk-[\w-]+\.js$/];

/** modulepreload hrefs in an index.html, in order. */
export function modulePreloads(html) {
  return [...html.matchAll(/<link\b[^>]*\brel=["']?modulepreload["']?[^>]*>/gi)]
    .map((m) => /\bhref=["']([^"']+)["']/i.exec(m[0])?.[1])
    .filter(Boolean);
}

/** The problems found in a built dist directory (empty when it is clean). */
export function checkDist(distDir, chunkModulesFile) {
  const problems = [];
  const indexPath = join(distDir, 'index.html');
  if (!existsSync(indexPath)) return [`${indexPath} not found — run vite build first`];
  if (!existsSync(chunkModulesFile)) return [`${chunkModulesFile} not found — run npm run build (vite.config.js writes it)`];
  const chunkModules = JSON.parse(readFileSync(chunkModulesFile, 'utf8'));
  for (const href of modulePreloads(readFileSync(indexPath, 'utf8'))) {
    const file = join(distDir, href.replace(/^\//, '').split('?')[0]);
    if (FORBIDDEN_PRELOAD.test(basename(file))) {
      problems.push(`index.html modulepreloads ${href}, a diagram-library chunk (by name)`);
    } else if (!existsSync(file)) {
      problems.push(`index.html modulepreloads ${href}, which is not in ${distDir}`);
    } else if (FORBIDDEN_PRELOAD.test(readFileSync(file, 'utf8'))) {
      problems.push(`index.html modulepreloads ${href}, which contains diagram-library code`);
    } else {
      const rel = href.replace(/^\//, '').split('?')[0];
      const modules = chunkModules[rel];
      const family = modules?.find((id) => FAMILY_MODULE.test(id));
      if (!modules) problems.push(`index.html modulepreloads ${href}, which the module map does not know (stale map?)`);
      else if (family) problems.push(`index.html modulepreloads ${href}, which holds Mermaid-family module ${family}`);
    }
  }
  const assets = join(distDir, 'assets');
  if (existsSync(assets)) {
    for (const name of readdirSync(assets)) {
      if (!name.endsWith('.js')) continue;
      const size = statSync(join(assets, name)).size;
      if (size > CHUNK_BUDGET_BYTES && !OVER_BUDGET_ALLOWED.some((re) => re.test(name))) {
        problems.push(`assets/${name} is ${(size / 1000).toFixed(2)} kB, over the ${CHUNK_BUDGET_BYTES / 1000} kB chunk budget`);
      }
    }
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const dir = resolve(process.argv[2] ?? 'dist');
  const problems = checkDist(dir, resolve(process.argv[3] ?? CHUNK_MODULES_FILE));
  if (problems.length) {
    problems.forEach((p) => console.error(`check-dist: ${p}`));
    process.exit(1);
  }
}
