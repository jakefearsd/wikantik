import { markdownLanguage } from '@codemirror/lang-markdown';
import { editorFoldConfig } from './markdownFold';

/**
 * The editor's markdown dialect: GitHub-flavoured (tables, strikethrough, task lists, autolinks) so the source
 * tree matches what the server and preview render, plus the fold-limiting parser extensions. Note:
 * `markdownLanguage` is GFM *plus* Subscript/Superscript/Emoji (`~x~`, `^x^`, `:smile:`), which the server does
 * not render; narrowing to commonmark + GFM would need `@lezer/markdown` as a new direct dependency, so the
 * superset is accepted (live preview styles none of those nodes). Used in both
 * source and live mode. Tests build states with markdown(editorMarkdownConfig).
 */
export const editorMarkdownConfig = { base: markdownLanguage, extensions: editorFoldConfig };
