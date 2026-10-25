import { describe, it, expect, vi, afterEach } from 'vitest';
import fixture from '../__fixtures__/renderedFences.json';
import { checkMermaid } from './mermaidPolicy';

afterEach(() => { vi.restoreAllMocks(); });

const flow = (line) => `flowchart TD\n  A-->B\n  ${line}`;

describe('checkMermaid', () => {
  it('has the shared cases', () => {
    expect(fixture.mermaidPolicy.length).toBeGreaterThanOrEqual(14);
  });

  it.each(fixture.mermaidPolicy.map((c) => [JSON.stringify(c.source).slice(0, 90), c]))(
    'matches the shared fixture: %s', (_n, c) => {
      const v = checkMermaid(c.source);
      expect(v.allowed).toBe(c.allowed);
      expect(v.reason).toBe(c.reason);
      expect(new Set(v.pageLinks)).toEqual(new Set(c.pageLinks));
    });

  it('allows null, undefined and empty sources with no links', () => {
    for (const s of [null, undefined, '']) {
      expect(checkMermaid(s)).toEqual({ allowed: true, reason: null, pageLinks: [] });
    }
  });

  it('reports no links for a refused source', () => {
    const v = checkMermaid('flowchart TD\n click A "/wiki/Foo"\n click B call f()');
    expect(v).toEqual({ allowed: false, reason: 'callback', pageLinks: [] });
  });

  it('reports each page link once, in source order', () => {
    const v = checkMermaid(flow('click A "/wiki/Foo"\n  click B "/wiki/Bar?x=1"\n  click C "/wiki/Foo"'));
    expect(v.pageLinks).toEqual(['Foo', 'Bar']);
  });

  it('decodes page names like java.net.URLDecoder, keeping + literal', () => {
    expect(checkMermaid(flow('click A "/wiki/A+B"')).pageLinks).toEqual(['A+B']);
    expect(checkMermaid(flow('click A "/wiki/%C3%A9t%C3%A9"')).pageLinks).toEqual(['\u00E9t\u00E9']);
    expect(checkMermaid(flow('click A "/wiki/%FFx"')).pageLinks).toEqual(['\uFFFDx']);
    expect(checkMermaid(flow('click A "/wiki/%EF%BB%BFA"')).pageLinks).toEqual(['\uFEFFA']);
  });

  // Expected values are what JDK 25's new String(bytes, UTF_8) produces (checked against the Java renderer).
  it.each([
    ['%F0%9F%98%80', '\uD83D\uDE00'],
    ['%ED%A0%80x', '\uFFFDx'],
    ['%E2%82', '\uFFFD'],
    ['%E2%82x', '\uFFFDx'],
    ['%E0%80%80', '\uFFFD\uFFFD\uFFFD'],
    ['%E2x%82', '\uFFFDx\uFFFD'],
    ['%C2', '\uFFFD'],
    ['%C2x', '\uFFFDx'],
    ['%C0%80', '\uFFFD\uFFFD'],
    ['%F0%9F%98', '\uFFFD'],
    ['%F0%9Fx', '\uFFFDx'],
    ['%F0%9F%98x', '\uFFFDx'],
    ['%F0%9Fx%98', '\uFFFDx\uFFFD'],
    ['%F0%80%80%80', '\uFFFD\uFFFD\uFFFD\uFFFD'],
    ['%F4%90%80%80', '\uFFFD\uFFFD\uFFFD\uFFFD'],
    ['%F5%80', '\uFFFD\uFFFD'],
    ['%F0%9F%98%80%F0%9Fx%80', '\uD83D\uDE00\uFFFDx\uFFFD'],
    ['%C2%41', '\uFFFDA'],
    ['%E1%80%41', '\uFFFDA'],
    ['%E1%41', '\uFFFDA'],
    ['%E0%80', '\uFFFD\uFFFD'],
    ['%F0%9F%41', '\uFFFDA'],
    ['%F0%9F%41%80', '\uFFFDA\uFFFD'],
    ['%F5%80%80%80', '\uFFFD\uFFFD\uFFFD\uFFFD'],
    ['A?b#c', 'A'],
    ['A#b?c', 'A'],
  ])('decodes malformed UTF-8 in %s the way Java does', (enc, name) => {
    expect(checkMermaid(flow(`click A "/wiki/${enc}"`)).pageLinks).toEqual([name]);
  });

  it('does not track a page name with broken percent-encoding, a blank name, or a non-wiki path', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    for (const url of ['/wiki/%zz', '/wiki/A%4', '/wiki/%-0x', '/wiki/%\uFF14\uFF41', '/wiki/%\u0664\u0661', '/wiki/', '/wiki/%20', '/wiki/#x', '/other/Foo', 'https://x.org/wiki/Foo']) {
      const v = checkMermaid(flow(`click A "${url}"`));
      expect(v.allowed, url).toBe(true);
      expect(v.pageLinks, url).toEqual([]);
    }
    expect(warn).toHaveBeenCalled();
  });

  it('strips Java whitespace (not NBSP) around a decoded link target', () => {
    expect(checkMermaid(flow('click A "&#x2028/wiki/Foo"')).pageLinks).toEqual(['Foo']);
    expect(checkMermaid(flow('click A "&#xA0/wiki/Foo"'))).toEqual({ allowed: true, reason: null, pageLinks: [] });
  });

  it('decodes entity forms before the scheme check and leaves out-of-range ones alone', () => {
    expect(checkMermaid(flow('click A "javascript&#x3A;x"')).reason).toBe('unsafe-link');
    expect(checkMermaid(flow('click A "javascript#58;x"')).reason).toBe('unsafe-link');
    expect(checkMermaid(flow('click A "javascript&Tab;:x"')).reason).toBe('unsafe-link');
    // hex digits are taken greedily: &#x2FFoo is U+02FF + "oo", so this is not a /wiki/ path
    expect(checkMermaid(flow('click A "&#47wiki&#x2FFoo"')).pageLinks).toEqual([]);
    expect(checkMermaid(flow('click A "&#47wiki&#47Foo"')).pageLinks).toEqual(['Foo']);
    expect(checkMermaid(flow('click A "/wiki/A&#99999999999"')).pageLinks).toEqual(['A&']);
    // an entity's ';' also ends a naive statement piece, which then fails the click rule (Java does the same)
    expect(checkMermaid(flow('click A "&sol;wiki&sol;Foo"')).reason).toBe('callback');
  });

  it('treats a scheme behind a slash as a path, and soft hyphens / zero-width spaces inside a scheme as absent', () => {
    expect(checkMermaid(flow('click A "a/b:c"')).allowed).toBe(true);
    expect(checkMermaid(flow('click A "#frag"')).allowed).toBe(true);
    expect(checkMermaid(flow('click A "da\u00ADta:x"')).reason).toBe('unsafe-link');
  });

  it('matches Java Unicode case folding on click targets', () => {
    expect(checkMermaid(flow('click A "/wiki/Foo" _\u017Felf')).allowed).toBe(true);
    expect(checkMermaid(flow('click A "/wiki/Foo" _bogus')).reason).toBe('callback');
  });

  it('refuses a class-diagram link whose trailing words are not a target', () => {
    expect(checkMermaid('classDiagram\n  class A\n  link A "/wiki/Foo" bogus').reason).toBe('unsafe-link');
  });

  it('leaves astral format characters alone, as Java (which walks UTF-16 units) does', () => {
    expect(checkMermaid('%%\u{E0001}{init: {}}%%\nflowchart TD').allowed).toBe(true);
  });

  it('refuses a JSON links value carrying a backslash escape', () => {
    expect(checkMermaid('sequenceDiagram\n  links Alice: {"D": "\\u0064ata:x"}').reason).toBe('unsafe-link');
    expect(checkMermaid('sequenceDiagram\n  links Alice: {"D": "/wiki/Dash"}').pageLinks).toEqual(['Dash']);
  });

  it('ignores callback lines that are diagram edges, and words glued to punctuation', () => {
    expect(checkMermaid('flowchart TD\n  callback-->B').allowed).toBe(true);
    expect(checkMermaid('flowchart TD\n  click:x').allowed).toBe(true);
  });

  it('refuses render-failed when the analysis itself throws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const evil = { toString() { throw new Error('boom'); } };
    expect(checkMermaid(evil)).toEqual({ allowed: false, reason: 'render-failed', pageLinks: [] });
    expect(warn).toHaveBeenCalled();
  });

  it('stays fast on pathological inputs', () => {
    const sp = ' '.repeat(65536);
    const t0 = performance.now();
    checkMermaid('%%{init:'.repeat(12000));
    for (const s of [`link${sp}x`, `links${sp}`, `click${sp}`, `callback${sp}x`, `sequenceDiagram\nlink${sp}:${sp}@${sp}`]) {
      checkMermaid(s);
    }
    expect(performance.now() - t0).toBeLessThan(1500);
  });
});
