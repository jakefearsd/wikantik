const CALLOUTS = [['note', 'Note'], ['tip', 'Tip'], ['warning', 'Warning'], ['danger', 'Danger'], ['info', 'Info']];

/** The editor's command-registry entries; every action is injected so the list stays pure. */
export function buildEditorCommands({ format, save, pickImage, togglePreview, toggleRail, foldAll, unfoldAll, toggleLivePreview }) {
  const cmd = (id, title, section, run, extra = {}) => ({ id, title, section, run, ...extra });
  return [
    cmd('editor-save', 'Save', 'Editor', save, { keys: 'Mod-S' }),
    cmd('format-bold', 'Bold', 'Editor', () => format('bold'), { keys: 'Mod-B' }),
    cmd('format-italic', 'Italic', 'Editor', () => format('italic'), { keys: 'Mod-I' }),
    cmd('format-code', 'Inline code', 'Editor', () => format('code')),
    cmd('format-list', 'Bulleted list', 'Editor', () => format('list')),
    cmd('insert-link', 'Insert link', 'Insert', () => format('link'), { keys: 'Mod-K', slash: true }),
    ...[1, 2, 3].map((n) => cmd(`heading-${n}`, `Heading ${n}`, 'Insert', () => format(`h${n}`), { slash: true })),
    ...CALLOUTS.map(([type, label]) => cmd(`callout-${type}`, `Insert callout: ${label}`, 'Insert',
      () => format(`callout:${type}`), { slash: true, slashLabel: `Callout: ${label}`, keywords: ['callout', type] })),
    cmd('insert-table', 'Insert table', 'Insert', () => format('table'), { slash: true }),
    cmd('code-block', 'Code block', 'Insert', () => format('codeblock'), { slash: true }),
    cmd('math-block', 'Math block', 'Insert', () => format('mathblock'), { slash: true }),
    cmd('horizontal-rule', 'Horizontal rule', 'Insert', () => format('rule'), { slash: true }),
    cmd('insert-image', 'Insert image', 'Insert', pickImage, { slash: true }),
    cmd('fold-all', 'Fold all headings', 'View', foldAll, { keys: 'Ctrl-Alt-[' }),
    cmd('unfold-all', 'Unfold all headings', 'View', unfoldAll, { keys: 'Ctrl-Alt-]' }),
    cmd('toggle-preview', 'Toggle preview', 'View', togglePreview),
    cmd('toggle-rail', 'Toggle rail', 'View', toggleRail),
    cmd('toggle-live-preview', 'Toggle live preview', 'View', toggleLivePreview, { keys: 'Mod-E' }),
  ];
}
