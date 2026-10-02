import { describe, it, expect, vi } from 'vitest';
import { buildEditorCommands } from './editorCommands';

const deps = () => ({ format: vi.fn(), save: vi.fn(), pickImage: vi.fn(), togglePreview: vi.fn(),
                      toggleRail: vi.fn(), foldAll: vi.fn(), unfoldAll: vi.fn(), toggleLivePreview: vi.fn() });

describe('buildEditorCommands', () => {
  it('declares the editor command set with stable ids', () => {
    const ids = buildEditorCommands(deps()).map((c) => c.id);
    expect(ids).toEqual(expect.arrayContaining([
      'editor-save', 'format-bold', 'format-italic', 'format-code', 'format-list', 'insert-link',
      'heading-1', 'heading-2', 'heading-3', 'callout-note', 'callout-tip', 'callout-warning', 'callout-danger',
      'callout-info', 'insert-table', 'code-block', 'math-block', 'horizontal-rule', 'insert-image',
      'fold-all', 'unfold-all', 'toggle-preview', 'toggle-rail', 'toggle-live-preview']));
  });

  it('marks only insert commands as slash commands', () => {
    const slash = buildEditorCommands(deps()).filter((c) => c.slash).map((c) => c.id).sort();
    expect(slash).toEqual(['callout-danger', 'callout-info', 'callout-note', 'callout-tip', 'callout-warning',
      'code-block', 'heading-1', 'heading-2', 'heading-3', 'horizontal-rule', 'insert-image', 'insert-link',
      'insert-table', 'math-block'].sort());
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
    expect(cmd).toMatchObject({ title: 'Toggle live preview', section: 'View', keys: 'Mod-E' });
    cmd.run();
    expect(d.toggleLivePreview).toHaveBeenCalled();
  });
});
