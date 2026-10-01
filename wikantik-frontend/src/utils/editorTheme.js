import { EditorView } from '@codemirror/view';
import { codeFolding } from '@codemirror/language';

/*
 * Editor chrome in the app's design tokens: the completion popup (slash menu + link completion), the fold
 * placeholder and gutter markers, and callout markers in the source. Every selector is rooted at
 * `&.cm-editor` so it out-ranks CodeMirror's base theme, the @uiw light theme and One Dark; colours come only
 * from tokens globals.css defines for both themes (editorTheme.test.js checks that).
 */

const svgMask = (body) => `url("data:image/svg+xml,${encodeURIComponent(
  `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>${body}</svg>`,
)}")`;

/** Stroke icons drawn as CSS masks in currentColor (no icon font, no extra DOM). */
const MASK_ICONS = {
  'slash-callout': "<rect x='3' y='4' width='18' height='16' rx='3'/><path d='M12 8.5v4.5'/><path d='M12 16.5h.01'/>",
  'slash-table': "<rect x='3' y='4' width='18' height='16' rx='2'/><path d='M3 10h18M3 15h18M9 10v10M15 10v10'/>",
  'slash-code': "<path d='M8.5 7 3.5 12l5 5'/><path d='m15.5 7 5 5-5 5'/>",
  'slash-math': "<path d='M18 5H6l6.5 7L6 19h12'/>",
  'slash-rule': "<path d='M3 12h18'/>",
  'slash-command': "<circle cx='12' cy='12' r='3.5' fill='black'/>",
  'slash-image': "<rect x='3' y='4' width='18' height='16' rx='2'/><circle cx='9' cy='10' r='1.75'/><path d='m21 16-5-5-10 9'/>",
  'slash-link': "<path d='M10 14a4.2 4.2 0 0 0 6 0l3-3a4.2 4.2 0 0 0-6-6l-1 1'/><path d='M14 10a4.2 4.2 0 0 0-6 0l-3 3a4.2 4.2 0 0 0 6 6l1-1'/>",
  wikilink: "<path d='M6 3h8l4 4v14H6z'/><path d='M14 3v4h4'/>",
  newpage: "<path d='M6 3h8l4 4v14H6z'/><path d='M12 11v6M9 14h6'/>",
  attachment: "<path d='m20.5 11.5-8.4 8.4a5 5 0 0 1-7.1-7.1l8.4-8.4a3.4 3.4 0 0 1 4.8 4.8l-8.4 8.4a1.7 1.7 0 0 1-2.4-2.4L15 8'/>",
};

/** Short text glyphs for the kinds that read best as letters. */
const TEXT_ICONS = {
  'slash-heading-1': 'H1',
  'slash-heading-2': 'H2',
  'slash-heading-3': 'H3',
  heading: '#',
};

/** Callout colour styles (the values of remarkCallouts' alias map); each has a --callout-<style> token. */
export const CALLOUT_STYLES = ['note', 'abstract', 'info', 'todo', 'tip', 'success', 'question', 'warning',
  'failure', 'danger', 'bug', 'example', 'quote'];

const ROOT = '&.cm-editor';
const POPUP = `${ROOT} .cm-tooltip.cm-tooltip-autocomplete`;
const ROW = `${POPUP} > ul > li`;

function iconRules() {
  const rules = {};
  for (const [kind, body] of Object.entries(MASK_ICONS)) {
    rules[`${ROOT} .cm-completionIcon-${kind}::after`] = {
      content: '""',
      width: '14px',
      height: '14px',
      backgroundColor: 'currentColor',
      WebkitMask: `${svgMask(body)} center / contain no-repeat`,
      mask: `${svgMask(body)} center / contain no-repeat`,
    };
  }
  for (const [kind, glyph] of Object.entries(TEXT_ICONS)) {
    rules[`${ROOT} .cm-completionIcon-${kind}::after`] = { content: `"${glyph}"` };
  }
  for (const style of CALLOUT_STYLES) {
    // The slash menu tints each callout's icon in its own colour, and the source marker likewise.
    rules[`${ROOT} .cm-completionIcon-slash-callout-${style}`] = {
      color: `var(--callout-${style})`,
      backgroundColor: `color-mix(in srgb, var(--callout-${style}) 14%, transparent)`,
    };
    rules[`${ROOT} .cm-callout-marker-${style}`] = { '--cm-callout-tint': `var(--callout-${style})` };
  }
  return rules;
}

/** The theme spec, exported so tests can check every token it reads. */
export const editorChromeSpec = {
  // ── Completion popup ─────────────────────────────────────────────────────
  [POPUP]: {
    backgroundColor: 'var(--bg-elevated)',
    color: 'var(--text)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-md)',
    boxShadow: '0 10px 28px var(--shadow-strong), 0 1px 3px var(--shadow)',
    padding: '4px 0',
    fontFamily: 'var(--font-ui)',
    fontSize: '0.85rem',
  },
  [`${POPUP} > ul`]: {
    fontFamily: 'var(--font-ui)',
    minWidth: '264px',
    maxHeight: '19em',
    padding: '0 4px',
  },
  [ROW]: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.6rem',
    padding: '5px 8px',
    lineHeight: '1.35',
    borderRadius: 'var(--radius-sm)',
    color: 'var(--text)',
  },
  [`${ROW}:hover`]: { backgroundColor: 'color-mix(in srgb, var(--text) 5%, transparent)' },
  [`${ROW}[aria-selected]`]: { backgroundColor: 'var(--row-selected-bg)', color: 'var(--text)' },
  [`${ROOT} .cm-tooltip.cm-tooltip-autocomplete-disabled > ul > li[aria-selected]`]: {
    backgroundColor: 'color-mix(in srgb, var(--text) 8%, transparent)',
  },
  [`${ROOT} .cm-completionLabel`]: {
    flex: '1 1 auto',
    minWidth: '0',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  [`${ROOT} .cm-completionMatchedText`]: { textDecoration: 'none', fontWeight: '600' },
  [`${ROOT} .cm-completionDetail`]: {
    flex: 'none',
    marginLeft: 'auto',
    paddingLeft: '1rem',
    fontStyle: 'normal',
    fontSize: '0.78rem',
    letterSpacing: '0.03em',
    color: 'var(--text-secondary)',
  },
  [`${ROOT} .cm-completionIcon`]: {
    flex: 'none',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    boxSizing: 'border-box',
    width: '1.5rem',
    height: '1.5rem',
    padding: '0',
    borderRadius: 'var(--radius-sm)',
    backgroundColor: 'color-mix(in srgb, var(--text) 6%, transparent)',
    color: 'var(--text-secondary)',
    opacity: '1',
    fontFamily: 'var(--font-ui)',
    fontSize: '0.68rem',
    fontWeight: '700',
    letterSpacing: '0.02em',
    lineHeight: '1',
  },
  [`${ROW}[aria-selected] .cm-completionIcon:not(.cm-completionIcon-slash-callout)`]: {
    color: 'var(--accent)',
    backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)',
  },

  // ── Folding ──────────────────────────────────────────────────────────────
  // A pill on the folded line. Its fill is a tint of the text colour rather than --bg-elevated: the light
  // editor surface is white, the same as --bg-elevated, so an elevated fill would vanish there.
  [`${ROOT} .cm-foldPlaceholder`]: {
    display: 'inline-block',
    margin: '0 6px',
    padding: '0 6px',
    backgroundColor: 'color-mix(in srgb, var(--text) 7%, transparent)',
    border: '1px solid color-mix(in srgb, var(--text-secondary) 45%, transparent)',
    borderRadius: '999px',
    color: 'var(--text-secondary)',
    fontFamily: 'var(--font-ui)',
    fontSize: '0.85em',
    fontWeight: '600',
    lineHeight: '1.25',
    letterSpacing: '0.08em',
    cursor: 'pointer',
  },
  [`${ROOT} .cm-foldPlaceholder:hover`]: {
    color: 'var(--accent)',
    borderColor: 'var(--accent)',
    backgroundColor: 'color-mix(in srgb, var(--accent) 10%, transparent)',
  },
  [`${ROOT} .cm-foldGutter .cm-gutterElement`]: { color: 'var(--text-muted)', cursor: 'pointer' },
  [`${ROOT} .cm-foldGutter .cm-gutterElement:hover`]: { color: 'var(--accent)' },

  // ── Callout markers in the source ────────────────────────────────────────
  [`${ROOT} .cm-callout-marker`]: {
    '--cm-callout-tint': 'var(--callout-note)',
    color: 'var(--cm-callout-tint)',
    backgroundColor: 'color-mix(in srgb, var(--cm-callout-tint) 13%, transparent)',
    borderRadius: 'var(--radius-sm)',
    padding: '1px 3px',
    fontFamily: 'inherit',
    fontWeight: '600',
    textDecoration: 'none',
  },
  // The marker wraps the link highlight spans (and the Ctrl-hover link mark): neutralise their colour/underline.
  [`${ROOT} .cm-content .cm-callout-marker *`]: { color: 'inherit', textDecoration: 'none', cursor: 'text' },

  ...iconRules(),
};

/**
 * The editor chrome theme plus a fold placeholder that reads as "⋯". Add once to the editor's extensions.
 */
export const editorChrome = [EditorView.theme(editorChromeSpec), codeFolding({ placeholderText: '⋯' })];
