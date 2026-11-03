import { GFM, parser as baseParser } from '@lezer/markdown';
import { editorFoldConfig } from '../markdownFold';
import { mathExtension } from './math';
import { highlightExtension } from './highlight';
import { inlineTagExtension } from './inlineTags';
import { wikiLinkExtension } from './wikiLinks';

export { opaqueRanges } from './math';

/** The wiki-specific parser extensions layered on commonmark + GFM (grows with each dialect construct). */
export const dialectExtensions = [mathExtension, highlightExtension, inlineTagExtension, wikiLinkExtension];

/** commonmark + GFM + the dialect + fold config, as a standalone parser for callers with only text (no editor). */
export const dialectParser = baseParser.configure([GFM, ...dialectExtensions, ...editorFoldConfig]);

