import { describe, it, expect, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { CompletionContext } from '@codemirror/autocomplete';
import { markdown } from '@codemirror/lang-markdown';
import { fullyParsed } from '../test/fullParse';
import { createSlashSource, slashIconType } from './slashComplete';

const COMMANDS = [
  { id: 'heading-2', title: 'Heading 2', slash: true, run: vi.fn() },
  { id: 'callout-warning', title: 'Insert callout: Warning', slash: true, run: vi.fn() },
  { id: 'fold-all', title: 'Fold all headings', run: vi.fn() },
];

function complete(doc, pos = doc.length) {
  const state = EditorState.create({ doc, extensions: [markdown()] });
  const source = createSlashSource(() => COMMANDS, vi.fn());
  return source(new CompletionContext(fullyParsed(state), pos, false));
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
    ['closed frontmatter', '---\ntitle: x\n/he\n---\nbody'],
  ])('does not trigger in %s', (_name, doc) => {
    expect(complete(doc)).toBeNull();
  });

  it.each([
    ['after a leading horizontal rule', '---\n\ntext /he'],
    ['between two rules around prose', '---\n\nprose /he\n\n---\n'],
    ['after closed frontmatter', '---\ntitle: x\n---\n/he'],
    ['after an unclosed look-alike once a blank line ends it', '---\ntitle: x\n\nprose /he'],
  ])('does trigger %s', (_name, doc) => {
    expect(complete(doc, doc.indexOf('/he') + 3)).not.toBeNull();
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
    ['something-else', 'slash slash-command'],
  ])('%s → %s', (id, type) => {
    expect(slashIconType(id)).toBe(type);
  });
});

describe('slash menu order', () => {
  const ALL = [
    'insert-link', 'heading-1', 'heading-2', 'heading-3', 'callout-note', 'callout-tip', 'callout-warning',
    'callout-danger', 'callout-info', 'insert-table', 'code-block', 'math-block', 'horizontal-rule', 'insert-image',
  ].map((id) => ({ id, title: id, slashLabel: id.replace(/-/g, ' '), slash: true, run: vi.fn() }));
  const FIXED = ['heading-1', 'heading-2', 'heading-3', 'callout-note', 'callout-tip', 'callout-info', 'callout-warning',
    'callout-danger', 'insert-table', 'code-block', 'math-block', 'horizontal-rule', 'insert-image', 'insert-link'];
  const ids = (doc, cmds = ALL) => {
    const state = EditorState.create({ doc, extensions: [markdown()] });
    const r = createSlashSource(() => cmds, vi.fn())(new CompletionContext(state, doc.length, false));
    return r ? r.options.map((o) => o.label.replace(/ /g, '-')) : [];
  };

  it('lists every command in the fixed logical order for an empty query', () => {
    expect(ids('/')).toEqual(FIXED);
  });

  it('keeps the fixed order (filtered) for a one-character query', () => {
    const one = ids('/c');
    expect(one).toEqual(FIXED.filter((id) => one.includes(id)));
    expect(one.length).toBeGreaterThan(3);
  });

  it('ranks longer queries by match quality, ties broken by the fixed order', () => {
    expect(ids('/callout')).toEqual(['callout-note', 'callout-tip', 'callout-info', 'callout-warning', 'callout-danger']);
    expect(ids('/table')[0]).toBe('insert-table');
  });

  it('puts unknown commands after the known ones, alphabetically', () => {
    const extra = [{ id: 'zeta', title: 'zeta', slash: true }, { id: 'alpha', title: 'alpha', slash: true }];
    expect(ids('/', [...extra, ...ALL]).slice(-2)).toEqual(['alpha', 'zeta']);
  });
});

