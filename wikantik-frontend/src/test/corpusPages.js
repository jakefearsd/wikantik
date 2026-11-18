/**
 * The repo corpus (docs/wikantik-pages) as the editor would open it: frontmatter stripped, and the legacy
 * JSPWiki-syntax pages set aside, because the editor forces Source mode for them. Shared by the live-preview sweeps.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { stripFrontmatter } from '../utils/frontmatterUtils';

const PAGES_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../docs/wikantik-pages');

// Port of WikiToMarkdownConverter.isLikelyWikiSyntax(String) in
// wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java (the server sets markupSyntax
// 'likely-wiki' from it; the editor then forces source mode). Keep in step with the Java patterns and
// HEURISTIC_THRESHOLD = 3; the 'drift guard' test in corpus.test.js mirrors cases from WikiToMarkdownConverterTest.
const WIKI_SIGNALS = [
  [/^!{1,3}\s/m, 2], [/''[^']+?''/, 2], [/(?<!\[)\[(?!\[)([^\]|]+)\|([^\]]+)\](?!\])/, 2], [/\[\{[^}]+\}\](?!\()/, 2], [/\{\{\{/, 1], [/__[^_]+?__/, 1],
];
export function isLikelyWikiSyntax(body) {
  if (!body || !body.trim()) return false;
  return WIKI_SIGNALS.reduce((score, [re, pts]) => score + (re.test(body) ? pts : 0), 0) >= 3;
}

/** Every `.md` file name of the corpus, sorted. */
export const all = readdirSync(PAGES_DIR).filter((f) => f.endsWith('.md')).sort();
/** The pages the live preview can show: `{ name, body }`. */
export const pages = [];
let skipped = 0;
for (const file of all) {
  const body = stripFrontmatter(readFileSync(path.join(PAGES_DIR, file), 'utf8'));
  if (isLikelyWikiSyntax(body)) skipped += 1; else pages.push({ name: file.replace(/\.md$/, ''), body });
}
/** How many pages were set aside as legacy syntax. */
export const legacy = skipped;
