import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  BLOCK_ID_RE, STANDALONE_RE, isBlockRef, blocksFromMarkdown, newBlockId, insertMarker, blockFingerprint,
  markerChange, blockAt, takenBlockIds, blockLinkEdit, blockMarkers,
} from './blockIds';

// Mirrors the server's BlockIdsTest (com.wikantik.parser.markdown): the same inputs, the same answers.
const TABLE = '| a | b |\n|---|---|\n| 1 | 2 |\n';
const ids = (md) => blocksFromMarkdown(md).map((b) => b.id);
const kinds = (md) => blocksFromMarkdown(md).map((b) => b.kind);
const find = (md, id) => blocksFromMarkdown(md).find((b) => b.id === id);

describe('marker regexes', () => {
  it('needs whitespace before an inline marker and allows trailing blanks', () => {
    expect(BLOCK_ID_RE.exec('Alpha ^abc')[1]).toBe('abc');
    expect(BLOCK_ID_RE.exec('Alpha\t^a-1  ')[1]).toBe('a-1');
    expect(BLOCK_ID_RE.exec('Alpha ^abc\r')[1]).toBe('abc');
    expect(BLOCK_ID_RE.test('x^2')).toBe(false);
    expect(BLOCK_ID_RE.test('a ^b c')).toBe(false);
    expect(BLOCK_ID_RE.test('a ^b_c')).toBe(false);
  });

  it('matches a line that is only a marker', () => {
    expect(STANDALONE_RE.exec('^lone')[1]).toBe('lone');
    expect(STANDALONE_RE.exec('^lone \r')[1]).toBe('lone');
    expect(STANDALONE_RE.test(' ^lone')).toBe(false);
    expect(STANDALONE_RE.test('^')).toBe(false);
  });
});

describe('isBlockRef', () => {
  it('is true only for a heading starting with ^', () => {
    expect(isBlockRef('^abc')).toBe(true);
    expect(isBlockRef('^')).toBe(true);
    expect(isBlockRef('Heading')).toBe(false);
    expect(isBlockRef('a^b')).toBe(false);
    expect(isBlockRef('')).toBe(false);
    expect(isBlockRef(null)).toBe(false);
    expect(isBlockRef(undefined)).toBe(false);
    expect(isBlockRef(42)).toBe(false);
  });
});

describe('blocksFromMarkdown', () => {
  it('returns nothing for empty input', () => {
    expect(blocksFromMarkdown('')).toEqual([]);
    expect(blocksFromMarkdown(null)).toEqual([]);
    expect(blocksFromMarkdown(undefined)).toEqual([]);
  });

  it('reads a paragraph inline marker with its offsets', () => {
    const md = 'Alpha beta ^abc\n';
    expect(blocksFromMarkdown(md)).toEqual([{
      index: 0, id: 'abc', kind: 'paragraph', from: 0, to: 'Alpha beta ^abc'.length,
      markerFrom: 'Alpha beta'.length, markerTo: 'Alpha beta ^abc'.length, inlineAt: 'Alpha beta ^abc'.length,
      text: 'Alpha beta',
    }]);
  });

  it('reads list items', () => {
    const blocks = blocksFromMarkdown('- one ^a1\n- two\n');
    expect(blocks.map((b) => [b.kind, b.id, b.text, b.index])).toEqual([
      ['list-item', 'a1', 'one', 0], ['list-item', null, 'two', 1]]);
    expect(blocks[1].markerFrom).toBe(-1);
    expect(blocks[1].markerTo).toBe(-1);
  });

  it('tags the block before a standalone marker', () => {
    const md = `${TABLE}\n^tbl\n`;
    const [b] = blocksFromMarkdown(md);
    expect(b.kind).toBe('table');
    expect(b.id).toBe('tbl');
    expect(b.markerFrom).toBe(TABLE.length + 1);
    expect(b.markerTo).toBe(TABLE.length + 1 + '^tbl'.length);
    expect(b.from).toBe(0);
    expect(b.to).toBe(TABLE.length - 1);
    expect(b.inlineAt).toBe(-1);
    expect(kinds('> quoted\n\n^q\n')).toEqual(['quote', 'paragraph']);
    expect(ids('> quoted\n\n^q\n')).toEqual(['q', null]);
    expect(kinds('```\ncode\n```\n\n^cd\n')).toEqual(['code']);
    expect(ids('```\ncode\n```\n\n^cd\n')).toEqual(['cd']);
    expect(kinds('$$\nx\n$$\n\n^m\n')).toEqual(['math']);
    expect(ids('$$\nx\n$$\n\n^m\n')).toEqual(['m']);
    expect(kinds('```math\nx\n```\n\n^mf\n')).toEqual(['math']);
    expect(kinds('```math extra\nx\n```\n')).toEqual(['code']);
    expect(kinds('- x\n\n^l\n')).toEqual(['list', 'list-item']);
    expect(ids('- x\n\n^l\n')).toEqual(['l', null]);
    expect(kinds('- x\n')).toEqual(['list-item']);
  });

  it('knows a callout, and its body paragraph starts after the title', () => {
    const md = '> [!note] T\n> body ^b1\n';
    const blocks = blocksFromMarkdown(md);
    expect(blocks.map((b) => b.kind)).toEqual(['callout', 'paragraph']);
    expect(blocks[0].text).toBe('T\nbody');
    expect(blocks[1].id).toBe('b1');
    expect(blocks[1].from).toBe(md.indexOf('body'));
    expect(blocks[1].text).toBe('body');
    expect(ids('> [!note] T\n> body\n\n^c1\n')).toEqual(['c1', null]);
  });

  it('never reads a marker in a heading, code or math', () => {
    expect(blocksFromMarkdown('## Title ^h\n')).toEqual([]);
    for (const md of ['`x ^a`\n', '```\ny ^b\n```\n', '$x ^c$\n', '    z ^d\n', '$$\nw ^e\n$$\n']) {
      expect(ids(md), md).toEqual([null]);
    }
  });

  it('needs whitespace before the caret', () => {
    const [b] = blocksFromMarkdown('e^x and 2^10\n');
    expect(b.id).toBeNull();
    expect(b.markerFrom).toBe(-1);
    expect(ids('squared ^2\n')).toEqual(['2']);
  });

  it('ignores an escaped or entity caret', () => {
    expect(ids('Para \\^esc\n')).toEqual([null]);
    expect(ids('Para &#94;ent\n')).toEqual([null]);
    expect(ids('Para\n\\^esc\n')).toEqual([null]);
    expect(ids('\\^esc\n')).toEqual([null]);
    expect(ids('Para ^a\\-b\n')).toEqual([null]);
  });

  it('reads a marker on the last line of a paragraph', () => {
    expect(find('Para\n^ll\n', 'll').text).toBe('Para');
    expect(find('Para  \n^hb\n', 'hb').text).toBe('Para');
    expect(find('Para\\\n^bs\n', 'bs').text).toBe('Para');
    expect(find('**Bold**\n^bb\n', 'bb').text).toBe('Bold');
    expect(find('**Bold**  \n^hs\n', 'hs').text).toBe('Bold');
    expect(find('- Item\n  ^li\n', 'li').kind).toBe('list-item');
    expect(find('> q\n> ^lq\n', 'lq').kind).toBe('paragraph');
    const md = 'Para\n^ll\n';
    expect(find(md, 'll').markerFrom).toBe(md.indexOf('^'));
  });

  it('lets the first occurrence of an id win', () => {
    const md = 'a ^d\n\nb ^d\n';
    const blocks = blocksFromMarkdown(md);
    expect(blocks.map((b) => b.id)).toEqual(['d', null]);
    expect(blocks[1].markerFrom).toBe(md.lastIndexOf(' ^d'));
    expect(blocks[1].text).toBe('b');
    // source order decides, not block order: the item's inline marker comes before the list's standalone one
    expect(ids('- a ^d\n\n^d\n')).toEqual([null, 'd']);
    expect(ids('^d\n\nText ^d\n')).toEqual([null]);
  });

  it('matches ids case-sensitively', () => {
    expect(ids('a ^Abc\n\nb ^abc\n')).toEqual(['Abc', 'abc']);
  });

  it('reads CRLF text', () => {
    const md = 'Alpha ^abc\r\n\r\n- x ^k\r\n\r\nPara\r\n^cr\r\n\r\n> q\r\n\r\n^s\r\n';
    const blocks = blocksFromMarkdown(md);
    expect(blocks.map((b) => [b.kind, b.id, b.text])).toEqual([
      ['paragraph', 'abc', 'Alpha'], ['list-item', 'k', 'x'], ['paragraph', 'cr', 'Para'],
      ['quote', 's', 'q'], ['paragraph', null, 'q']]);
    expect(blocks[2].markerFrom).toBe(md.indexOf('^cr'));
    expect(blocks[2].markerTo).toBe(md.indexOf('^cr') + 3);
  });

  it('reports offsets in the full text after frontmatter', () => {
    const md = '---\ntitle: t\n---\nText ^z\n';
    const b = find(md, 'z');
    expect(b.markerFrom).toBe(md.indexOf(' ^z'));
    expect(b.from).toBe(md.indexOf('Text'));
  });

  it('keeps offsets in the raw text after plugin syntax', () => {
    const md = '[{TableOfContents}] then ^p1\n';
    const b = find(md, 'p1');
    expect(b.markerFrom).toBe(md.indexOf(' ^p1'));
    expect(b.text).toBe('[{TableOfContents}] then');
  });

  it('skips access-rule-only paragraphs, and their standalone markers look past them', () => {
    expect(blocksFromMarkdown('[{ALLOW view Admin}]\n\nText\n').map((b) => b.text)).toEqual(['Text']);
    expect(blocksFromMarkdown('[{ALLOW view Admin}]()\n[{DENY edit Guest}]\n').length).toBe(0);
    expect(blocksFromMarkdown('[{ALLOW view Admin}]  \n[{DENY edit Guest}]\n').length).toBe(0);
    expect(ids('First.\n\n[{ALLOW view Admin}]\n\n^x\n')).toEqual(['x']);
    expect(ids('[{ALLOW view Admin}]\n\n^x\n\nText\n')).toEqual([null]);
    expect(blocksFromMarkdown('- [{ALLOW view Admin}]\n').map((b) => [b.kind, b.inlineAt])).toEqual([['list-item', -1]]);
    // a rule inside other text is not a rule-only paragraph, and leaves the block's text
    expect(blocksFromMarkdown('Third [{ALLOW edit Admin}] bit.\n').map((b) => b.text)).toEqual(['Third  bit.']);
    expect(blocksFromMarkdown('[Link]()\n').map((b) => b.kind)).toEqual(['paragraph']);
  });

  it('reads access rules and one marker as the standalone marker the render sees once the rules are stripped', () => {
    const md = 'Para.\n\n[{ALLOW view Admin}] ^id\n\nAfter.\n';
    const blocks = blocksFromMarkdown(md);
    expect(blocks.map((b) => [b.id, b.text])).toEqual([['id', 'Para.'], [null, 'After.']]);
    // beside a rule on its line, the marker goes with the blanks before it; on a line of its own it keeps them
    expect([blocks[0].markerFrom, blocks[0].markerTo]).toEqual([md.indexOf(' ^id'), md.indexOf(' ^id') + 4]);
    const own = 'Para.\n\n[{ALLOW view Admin}]\n^id\n';
    expect(blocksFromMarkdown(own)[0].markerFrom).toBe(own.indexOf('^id'));
    expect(ids('Para.\n\n^id [{DENY edit Guest}]\n')).toEqual(['id']);
    expect(ids('Para.\n\n^id\n[{DENY edit Guest}]\n')).toEqual(['id']);
    expect(ids('Para.\n\n[{ALLOW view Admin}]() ^id\n')).toEqual(['id']);
    expect(ids('Para.\n\n[{ALLOW view Admin}] [{DENY edit Guest}] ^id\n')).toEqual(['id']);
    expect(ids('Para.\r\n\r\n[{ALLOW view Admin}] ^id\r\n')).toEqual(['id']);
    expect(blocksFromMarkdown('> [{ALLOW view Admin}] ^q\n\nAfter.\n').map((b) => [b.kind, b.id])).toEqual([['quote', null], ['paragraph', null]]);
    expect(blockMarkers('> [{ALLOW view Admin}] ^q\n')).toEqual([{ id: 'q', caret: 23, wins: true }]);
    expect(blocksFromMarkdown('- item\n- [{ALLOW view Admin}] ^li\n').map((b) => [b.kind, b.id])).toEqual([['list-item', null], ['list-item', null]]);
    // not that form: an escaped caret, two markers, other text, or a marker in emphasis
    expect(ids('Para.\n\n[{ALLOW view Admin}] \\^id\n')).toEqual([null, null]);
    expect(ids('Para.\n\n[{ALLOW view Admin}] ^a\n^b\n')).toEqual([null, 'b']);
    expect(ids('Para.\n\n[{ALLOW view Admin}] text ^id\n')).toEqual([null, 'id']);
    expect(ids('Para.\n\n[{ALLOW view Admin}] *^id*\n')).toEqual([null, null]);
    expect(ids('Para.\n\n^x ^y\n')).toEqual([null, 'y']);
  });

  it('tags the block before a rule-and-marker paragraph, and strips only the marker from the text', () => {
    const md = 'Para.\n\n[{ALLOW view Admin}]\n\nAfter.\n';
    const tagged = insertMarker(md, blocksFromMarkdown(md)[0], 'pp');
    expect(tagged).toBe('Para. ^pp\n\n[{ALLOW view Admin}]\n\nAfter.\n');
    const beside = 'Para.\n\n[{ALLOW view Admin}] ^dup\n\nAfter. ^dup\n';
    expect(blocksFromMarkdown(beside).map((b) => b.id)).toEqual(['dup', null]);
  });

  it('reads a list item of only access rules as the empty item the render sees', () => {
    // "  - " (empty once the rule is stripped) cannot interrupt the paragraph "one": no nested item there
    const md = '- one\n  - [{ALLOW view Admin}]\n  - three\n- four\n';
    const blocks = blocksFromMarkdown(md);
    expect(blocks.map((b) => [b.kind, b.text])).toEqual([['list-item', 'one\nthree'], ['list-item', 'three'], ['list-item', 'four']]);
    expect(blocks[1].from).toBe(md.indexOf('- three'));
    expect(insertMarker(md, blocks[1], 't3')).toBe('- one\n  - [{ALLOW view Admin}]\n  - three ^t3\n- four\n');
    // a blank line after an empty item ends it: the marker below tags the list
    const ended = blocksFromMarkdown('- one\n- [{ALLOW view Admin}]\n\n  ^mk\n\nAfter.\n');
    expect(ended.map((b) => [b.kind, b.id])).toEqual([['list', 'mk'], ['list-item', null], ['list-item', null], ['paragraph', null]]);
    // other text on the item's line keeps it an ordinary item
    expect(blocksFromMarkdown('- one\n  - [{ALLOW view Admin}] x\n').map((b) => b.kind)).toEqual(['list-item', 'list-item']);
  });

  it('gives a block plain text, wikilinks reduced to their display text and embeds dropped', () => {
    expect(find('See [[Foo|Bar]] and **bold** ^w\n', 'w').text).toBe('See Bar and bold');
    expect(blocksFromMarkdown('![[Other]] and [[A#B]]\n')[0].text).toBe('and A > B');
    expect(blocksFromMarkdown('Line one  \nline two\n')[0].text).toBe('Line one\nline two');
    expect(blocksFromMarkdown('- a\n  - b\n')[0].text).toBe('a\nb');
    expect(blocksFromMarkdown(TABLE)[0].text).toBe('a b\n1 2');
    expect(find('![pic](a.png) after ^im\n', 'im').text).toBe('pic after');
    expect(find('Note[^1] ^fn\n\n[^1]: x\n', 'fn').text).toBe('Note');
  });

  it('numbers a standalone marker inside an item as the item, and an orphan in an item tags nothing', () => {
    const item = blocksFromMarkdown('- a\n\n  ^x\n');
    expect(item.map((b) => [b.kind, b.id])).toEqual([['list-item', 'x']]);
    const orphan = blocksFromMarkdown('- ^x\n');
    expect(orphan.map((b) => [b.kind, b.id, b.inlineAt])).toEqual([['list-item', null, -1]]);
  });

  it('starts indented code at its indent', () => {
    const md = 'Intro\n\n    code line\n    more\n';
    const code = blocksFromMarkdown(md)[1];
    expect(code.kind).toBe('code');
    expect(code.from).toBe(md.indexOf('    code'));
    expect(md.slice(code.from, code.to)).toBe('    code line\n    more');
  });
});

describe('insertMarker', () => {
  it('adds an inline marker to a paragraph and a list item', () => {
    const md = '$$\na\n$$\n\nPara\n';
    const blocks = blocksFromMarkdown(md);
    expect(insertMarker(md, blocks[1], 'q1')).toBe('$$\na\n$$\n\nPara ^q1\n');
    expect(insertMarker('- one\n- two\n', blocksFromMarkdown('- one\n- two\n')[1], 'x')).toBe('- one\n- two ^x\n');
    const nested = '- a\n  - child\n- b\n';
    expect(insertMarker(nested, blocksFromMarkdown(nested)[0], 'ai')).toBe('- a ^ai\n  - child\n- b\n');
  });

  it('adds a standalone marker after a block that cannot take an inline one', () => {
    const math = '$$\na\n$$\n\nPara\n';
    expect(insertMarker(math, blocksFromMarkdown(math)[0], 'q2')).toBe('$$\na\n$$\n\n^q2\n\nPara\n');
    expect(insertMarker(TABLE, blocksFromMarkdown(TABLE)[0], 't2')).toBe(`${TABLE}\n^t2\n`);
    const code = '```\nc\n```\nNext\n';
    expect(insertMarker(code, blocksFromMarkdown(code)[0], 'cc')).toBe('```\nc\n```\n\n^cc\n\nNext\n');
    expect(insertMarker('> q', blocksFromMarkdown('> q')[0], 'e1')).toBe('> q\n\n^e1');
    const quoted = '> ```\n> c\n> ```\n';
    const qc = blocksFromMarkdown(quoted).find((b) => b.kind === 'code');
    expect(insertMarker(quoted, qc, 'qc')).toBe('> ```\n> c\n> ```\n>\n> ^qc\n');
    const quotedMore = '> ```\n> c\n> ```\n> after\n';
    const qm = blocksFromMarkdown(quotedMore).find((b) => b.kind === 'code');
    expect(insertMarker(quotedMore, qm, 'qm')).toBe('> ```\n> c\n> ```\n>\n> ^qm\n>\n> after\n');
    const listCode = '- a\n\n      code\n';
    const lc = blocksFromMarkdown(listCode).find((b) => b.kind === 'code');
    expect(insertMarker(listCode, lc, 'lc')).toBe('- a\n\n      code\n\n  ^lc\n');
  });

  it('keeps CRLF line endings', () => {
    const crlf = '| a |\r\n|---|\r\n| 1 |\r\n';
    expect(insertMarker(crlf, blocksFromMarkdown(crlf)[0], 't')).toBe(`${crlf}\r\n^t\r\n`);
    const noEol = '| a |\r\n|---|\r\n| 1 |';
    expect(insertMarker(noEol, blocksFromMarkdown(noEol)[0], 't')).toBe(`${noEol}\r\n\r\n^t`);
  });

  it('replaces the marker of a losing duplicate', () => {
    const md = 'a ^d\n\nb ^d\n';
    expect(insertMarker(md, blocksFromMarkdown(md)[1], 'e')).toBe('a ^d\n\nb ^e\n');
    const inItem = 'Top ^x\n\n- a\n\n  ^x\n';
    const item = blocksFromMarkdown(inItem).find((b) => b.kind === 'list-item');
    expect(insertMarker(inItem, item, 'y')).toBe('Top ^x\n\n- a\n\n  ^y\n');
  });

  it('refuses bad input', () => {
    const md = 'Alpha ^abc\n\nBeta\n';
    const blocks = blocksFromMarkdown(md);
    expect(() => insertMarker(md, blocks[0], 'new')).toThrow(/already has id \^abc/);
    expect(() => insertMarker(md, blocks[1], 'bad id')).toThrow(/Not a block id/);
    expect(() => insertMarker(md, blocks[1], null)).toThrow(/Not a block id/);
    expect(() => insertMarker('Other\n', blocks[1], 'x')).toThrow(/not a current block/);
    expect(() => insertMarker(md, null, 'x')).toThrow(/not a current block/);
    const orphan = '- ^x\n';
    expect(() => insertMarker(orphan, blocksFromMarkdown(orphan)[0], 'y')).toThrow(/no text line/);
  });

  it('tags a paragraph that ends in inline markup', () => {
    const code = 'Use `code`\n';
    expect(insertMarker(code, blocksFromMarkdown(code)[0], 'x')).toBe('Use `code` ^x\n');
    const em = 'Ends with *em*\n';
    expect(insertMarker(em, blocksFromMarkdown(em)[0], 'x')).toBe('Ends with *em* ^x\n');
  });

  it('refuses an edit that does not read back with the block tagged', () => {
    // the id is already taken earlier on the page: the new marker would be a losing duplicate
    const md = 'a ^x\n\nb\n';
    expect(() => insertMarker(md, blocksFromMarkdown(md)[1], 'x')).toThrow(/did not tag it/);
  });
});

describe('newBlockId', () => {
  afterEach(() => { vi.restoreAllMocks(); });

  it('makes six chars of [a-z0-9]', () => {
    for (let i = 0; i < 50; i++) expect(newBlockId(new Set())).toMatch(/^[a-z0-9]{6}$/);
    expect(newBlockId()).toMatch(/^[a-z0-9]{6}$/);
  });

  it('avoids ids already on the page (a Set or an array)', () => {
    const seq = [0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2];
    let n = 0;
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation((arr) => {
      for (let i = 0; i < arr.length; i++) arr[i] = seq[n++];
      return arr;
    });
    expect(newBlockId(new Set(['aaaaaa']))).toBe('bbbbbb');
    n = 0;
    expect(newBlockId(['aaaaaa', 'bbbbbb'])).toBe('cccccc');
  });
});

describe('blockFingerprint', () => {
  it('lowercases and keeps letters and digits, first 200', () => {
    expect(blockFingerprint('Hello, World! 42')).toBe('helloworld42');
    expect(blockFingerprint('Ünïcødé — ok')).toBe('ünïcødéok');
    expect(blockFingerprint('a'.repeat(250))).toHaveLength(200);
    expect(blockFingerprint(null)).toBe('');
    expect(blockFingerprint(undefined)).toBe('');
  });
});

describe('markerChange', () => {
  const apply = (md, c) => md.slice(0, c.from) + c.insert + md.slice(c.to);

  it('is the one edit insertMarker makes: inline, standalone, a duplicate replaced', () => {
    for (const [md, i] of [['a\n\nPara\n', 1], [TABLE, 0], ['a ^d\n\nb ^d\n', 1], ['> q', 0], ['- a\n- b\n', 1]]) {
      const b = blocksFromMarkdown(md)[i];
      const c = markerChange(md, b, 'zz');
      expect(apply(md, c)).toBe(insertMarker(md, b, 'zz'));
    }
    expect(markerChange('Para\n', blocksFromMarkdown('Para\n')[0], 'x')).toEqual({ from: 4, to: 4, insert: ' ^x' });
    expect(markerChange('a ^d\n\nb ^d\n', blocksFromMarkdown('a ^d\n\nb ^d\n')[1], 'e')).toEqual({ from: 8, to: 10, insert: '^e' });
  });

  it('validates as insertMarker does', () => {
    const md = 'a ^abc\n\nb\n';
    expect(() => markerChange(md, blocksFromMarkdown(md)[0], 'n')).toThrow(/already has id/);
    expect(() => markerChange(md, blocksFromMarkdown(md)[1], 'abc')).toThrow(/did not tag it/);
  });
});

describe('blockAt', () => {
  it('is the innermost block whose range holds the position, or null', () => {
    const md = '# H\n\n> quoted para\n\n- item\n\n  more\n';
    expect(blockAt(md, 1)).toBeNull();                       // a heading
    expect(blockAt(md, 4)).toBeNull();                       // the blank line
    expect(blockAt(md, md.indexOf('quoted')).kind).toBe('paragraph');
    expect(blockAt(md, md.indexOf('item') + 2).kind).toBe('list-item');
    expect(blockAt(md, md.indexOf('more')).kind).toBe('paragraph');
    expect(blockAt('', 0)).toBeNull();
  });
});

describe('takenBlockIds', () => {
  it('collects every ^id-looking token (orphans and losers too), so a fresh id never collides', () => {
    expect([...takenBlockIds('a ^one\n\n^two\n\nx^y\n')].sort()).toEqual(['one', 'two', 'y']);
    expect(takenBlockIds('')).toEqual(new Set());
  });
});

describe('blockLinkEdit', () => {
  afterEach(() => { vi.restoreAllMocks(); });

  it('reuses the id of the block at the position, with no edit', () => {
    expect(blockLinkEdit('Para ^abc\n', 2)).toEqual({ id: 'abc', change: null });
  });

  it('gives an id-less block a fresh id that avoids every id on the page', () => {
    vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementationOnce((arr) => arr.fill(0))
      .mockImplementationOnce((arr) => arr.fill(1));
    const md = 'Para\n\nUse `x ^aaaaaa` here\n';   // only a look-alike in code, but still avoided
    expect(blockLinkEdit(md, 1)).toEqual({ id: 'bbbbbb', change: { from: 4, to: 4, insert: ' ^bbbbbb' } });
  });

  it('is null away from a block', () => {
    expect(blockLinkEdit('# Heading\n', 3)).toBeNull();
  });
});

describe('blockMarkers', () => {
  it('lists every marker in source order — block markers, losing duplicates, orphans — and which ones win', () => {
    const md = '---\nt: 1\n---\n^o\n\nA ^o\n\nB ^b\n\n^b\n';
    expect(blockMarkers(md)).toEqual([
      { id: 'o', caret: md.indexOf('^o'), wins: true },
      { id: 'o', caret: md.indexOf('A ^o') + 2, wins: false },
      { id: 'b', caret: md.indexOf('B ^b') + 2, wins: true },
      { id: 'b', caret: md.lastIndexOf('^b'), wins: false },
    ]);
    expect(blockMarkers('')).toEqual([]);
  });
});
