import { describe, it, expect, vi } from 'vitest';
import { buildEditorCommands, buildTableCommands, TABLE_COMMAND_IDS, FENCE_STARTERS } from './editorCommands';

const deps = () => ({ format: vi.fn(), save: vi.fn(), pickImage: vi.fn(), togglePreview: vi.fn(),
                      toggleRail: vi.fn(), foldAll: vi.fn(), unfoldAll: vi.fn(), toggleLivePreview: vi.fn(),
                      pastePlain: vi.fn(), copyBlockLink: vi.fn(), pickAccessRule: vi.fn(), showSourcePane: vi.fn() });

describe('buildEditorCommands', () => {
  it('declares the editor command set with stable ids', () => {
    const ids = buildEditorCommands(deps()).map((c) => c.id);
    expect(ids).toEqual(expect.arrayContaining([
      'editor-save', 'format-bold', 'format-italic', 'format-code', 'format-list', 'insert-link',
      'heading-1', 'heading-2', 'heading-3', 'callout-note', 'callout-tip', 'callout-warning', 'callout-danger',
      'callout-info', 'insert-table', 'code-block', 'math-block', 'horizontal-rule', 'insert-image',
      'fold-all', 'unfold-all', 'toggle-preview', 'toggle-rail', 'toggle-live-preview', 'paste-plain', 'copy-block-link']));
  });

  it('offers the Markdown source pane as a View command', () => {
    const d = deps();
    const cmd = buildEditorCommands(d).find((c) => c.id === 'pane-source');
    expect(cmd).toMatchObject({ title: 'Show Markdown source beside the editor', section: 'View' });
    expect(cmd.slash).toBeUndefined();
    cmd.run();
    expect(d.showSourcePane).toHaveBeenCalledTimes(1);
  });

  it('marks the insert and block commands as slash commands', () => {
    const slash = buildEditorCommands(deps()).filter((c) => c.slash).map((c) => c.id).sort();
    expect(slash).toEqual(['callout-danger', 'callout-info', 'callout-note', 'callout-tip', 'callout-warning',
      'code-block', 'heading-1', 'heading-2', 'heading-3', 'heading-4', 'horizontal-rule', 'insert-image', 'insert-link',
      'insert-table', 'math-block', 'format-list', 'format-ordered-list', 'format-task-list', 'format-quote',
      'insert-mermaid', 'insert-plantuml', 'insert-footnote', 'insert-embed', 'insert-access-rule', 'format-highlight',
      'toggle-comment'].sort());
  });

  it.each([
    ['format-strike', 'Strikethrough', 'strike', false],
    ['format-ordered-list', 'Numbered list', 'olist', true],
    ['format-task-list', 'Task list', 'tasklist', true],
    ['format-quote', 'Quote', 'quote', true],
    ['heading-4', 'Heading 4', 'h4', true],
    ['insert-mermaid', 'Mermaid diagram', 'fence:mermaid', true],
    ['insert-plantuml', 'PlantUML diagram', 'fence:plantuml', true],
    ['insert-footnote', 'Footnote', 'footnote', true],
    ['insert-embed', 'Embed a page', 'embed', true],
  ])('declares %s as "%s", routed through format(%s)', (id, title, arg, slash) => {
    const d = deps();
    const c = buildEditorCommands(d).find((x) => x.id === id);
    expect(c).toMatchObject({ id, title });
    expect(!!c.slash).toBe(slash);
    c.run();
    expect(d.format).toHaveBeenCalledTimes(1);
    expect(d.format).toHaveBeenCalledWith(arg);
  });

  it('declares Strikethrough with its Mod-Shift-X hint', () => {
    expect(buildEditorCommands(deps()).find((c) => c.id === 'format-strike').keys).toBe('Mod-Shift-X');
  });

  it('"Access rule" is a slash command that opens the form instead of formatting', () => {
    const d = deps();
    const c = buildEditorCommands(d).find((x) => x.id === 'insert-access-rule');
    expect(c).toMatchObject({ title: 'Access rule', slash: true });
    c.run();
    expect(d.pickAccessRule).toHaveBeenCalled();
    expect(d.format).not.toHaveBeenCalled();
  });

  it('holds a starter body for each diagram kind', () => {
    expect(FENCE_STARTERS.mermaid).toBe('flowchart TD\n  A-->B');
    expect(FENCE_STARTERS.plantuml).toBe('@startuml\nA -> B\n@enduml');
  });

  it('routes to the right dependency', () => {
    const d = deps();
    const byId = Object.fromEntries(buildEditorCommands(d).map((c) => [c.id, c]));
    byId['callout-warning'].run();
    byId['heading-2'].run();
    byId['editor-save'].run();
    byId['insert-image'].run();
    expect(d.format).toHaveBeenCalledWith('callout:warning');
    expect(d.format).toHaveBeenCalledWith('h2');
    expect(d.save).toHaveBeenCalled();
    expect(d.pickImage).toHaveBeenCalled();
  });

  it('gives callouts a short slash-menu label but keeps the palette title', () => {
    const byId = Object.fromEntries(buildEditorCommands(deps()).map((c) => [c.id, c]));
    expect(byId['callout-warning'].slashLabel).toBe('Callout: Warning');
    expect(byId['callout-warning'].title).toBe('Insert callout: Warning');
    expect(byId['callout-note'].slashLabel).toBe('Callout: Note');
  });

  it('declares the live-preview toggle with Mod-E and runs the injected action', () => {
    const d = deps();
    const cmd = buildEditorCommands(d).find((c) => c.id === 'toggle-live-preview');
    expect(cmd).toMatchObject({ title: 'Switch between Rich and Source', section: 'View', keys: 'Mod-E' });
    cmd.run();
    expect(d.toggleLivePreview).toHaveBeenCalled();
  });

  it('declares "Paste as plain text" with its Mod-Shift-V hint (not a slash command) and runs the injected action', () => {
    const d = deps();
    const cmd = buildEditorCommands(d).find((c) => c.id === 'paste-plain');
    expect(cmd).toMatchObject({ title: 'Paste as plain text', section: 'Editor', keys: 'Mod-Shift-V' });
    expect(cmd.slash).toBeFalsy();
    cmd.run();
    expect(d.pastePlain).toHaveBeenCalled();
  });

  it('offers "Copy block link" in the palette (not the slash menu), findable by block words', () => {
    const d = deps();
    const c = buildEditorCommands(d).find((x) => x.id === 'copy-block-link');
    expect(c).toMatchObject({ title: 'Copy block link', section: 'Editor', keywords: ['block', 'reference', 'anchor'] });
    expect(c.slash).toBeUndefined();
    c.run();
    expect(d.copyBlockLink).toHaveBeenCalled();
  });

  it('offers Highlight (Mod-Shift-H) and Toggle comment (Mod-/) in the palette and the slash menu, routed through format', () => {
    const d = deps();
    const byId = Object.fromEntries(buildEditorCommands(d).map((c) => [c.id, c]));
    expect(byId['format-highlight']).toMatchObject({ title: 'Highlight', section: 'Editor', keys: 'Mod-Shift-H' });
    expect(byId['toggle-comment']).toMatchObject({ title: 'Toggle comment', section: 'Editor', keys: 'Mod-/' });
    expect(byId['format-highlight'].slash).toBe(true);
    expect(byId['toggle-comment'].slash).toBe(true);
    byId['format-highlight'].run();
    byId['toggle-comment'].run();
    expect(d.format).toHaveBeenCalledWith('highlight');
    expect(d.format).toHaveBeenCalledWith('comment');
  });
});

describe('buildTableCommands', () => {
  it('declares the table commands in the Table section, each handing its id to runTable', () => {
    const runTable = vi.fn();
    const commands = buildTableCommands({ runTable });
    expect(commands.map((c) => c.id).sort()).toEqual(['table-row-above', 'table-row-below', 'table-column-left',
      'table-column-right', 'table-delete-row', 'table-delete-column', 'table-move-column-left', 'table-move-column-right',
      'table-align-left', 'table-align-center', 'table-align-right', 'table-align-none'].sort());
    expect(commands.map((c) => c.id)).toEqual(TABLE_COMMAND_IDS);
    expect(commands.every((c) => c.section === 'Table' && c.title && !c.slash && !c.keys)).toBe(true);
    commands.forEach((c) => c.run());
    expect(runTable.mock.calls.map(([id]) => id)).toEqual(TABLE_COMMAND_IDS);
  });

  it('is not part of the editor\'s standing command set', () => {
    const ids = buildEditorCommands({}).map((c) => c.id);
    expect(ids.filter((id) => id.startsWith('table-'))).toEqual([]);
  });
});
