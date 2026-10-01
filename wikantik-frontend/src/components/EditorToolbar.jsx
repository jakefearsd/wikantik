import { formatKeys } from '../utils/keyHints';

/**
 * Presentational markdown formatting toolbar; each button runs a command-registry id.
 *
 * Props:
 *   onRun(commandId) — e.g. 'format-bold', 'heading-2', 'insert-table'
 */
export default function EditorToolbar({ onRun }) {
  const buttons = [
    { id: 'format-bold',   label: 'B',   title: `Bold (${formatKeys('Mod-B')})`, style: { fontWeight: 'bold' } },
    { id: 'format-italic', label: 'I',   title: `Italic (${formatKeys('Mod-I')})`, style: { fontStyle: 'italic' } },
    { id: 'heading-2',     label: 'H',   title: 'Heading',     style: {} },
    { id: 'format-list',   label: '≡',   title: 'List',        style: {} },
    { id: 'format-code',   label: '`',   title: 'Inline code', style: { fontFamily: 'monospace' } },
    { id: 'code-block',    label: '{ }', title: 'Code block',  style: { fontFamily: 'monospace' } },
    { id: 'insert-table',  label: '▦',   title: 'Table',       style: {} },
    { id: 'insert-link',   label: formatKeys('Mod-K'), title: `Link (${formatKeys('Mod-K')})`, style: {} },
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
