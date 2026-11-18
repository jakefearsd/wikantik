const CALLOUTS = [['note', 'Note'], ['tip', 'Tip'], ['warning', 'Warning'], ['danger', 'Danger'], ['info', 'Info']];

/**
 * What a new diagram fence starts with, by fence language. Mermaid's is the first source the shared reader fixture
 * (`__fixtures__/renderedFences.json`, `mermaidPolicy`) allows; that fixture holds no PlantUML source, so PlantUML's
 * is the smallest whole diagram in the `@startuml` spelling the wiki's pages use.
 */
export const FENCE_STARTERS = {
  mermaid: 'flowchart TD\n  A-->B',
  plantuml: '@startuml\nA -> B\n@enduml',
};

/** The editor's command-registry entries; every action is injected so the list stays pure. */
export function buildEditorCommands({ format, save, pickImage, togglePreview, toggleRail, foldAll, unfoldAll, toggleLivePreview, pastePlain, copyBlockLink, pickAccessRule, showSourcePane }) {
  const cmd = (id, title, section, run, extra = {}) => ({ id, title, section, run, ...extra });
  return [
    cmd('editor-save', 'Save', 'Editor', save, { keys: 'Mod-S' }),
    cmd('format-bold', 'Bold', 'Editor', () => format('bold'), { keys: 'Mod-B' }),
    cmd('format-italic', 'Italic', 'Editor', () => format('italic'), { keys: 'Mod-I' }),
    cmd('format-code', 'Inline code', 'Editor', () => format('code')),
    cmd('format-highlight', 'Highlight', 'Editor', () => format('highlight'), { keys: 'Mod-Shift-H', keywords: ['mark', '=='], slash: true }),
    cmd('format-strike', 'Strikethrough', 'Editor', () => format('strike'), { keys: 'Mod-Shift-X', keywords: ['strike', 'delete', '~~'] }),
    cmd('toggle-comment', 'Toggle comment', 'Editor', () => format('comment'), { keys: 'Mod-/', keywords: ['%%', 'hidden', 'note'], slash: true }),
    cmd('format-list', 'Bulleted list', 'Editor', () => format('list'), { slash: true }),
    cmd('format-ordered-list', 'Numbered list', 'Editor', () => format('olist'), { slash: true, keywords: ['ordered'] }),
    cmd('format-task-list', 'Task list', 'Editor', () => format('tasklist'), { slash: true, keywords: ['todo', 'checkbox', 'checklist'] }),
    cmd('format-quote', 'Quote', 'Editor', () => format('quote'), { slash: true, keywords: ['blockquote', '>'] }),
    // The hint is the browser's own plain-text paste, which the editor honours; the command reads the clipboard.
    cmd('paste-plain', 'Paste as plain text', 'Editor', pastePlain, { keys: 'Mod-Shift-V', keywords: ['clipboard', 'unformatted'] }),
    cmd('copy-block-link', 'Copy block link', 'Editor', copyBlockLink, { keywords: ['block', 'reference', 'anchor'] }),
    cmd('insert-link', 'Insert link', 'Insert', () => format('link'), { keys: 'Mod-K', slash: true }),
    ...[1, 2, 3, 4].map((n) => cmd(`heading-${n}`, `Heading ${n}`, 'Insert', () => format(`h${n}`), { slash: true })),
    ...CALLOUTS.map(([type, label]) => cmd(`callout-${type}`, `Insert callout: ${label}`, 'Insert',
      () => format(`callout:${type}`), { slash: true, slashLabel: `Callout: ${label}`, keywords: ['callout', type] })),
    cmd('insert-table', 'Insert table', 'Insert', () => format('table'), { slash: true }),
    cmd('code-block', 'Code block', 'Insert', () => format('codeblock'), { slash: true }),
    cmd('insert-mermaid', 'Mermaid diagram', 'Insert', () => format('fence:mermaid'), { slash: true, keywords: ['flowchart', 'chart'] }),
    cmd('insert-plantuml', 'PlantUML diagram', 'Insert', () => format('fence:plantuml'), { slash: true, keywords: ['uml', 'sequence'] }),
    cmd('math-block', 'Math block', 'Insert', () => format('mathblock'), { slash: true }),
    cmd('horizontal-rule', 'Horizontal rule', 'Insert', () => format('rule'), { slash: true }),
    cmd('insert-image', 'Insert image', 'Insert', pickImage, { slash: true }),
    cmd('insert-embed', 'Embed a page', 'Insert', () => format('embed'), { slash: true, keywords: ['transclude', 'include', '![['] }),
    cmd('insert-footnote', 'Footnote', 'Insert', () => format('footnote'), { slash: true, keywords: ['note', 'reference'] }),
    cmd('insert-access-rule', 'Access rule', 'Insert', pickAccessRule, { slash: true, keywords: ['acl', 'allow', 'permission', 'restrict'] }),
    cmd('fold-all', 'Fold all headings', 'View', foldAll, { keys: 'Ctrl-Alt-[' }),
    cmd('unfold-all', 'Unfold all headings', 'View', unfoldAll, { keys: 'Ctrl-Alt-]' }),
    cmd('toggle-preview', 'Toggle preview', 'View', togglePreview),
    cmd('pane-source', 'Show Markdown source beside the editor', 'View', showSourcePane, { keywords: ['markdown', 'mirror', 'pane'] }),
    cmd('toggle-rail', 'Toggle rail', 'View', toggleRail),
    cmd('toggle-live-preview', 'Switch between Rich and Source', 'View', toggleLivePreview, { keys: 'Mod-E' }),
  ];
}
