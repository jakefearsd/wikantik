import { Autolink, Strikethrough, TaskList, parser as baseParser } from '@lezer/markdown';
import { editorFoldConfig } from '../markdownFold';
import { mathExtension } from './math';
import { highlightExtension } from './highlight';
import { inlineTagExtension } from './inlineTags';
import { wikiLinkExtension } from './wikiLinks';
import { tableExtension } from './tables';

export { opaqueRanges } from './math';

/**
 * GFM (strikethrough, task lists, autolinks) without its `Table`: the wiki's own table (`./tables`) replaces it whole,
 * because `@lezer/markdown`'s table registers an `endLeaf` that cannot be removed and that ends a paragraph above a
 * table, which the reader never does.
 */
export const gfmWithoutTable = [TaskList, Strikethrough, Autolink];

/** The wiki-specific parser extensions layered on commonmark + GFM (grows with each dialect construct). */
export const dialectExtensions = [mathExtension, highlightExtension, inlineTagExtension, wikiLinkExtension, tableExtension];

/** commonmark + GFM + the dialect + fold config, as a standalone parser for callers with only text (no editor). */
export const dialectParser = baseParser.configure([...gfmWithoutTable, ...dialectExtensions, ...editorFoldConfig]);

