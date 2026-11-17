import { describe, it, expect } from 'vitest';
import { highlightMentions } from './mentionText';

const root = (html) => {
  const el = document.createElement('div');
  el.innerHTML = html;
  return el;
};

describe('highlightMentions', () => {
  it('wraps each mentioned name in a chip, keeping the surrounding text', () => {
    const el = root('<p>hi @Ana A and @Bob B, thanks</p>');
    expect(highlightMentions(el, ['Ana A', 'Bob B'])).toBe(2);
    const chips = [...el.querySelectorAll('.comment-mention-chip')].map((c) => c.textContent);
    expect(chips).toEqual(['@Ana A', '@Bob B']);
    expect(el.textContent).toBe('hi @Ana A and @Bob B, thanks');
  });
  it('does nothing without a root or without usable names', () => {
    expect(highlightMentions(null, ['Ana'])).toBe(0);
    expect(highlightMentions(root('<p>@Ana</p>'), undefined)).toBe(0);
    const el = root('<p>@Ana</p>');
    expect(highlightMentions(el, ['', 5, null])).toBe(0);
    expect(el.querySelector('.comment-mention-chip')).toBeNull();
  });
  it('prefers the longest name and ignores a name that is only a prefix of a longer word', () => {
    const el = root('<p>@Ana Alvarez and @Anabel</p>');
    expect(highlightMentions(el, ['Ana', 'Ana Alvarez'])).toBe(1);
    expect(el.querySelector('.comment-mention-chip').textContent).toBe('@Ana Alvarez');
  });
  it('leaves mentions inside code, pre and links alone', () => {
    const el = root('<code>@Ana</code><pre>@Ana</pre><a href="#x">@Ana</a><p>@Ana</p>');
    expect(highlightMentions(el, ['Ana'])).toBe(1);
    expect(el.querySelectorAll('.comment-mention-chip')).toHaveLength(1);
    expect(el.querySelector('p .comment-mention-chip')).not.toBeNull();
  });
  it('escapes regular-expression characters in a name', () => {
    const el = root('<p>@A.B (x)</p>');
    expect(highlightMentions(el, ['A.B (x)'])).toBe(1);
    expect(highlightMentions(root('<p>@AxB (x)</p>'), ['A.B (x)'])).toBe(0);
  });
});
