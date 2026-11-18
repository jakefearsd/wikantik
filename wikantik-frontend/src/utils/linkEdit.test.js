import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { syntaxTree } from '@codemirror/language';
import fixture from './__fixtures__/wikilinks.json';
import { fullyParsed } from '../test/fullParse';
import { editorMarkdownConfig } from './editorMarkdown';
import { readLinkAt, writeLink, unwrapLink, linkChange } from './linkEdit';

const stateOf = (doc) => fullyParsed(EditorState.create({ doc, extensions: [markdown(editorMarkdownConfig)] }));
/** The model of the link `token`, read with the caret one character into it, inside `before…after`. */
function read(token, before = 'See ', after = ' here.') {
  return readLinkAt(stateOf(before + token + after), before.length + 1);
}
/** `token` after `patch`, as the document would hold it. */
const patched = (token, patch) => linkChange(read(token), patch).insert;

describe('readLinkAt', () => {
  it.each([
    ['[[P]]', { kind: 'wiki', target: 'P', heading: null, alias: null, size: null }],
    ['[[P#H]]', { kind: 'wiki', target: 'P', heading: 'H', alias: null }],
    ['[[P|a]]', { kind: 'wiki', target: 'P', heading: null, alias: 'a' }],
    ['[[P#H|a]]', { kind: 'wiki', target: 'P', heading: 'H', alias: 'a' }],
    ['[[#H]]', { kind: 'wiki', target: '', heading: 'H', alias: null }],
    ['![[P]]', { kind: 'embed', target: 'P', heading: null, alias: null, size: null }],
    ['![[img.png|300]]', { kind: 'embed', target: 'img.png', alias: null, size: '300' }],
    ['![[Owner/img.png|300x200]]', { kind: 'embed', target: 'Owner/img.png', alias: null, size: '300x200' }],
    ['![[img.png|a caption]]', { kind: 'embed', target: 'img.png', alias: 'a caption', size: null }],
    ['[[P|300]]', { kind: 'wiki', target: 'P', alias: '300', size: null }],
    ['![[P|300]]', { kind: 'embed', target: 'P', alias: '300', size: null }],
    ['[t](u)', { kind: 'link', text: 't', url: 'u', title: null }],
    ['[t](<u v>)', { kind: 'link', text: 't', url: 'u v', title: null }],
    ['[t]()', { kind: 'link', text: 't', url: '', title: null }],
    ['[a \\[b\\]](u \'it\')', { kind: 'link', text: 'a [b]', url: 'u', title: 'it' }],
    ['[n *b* m](u (t))', { kind: 'link', text: 'n *b* m', url: 'u', title: 't' }],
    ['![a](u "t")', { kind: 'image', alt: 'a', url: 'u', title: 't' }],
    ['![](u)', { kind: 'image', alt: '', url: 'u', title: null }],
  ])('reads %s', (token, fields) => {
    const model = read(token);
    expect(model).toMatchObject({ ...fields, from: 4, to: 4 + token.length, source: token });
  });

  it('finds the link from either of its ends and from every position inside it', () => {
    const state = stateOf('a [[P|a]] b');
    for (let pos = 2; pos <= 9; pos++) expect(readLinkAt(state, pos)?.source).toBe('[[P|a]]');
    expect(readLinkAt(state, 1)).toBeNull();
    expect(readLinkAt(state, 10)).toBeNull();
  });

  it.each([
    ['inline code', '`[[P]]`', 3],
    ['a fenced block', '```\n[[P]]\n```', 6],
    ['an indented block', 'x\n\n    [[P]]', 9],
    ['inline code holding a Markdown link', '`[t](u)`', 3],
    ['a bracket pair that is no wikilink', '[[ not a link]]', 4],
    ['the fixture\'s shell test', 'if [[ -f x ]]; then', 6],
    ['a reference-style link', '[r][ref]\n\n[ref]: http://x', 1],
    ['a shortcut reference link', '[ref]\n\n[ref]: http://x', 1],
    ['a link reference definition', '[r][ref]\n\n[ref]: http://x', 12],
    ['an autolink', 'see <http://x.y> now', 8],
    ['a bare URL', 'see https://bare.example/x now', 8],
    ['plain prose', 'just words', 3],
  ])('is null in %s', (_name, doc, pos) => {
    expect(readLinkAt(stateOf(doc), pos)).toBeNull();
  });

  it('reads the innermost of nested links, and the outer one from its own text', () => {
    const state = stateOf('[![a](i)](u)');
    expect(readLinkAt(state, 4)).toMatchObject({ kind: 'image', alt: 'a', url: 'i' });
    expect(readLinkAt(state, 10)).toMatchObject({ kind: 'link', text: '![a](i)', url: 'u' });
  });

  it('reads a wikilink after an escaped bang as a link, not an embed', () => {
    expect(read('[[P]]', 'a \\!')).toMatchObject({ kind: 'wiki', target: 'P', source: '[[P]]' });
  });
});

describe('writeLink', () => {
  it.each([
    '[[P]]', '[[P#H]]', '[[P|a]]', '[[P#H|a]]', '![[P]]', '![[img.png|300]]', '[t](u)', '[t](<u v>)', '![a](u "t")',
    '[[P |a ]]', '[[P# H | a]]', '[[P|]]', '![[img.png|300x200]]', '[[#^abc]]',
    '[t]( u  \'t\' )', '[a \\[b\\]](u)', '[n *b* m](<u>)', '[t]()', '![](u)', '[t](u (paren))', '[t\nu](v)',
    '[t](u "")', '[t](u \'\')', '![a](u "")', '![a](u \'\')', '[t](u ())', '![a b](<u v> \'t\')',
  ])('writes an unread-then-unchanged %s back as it was', (token) => {
    const model = read(token);
    expect(writeLink(model)).toBe(token);
    expect(linkChange(model, {})).toEqual({ from: 4, to: 4 + token.length, insert: token });
    // The same values patched in again are no change either, whatever the kind.
    const { kind: _kind, from: _from, to: _to, source: _source, original: _original, ...fields } = model;
    expect(linkChange(model, fields).insert).toBe(token);
    expect(linkChange(model, Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, v ?? '']))).insert).toBe(token);
  });

  it.each(['[[P|a]]', '![[img.png|300]]', '[t](u "t")', '![a](u "t")', '![a](u "")'])(
    'takes an unchanged %s from its source, not from its parts', (token) => {
      // `source` is what an unchanged model returns: a stand-in there shows the comparison found no change.
      expect(writeLink({ ...read(token), source: 'AS READ' })).toBe('AS READ');
    });

  it('reads an empty title as no title, and writes one over it', () => {
    expect(read('[t](u "")')).toMatchObject({ title: null });
    expect(patched('[t](u "")', { title: 'x' })).toBe('[t](u "x")');
    expect(patched('[t](u "")', { url: 'v' })).toBe('[t](v "")');
  });

  it('writes every wikilink of the shared reader fixture back byte for byte', () => {
    let seen = 0;
    for (const { markdown: doc } of fixture) {
      const state = stateOf(doc);
      syntaxTree(state).iterate({
        enter: (node) => {
          if (node.name !== 'WikiLink' && node.name !== 'WikiEmbed') return;
          const model = readLinkAt(state, node.from + 1);
          expect(model, doc).not.toBeNull();
          expect(writeLink(model)).toBe(doc.slice(node.from, node.to));
          // The same values patched in again are no change either.
          const { target, heading, alias, size } = model;
          expect(linkChange(model, { target, heading, alias, size }).insert).toBe(model.source);
          seen++;
        },
      });
    }
    expect(seen).toBeGreaterThan(10);
  });

  it('writes a patched copy without changing the model it was read into', () => {
    const model = read('[[P|a]]');
    expect(writeLink({ ...model, target: 'Q' })).toBe('[[Q|a]]');
    expect(writeLink(model)).toBe('[[P|a]]');
  });
});

describe('linkChange — wikilinks and embeds', () => {
  it.each([
    ['[[P|a]]', { target: 'Q' }, '[[Q|a]]'],
    ['[[P|a]]', { alias: '' }, '[[P]]'],
    ['[[P|a]]', { alias: null }, '[[P]]'],
    ['[[P]]', { alias: 'a' }, '[[P|a]]'],
    ['[[P]]', { alias: '  a  ' }, '[[P|a]]'],
    ['[[P]]', { heading: 'H' }, '[[P#H]]'],
    ['[[P#H|a]]', { heading: '' }, '[[P|a]]'],
    ['[[P#H|a]]', { target: 'Q', heading: 'G', alias: 'b' }, '[[Q#G|b]]'],
    ['[[P#H]]', { target: '' }, '[[#H]]'],
    ['[[P|a|b]]', { target: 'Q' }, '[[Q|a|b]]'],
    ['![[img.png|300]]', { size: '400' }, '![[img.png|400]]'],
    ['![[img.png|300]]', { size: '400x200' }, '![[img.png|400x200]]'],
    ['![[img.png|300]]', { size: '' }, '![[img.png]]'],
    ['![[img.png]]', { size: '250' }, '![[img.png|250]]'],
    ['![[img.png|300]]', { target: 'other.png' }, '![[other.png|300]]'],
    ['![[img.png|cap]]', { size: '250' }, '![[img.png|250]]'],
  ])('%s patched with %o is %s', (token, patch, expected) => {
    expect(patched(token, patch)).toBe(expected);
  });

  it('touches only the part that changed: odd spacing elsewhere stays', () => {
    expect(patched('[[P# H | a ]]', { alias: 'b' })).toBe('[[P# H |b]]');
    expect(patched('[[P# H | a ]]', { target: 'Q' })).toBe('[[Q#H| a ]]');
  });

  it('keeps the escaped pipe of a table cell, and uses one when it adds display text there', () => {
    const doc = '| a | b |\n|---|---|\n| [[P\\|cell]] | [[Q]] |\n';
    const state = stateOf(doc);
    const first = readLinkAt(state, doc.indexOf('[[P') + 1);
    expect(first).toMatchObject({ target: 'P', alias: 'cell', source: '[[P\\|cell]]' });
    expect(linkChange(first, { target: 'R' }).insert).toBe('[[R\\|cell]]');
    expect(linkChange(first, { alias: 'x' }).insert).toBe('[[P\\|x]]');
    const second = readLinkAt(state, doc.indexOf('[[Q') + 1);
    expect(linkChange(second, { alias: 'x' }).insert).toBe('[[Q\\|x]]');
    expect(() => linkChange(second, { alias: 'x|y' })).toThrow(/display text.*\|.*table/i);
    expect(patched('[[Q]]', { alias: 'x|y' })).toBe('[[Q|x|y]]');
  });

  it.each([
    ['an alias holding ]]', { alias: 'x]]y' }, /display text.*bracket/i],
    ['an alias holding one bracket', { alias: 'x]y' }, /display text.*bracket/i],
    ['an alias holding a line break', { alias: 'x\ny' }, /display text.*line break/i],
    ['a target holding ]]', { target: 'Q]]' }, /page.*bracket/i],
    ['a target holding a pipe', { target: 'Q|R' }, /page.*\|/i],
    ['a target holding a line break', { target: 'Q\nR' }, /page.*line break/i],
    ['a target holding #', { target: 'Q#R' }, /page.*#/i],
    ['a target starting with a space', { target: ' Q' }, /page.*space/i],
    ['a heading holding a pipe', { heading: 'H|I' }, /heading.*\|/i],
    ['a heading holding a bracket', { heading: 'H[1]' }, /heading.*bracket/i],
    ['neither page nor heading', { target: '' }, /page or a heading/i],
    ['a size that is no size', { size: 'big' }, /size/i],
  ])('refuses %s with a RangeError that says why', (_name, patch, message) => {
    const model = read(_name.startsWith('a size') ? '![[img.png|300]]' : '[[P|a]]');
    expect(() => linkChange(model, patch)).toThrow(RangeError);
    expect(() => linkChange(model, patch)).toThrow(message);
  });

  it('does not refuse a link for what an untouched field already held', () => {
    // `[[P|a|b]]` is valid as written; changing only the display text must not blame the page.
    expect(patched('[[P|a|b]]', { alias: 'c' })).toBe('[[P|c]]');
  });
});

describe('linkChange — Markdown links and images', () => {
  it.each([
    ['[t](u)', { url: 'v' }, '[t](v)'],
    ['[t](u)', { url: 'v w' }, '[t](<v w>)'],
    ['[t](u)', { url: 'v)w' }, '[t](<v)w>)'],
    ['[t](<u v>)', { url: 'w' }, '[t](w)'],
    ['[t](<u v>)', { text: 's' }, '[s](<u v>)'],
    ['[t](u)', { text: 'a [b] c' }, '[a \\[b\\] c](u)'],
    ['[t](u)', { text: 'a\\' }, '[a\\\\](u)'],
    ['[t](u)', { url: '' }, '[t]()'],
    ['[t]()', { url: 'v' }, '[t](v)'],
    ['[t](u)', { title: 'say "hi"' }, '[t](u "say \\"hi\\"")'],
    ['[t](u \'old\')', { title: 'new' }, '[t](u "new")'],
    ['[t](u \'old\')', { title: '' }, '[t](u)'],
    ['[t](u \'old\')', { url: 'v' }, '[t](v \'old\')'],
    ['[n *b* m](u)', { url: 'v' }, '[n *b* m](v)'],
    ['![a](u "t")', { alt: 'b' }, '![b](u "t")'],
    ['![a](u "t")', { url: 'v w' }, '![a](<v w> "t")'],
    ['![a](u "t")', { alt: '' }, '![](u "t")'],
  ])('%s patched with %o is %s', (token, patch, expected) => {
    expect(patched(token, patch)).toBe(expected);
  });

  it('leaves an image inside the link text alone when the outer link changes', () => {
    const outer = readLinkAt(stateOf('[![a](i)](u)'), 10);
    expect(linkChange(outer, { url: 'v' })).toEqual({ from: 0, to: 12, insert: '[![a](i)](v)' });
  });

  it('reads back what it wrote', () => {
    const insert = patched('[t](u)', { text: 'a [b]', url: 'v w)', title: 'q "r"' });
    expect(read(insert)).toMatchObject({ kind: 'link', text: 'a [b]', url: 'v w)', title: 'q "r"', source: insert });
  });

  it.each([
    ['a URL holding a line break', { url: 'v\nw' }, /address.*line break/i],
    ['a URL holding an angle bracket and a space', { url: 'v <w' }, /address.*</i],
    ['a text holding a blank line', { text: 'a\n\nb' }, /text.*blank line/i],
    ['a title holding a blank line', { title: 'a\n\nb' }, /title.*blank line/i],
  ])('refuses %s with a RangeError that says why', (_name, patch, message) => {
    expect(() => linkChange(read('[t](u)'), patch)).toThrow(RangeError);
    expect(() => linkChange(read('[t](u)'), patch)).toThrow(message);
  });
});

describe('unwrapLink', () => {
  it.each([
    ['[[P|a]]', 'a'],
    ['[[P]]', 'P'],
    ['[[P#H]]', 'P'],
    ['[[#H]]', 'H'],
    ['![[P]]', 'P'],
    ['![[img.png|300]]', 'img.png'],
    ['[t](u)', 't'],
    ['[n *b* m](u "t")', 'n *b* m'],
    ['[a \\[b\\]](u)', 'a \\[b\\]'],
    ['![a](u "t")', 'a'],
  ])('%s unwraps to %s', (token, expected) => {
    expect(unwrapLink(read(token))).toBe(expected);
  });

  it('unwraps a patched model to its new text', () => {
    expect(unwrapLink({ ...read('[[P|a]]'), alias: 'b' })).toBe('b');
    expect(unwrapLink({ ...read('[t](u)'), text: 'x [y]' })).toBe('x \\[y\\]');
  });
});
