import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { CompletionContext } from '@codemirror/autocomplete';
import { markdown } from '@codemirror/lang-markdown';
import { fullyParsed } from '../test/fullParse';
import { createTagSource, rankTags } from './tagComplete';

// Most-used first, as the structural index's tag distribution arrives.
const TAGS = ['foo', 'team-core', 'bar', 'food', 'xfoo'];

function complete(doc, pos = doc.length, tags = TAGS) {
  const state = EditorState.create({ doc, extensions: [markdown()] });
  return createTagSource(() => tags)(new CompletionContext(fullyParsed(state), pos, false));
}

describe('tag completion', () => {
  it('opens after # plus a character in prose and lists matching tags, prefix matches first, in count order', () => {
    const r = complete('see #fo');
    expect(r.options.map((o) => o.label)).toEqual(['#foo', '#food', '#xfoo']);
    expect(r.from).toBe(4);
    expect(r.filter).toBe(false);
  });

  it('opens at the start of a line and in list items and quotes', () => {
    expect(complete('#te').options.map((o) => o.label)).toEqual(['#team-core']);
    expect(complete('- #te').options.map((o) => o.label)).toEqual(['#team-core']);
    expect(complete('> #te').options.map((o) => o.label)).toEqual(['#team-core']);
  });

  it('picking inserts the tag, hash included, replacing what was typed', () => {
    const [first] = complete('x #te').options;
    expect(first.apply).toBe('#team-core');
  });

  it('matches the typed text by its normalised form', () => {
    expect(complete('#Team/C').options.map((o) => o.label)).toEqual(['#team-core']);
  });

  it.each([
    ['a heading marker with its space', '# '],
    ['a bare hash', 'text #'],
    ['a heading line', '## Topic #fo'],
    ['a hash glued to a word', 'word#fo'],
    ['inline code', 'run `ls #fo'],
    ['fenced code', '```\n#fo'],
    ['inline math', 'value $a #fo'],
    ['math block', '$$\n#fo'],
    ['inside a wikilink', 'see [[My Page #fo'],
    ['a wikilink anchor', 'see [[Page#fo'],
    ['a link destination', 'see [t](Page#fo'],
    ['a URL fragment', 'see https://example.com/#fo'],
    ['frontmatter', '---\ntitle: x\n#fo'],
  ])('does not open in %s', (_name, doc) => {
    expect(complete(doc)).toBeNull();
  });

  it('offers nothing when no tag matches or none exist', () => {
    expect(complete('#zzz')).toBeNull();
    expect(complete('#fo', undefined, [])).toBeNull();
  });
});

describe('rankTags', () => {
  it('puts prefix matches before contains matches and keeps the incoming order within each', () => {
    expect(rankTags(['b-foo', 'foo2', 'foo1', 'x'], 'foo')).toEqual(['foo2', 'foo1', 'b-foo']);
  });
});
