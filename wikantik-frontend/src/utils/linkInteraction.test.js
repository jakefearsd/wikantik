import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { EditorView } from '@codemirror/view';
import { linkAt, hrefFor, linkInteraction } from './linkInteraction';

const at = (doc, pos) => {
  const state = EditorState.create({ doc, extensions: [markdown()] });
  ensureSyntaxTree(state, doc.length, 5000);
  return linkAt(state, pos);
};

describe('linkAt', () => {
  it('finds the link under the position, in its text or its URL', () => {
    expect(at('see [the hub](IndexFundsHub) now', 7).url).toBe('IndexFundsHub');
    expect(at('see [the hub](IndexFundsHub) now', 16).url).toBe('IndexFundsHub');
    expect(at('go <https://example.com> x', 8).url).toBe('https://example.com');
  });
  it('returns null off a link', () => {
    expect(at('plain text', 3)).toBeNull();
  });
});

describe('hrefFor', () => {
  it('maps wiki targets into the app and keeps external URLs', () => {
    expect(hrefFor('IndexFundsHub')).toBe('/wiki/IndexFundsHub');
    expect(hrefFor('Page#setup')).toBe('/wiki/Page#setup');
    expect(hrefFor('https://example.com/a')).toBe('https://example.com/a');
    expect(hrefFor('javascript:alert(1)')).toBeNull();
    expect(hrefFor('#local')).toBeNull();
  });
});

describe('mod-held class', () => {
  it('is cleared when the window loses focus', () => {
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const view = new EditorView({ parent, doc: 'x', extensions: [linkInteraction({ onHover: () => {} })] });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Control', ctrlKey: true }));
    expect(view.dom.classList.contains('cm-mod-held')).toBe(true);
    window.dispatchEvent(new Event('blur'));
    expect(view.dom.classList.contains('cm-mod-held')).toBe(false);
    view.destroy();
    parent.remove();
  });
});

describe('linkAt on native wikilinks', () => {
  it('finds [[ ]] and ![[ ]] with the same relative url scheme', () => {
    expect(at('see [[Index Funds Hub|hub]] now', 8)).toEqual({ url: 'Index%20Funds%20Hub', from: 4, to: 27 });
    expect(at('x ![[Page#Set Up]] y', 6).url).toBe('Page#set-up');
    expect(at('go [[#Setup]]', 6).url).toBe('#setup');
  });
  it('ignores wikilinks inside inline code and prose with a leading space', () => {
    expect(at('`[[Page]]` x', 4)).toBeNull();
    expect(at('if [[ -f x ]]; then', 6)).toBeNull();
  });
  it('ignores wikilinks inside fenced code', () => {
    expect(at('```\n[[Page]]\n```', 6)).toBeNull();
  });
  it('maps wikilink urls into the app', () => {
    expect(hrefFor('Index%20Funds%20Hub')).toBe('/wiki/Index%20Funds%20Hub');
  });
});

describe('linkRanges marks wikilinks', () => {
  it('marks wikilinks outside code only, alongside markdown links', () => {
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const doc = '[[A]] and [b](B) and `[[C]]`';
    const view = new EditorView({ parent, doc, extensions: [markdown(), linkInteraction({ onHover: () => {} })] });
    ensureSyntaxTree(view.state, doc.length, 5000);
    view.dispatch({ changes: { from: doc.length, insert: ' ' } });
    const marked = [...view.dom.querySelectorAll('.cm-link-range')].map((e) => e.textContent);
    expect(marked).toContain('[[A]]');
    expect(marked).not.toContain('[[C]]');
    view.destroy();
    parent.remove();
  });
});
