import { describe, it, expect, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { CompletionContext } from '@codemirror/autocomplete';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { createSlashSource, slashIconType } from './slashComplete';

const COMMANDS = [
  { id: 'heading-2', title: 'Heading 2', slash: true, run: vi.fn() },
  { id: 'callout-warning', title: 'Insert callout: Warning', slash: true, run: vi.fn() },
  { id: 'fold-all', title: 'Fold all headings', run: vi.fn() },
];

function complete(doc, pos = doc.length) {
  const state = EditorState.create({ doc, extensions: [markdown()] });
  ensureSyntaxTree(state, state.doc.length, 5000);
  const source = createSlashSource(() => COMMANDS, vi.fn());
  return source(new CompletionContext(state, pos, false));
}

describe('slash completion', () => {
  it('offers slash commands at line start', () => {
    const r = complete('/');
    expect(r.options.map((o) => o.label)).toEqual(['Heading 2', 'Insert callout: Warning']);
    expect(r.from).toBe(0);
  });

  it('filters by the typed query and keeps non-slash commands out', () => {
    expect(complete('text /warn').options.map((o) => o.label)).toEqual(['Insert callout: Warning']);
    expect(complete('/fold')).toBeNull();
  });

  it.each([
    ['mid-word', 'and/or'],
    ['URL path', 'see https://example.com/he'],
    ['inline code', 'run `ls /he'],
    ['fenced code', '```\n/he'],
    ['inline math', 'value $a /he'],
    ['math block', '$$\n/he'],
    ['frontmatter', '---\ntitle: x\n/he'],
  ])('does not trigger in %s', (_name, doc) => {
    expect(complete(doc)).toBeNull();
  });

  it('applying an option deletes the typed /query and runs the command', () => {
    const run = vi.fn();
    const state = EditorState.create({ doc: 'a /hea', extensions: [markdown()] });
    const r = createSlashSource(() => COMMANDS, run)(new CompletionContext(state, 6, false));
    const dispatch = vi.fn();
    r.options[0].apply({ dispatch, state }, r.options[0], r.from, 6);
    expect(dispatch).toHaveBeenCalledWith({ changes: { from: 2, to: 6, insert: '' } });
    expect(run).toHaveBeenCalledWith('heading-2');
  });

  it('tags each option with a completion type that picks its per-kind icon', () => {
    expect(complete('/').options.map((o) => o.type)).toEqual(['slash slash-heading-2', 'slash slash-callout slash-callout-warning']);
  });
});

describe('slashIconType', () => {
  it.each([
    ['heading-1', 'slash slash-heading-1'],
    ['heading-3', 'slash slash-heading-3'],
    ['callout-tip', 'slash slash-callout slash-callout-tip'],
    ['callout-caution', 'slash slash-callout slash-callout-warning'],
    ['insert-table', 'slash slash-table'],
    ['code-block', 'slash slash-code'],
    ['math-block', 'slash slash-math'],
    ['horizontal-rule', 'slash slash-rule'],
    ['insert-image', 'slash slash-image'],
    ['insert-link', 'slash slash-link'],
    ['something-else', 'slash'],
  ])('%s → %s', (id, type) => {
    expect(slashIconType(id)).toBe(type);
  });
});
