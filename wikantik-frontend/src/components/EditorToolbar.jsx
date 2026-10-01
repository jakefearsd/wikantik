import { formatKeys } from '../utils/keyHints';

/**
 * Presentational markdown formatting toolbar; each button runs a command-registry id.
 *
 * Props:
 *   onRun(commandId) — e.g. 'format-bold', 'heading-2', 'insert-table'
 */
const LinkGlyph = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    <path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.5 1.5" />
    <path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.5-1.5" />
  </svg>
);

export default function EditorToolbar({ onRun }) {
  const buttons = [
    { id: 'format-bold',   label: 'B',   title: `Bold (${formatKeys('Mod-B')})`, style: { fontWeight: 'bold' } },
    { id: 'format-italic', label: 'I',   title: `Italic (${formatKeys('Mod-I')})`, style: { fontStyle: 'italic' } },
    { id: 'heading-2',     label: 'H',   title: 'Heading',     style: {} },
    { id: 'format-list',   label: '≡',   title: 'List',        style: {} },
    { id: 'format-code',   label: '`',   title: 'Inline code', style: { fontFamily: 'monospace' } },
    { id: 'code-block',    label: '{ }', title: 'Code block',  style: { fontFamily: 'monospace' } },
    { id: 'insert-table',  label: '▦',   title: 'Table',       style: {} },
    { id: 'insert-link',   label: LinkGlyph, title: `Link (${formatKeys('Mod-K')})`, style: {} },
  ];

  return (
    <div className="editor-format-toolbar" role="toolbar" aria-label="Formatting toolbar">
      {buttons.map(({ id, label, title, style }) => (
        <button
          key={id}
          type="button"
          className="editor-format-btn"
          title={title}
          aria-label={title}
          style={style}
          onMouseDown={e => {
            // Prevent editor focus loss on click
            e.preventDefault();
            onRun(id);
          }}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
