import { commonmarkLanguage } from '@codemirror/lang-markdown';
import { editorFoldConfig } from './markdownFold';
import { dialectExtensions, gfmWithoutTable } from './dialect';

/**
 * The editor's markdown dialect: commonmark plus GFM (tables, strikethrough, task lists, autolinks), the wiki's own
 * constructs (display/inline math nodes, see `dialect/`) and the fold-limiting parser extensions. Deliberately NOT
 * `markdownLanguage`, which adds Subscript/Superscript/Emoji (`~x~`, `^x^`, `:smile:`): the server renders none of
 * them, and `^x^` collided with the `^id` block marker. Used in both source and live mode. Tests build states
 * with markdown(editorMarkdownConfig).
 */
export const editorMarkdownConfig = {
  base: commonmarkLanguage,
  extensions: [...gfmWithoutTable, ...dialectExtensions, ...editorFoldConfig],
};
