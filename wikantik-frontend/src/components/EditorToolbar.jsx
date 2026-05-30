/**
 * Presentational markdown formatting toolbar.
 *
 * Props:
 *   onCommand(commandName) — called with one of:
 *     'bold' | 'italic' | 'heading' | 'list' | 'code' | 'link'
 */
export default function EditorToolbar({ onCommand }) {
  const buttons = [
    { command: 'bold',    label: 'B',  title: 'Bold (⌘B)',    style: { fontWeight: 'bold' } },
    { command: 'italic',  label: 'I',  title: 'Italic (⌘I)',  style: { fontStyle: 'italic' } },
    { command: 'heading', label: 'H',  title: 'Heading',      style: {} },
    { command: 'list',    label: '≡',  title: 'List',         style: {} },
    { command: 'code',    label: '`',  title: 'Code',         style: { fontFamily: 'monospace' } },
    { command: 'link',    label: '⌘K', title: 'Link (⌘K)',    style: {} },
  ];

  return (
    <div className="editor-format-toolbar" role="toolbar" aria-label="Formatting toolbar">
      {buttons.map(({ command, label, title, style }) => (
        <button
          key={command}
          type="button"
          className="editor-format-btn"
          title={title}
          aria-label={title}
          style={style}
          onMouseDown={e => {
            // Prevent textarea focus loss on click
            e.preventDefault();
            onCommand(command);
          }}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
