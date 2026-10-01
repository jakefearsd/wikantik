import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { linkAt, hrefFor } from './linkInteraction';

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
