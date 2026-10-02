import { markdownLanguage } from '@codemirror/lang-markdown';
import { editorFoldConfig } from './markdownFold';

/**
 * The editor's markdown dialect: GitHub-flavoured (tables, strikethrough, task lists, autolinks) so the source
 * tree matches what the server and preview render, plus the fold-limiting parser extensions. Used in both
 * source and live mode. Tests build states with markdown(editorMarkdownConfig).
 */
export const editorMarkdownConfig = { base: markdownLanguage, extensions: editorFoldConfig };
