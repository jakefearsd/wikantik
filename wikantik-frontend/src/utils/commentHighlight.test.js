import { describe, it, expect, beforeEach } from 'vitest';
import { anchorThreads, highlightRange, clearHighlights } from './commentHighlight';

function makeRoot(html) {
  document.body.innerHTML = '';
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

describe('highlightRange', () => {
  it('wraps the ranged text in a comment-highlight mark', () => {
    const root = makeRoot('<p>say hello world</p>');
    const tn = root.querySelector('p').firstChild;
    const range = document.createRange();
    range.setStart(tn, 4);
    range.setEnd(tn, 9); // "hello"
    const marks = highlightRange(range, 'T1');
    expect(marks.length).toBe(1);
    const mark = root.querySelector('mark.comment-highlight');
    expect(mark.textContent).toBe('hello');
    expect(mark.dataset.threadId).toBe('T1');
  });

  it('skips a segment that cannot be surrounded without throwing', () => {
    // A range crossing two element children makes surroundContents throw on the
    // segment that partially selects a non-text node; highlightRange must not blow up.
    const root = makeRoot('<p><b>aaaa</b><i>bbbb</i></p>');
    const b = root.querySelector('b').firstChild;
    const i = root.querySelector('i').firstChild;
    const range = document.createRange();
    range.setStart(b, 1);
    range.setEnd(i, 3);
    expect(() => highlightRange(range, 'T1')).not.toThrow();
    // The full text content must remain intact regardless of which segments wrapped.
    expect(root.querySelector('p').textContent).toBe('aaaabbbb');
  });
});

describe('clearHighlights', () => {
  it('removes comment-highlight marks and restores the original text', () => {
    const root = makeRoot('<p>say hello world</p>');
    const tn = root.querySelector('p').firstChild;
    const range = document.createRange();
    range.setStart(tn, 4);
    range.setEnd(tn, 9); // "hello"
    highlightRange(range, 'T1');
    expect(root.querySelector('mark.comment-highlight')).not.toBeNull();

    clearHighlights(root);
    expect(root.querySelector('mark.comment-highlight')).toBeNull();
    expect(root.querySelector('p').textContent).toBe('say hello world');
    // parent normalized -> single text node
    expect(root.querySelector('p').childNodes.length).toBe(1);
  });
});

describe('anchorThreads', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('highlights an open thread by its exact text', () => {
    const root = makeRoot('<p>The quick brown fox</p>');
    const threads = [{ id: 'T1', status: 'open', anchor: { exact: 'quick brown', prefix: 'The ', suffix: ' fox' } }];
    const { detached } = anchorThreads(root, threads);
    expect(detached).toEqual([]);
    expect(root.querySelector('mark[data-thread-id="T1"]').textContent).toBe('quick brown');
  });

  it('disambiguates between duplicate text using prefix/suffix', () => {
    const root = makeRoot('<p>red cat then red dog</p>');
    const threads = [{ id: 'T1', status: 'open', anchor: { exact: 'red', prefix: 'then ', suffix: ' dog' } }];
    anchorThreads(root, threads);
    const mark = root.querySelector('mark[data-thread-id="T1"]');
    expect(mark.nextSibling.textContent).toContain('dog');
  });

  it('marks a thread detached when its text is gone', () => {
    const root = makeRoot('<p>nothing matches here</p>');
    const threads = [{ id: 'T1', status: 'open', anchor: { exact: 'absent phrase', prefix: '', suffix: '' } }];
    const { detached } = anchorThreads(root, threads);
    expect(detached).toEqual(['T1']);
    expect(root.querySelector('mark')).toBeNull();
  });

  it('does not highlight resolved threads', () => {
    const root = makeRoot('<p>quick brown fox</p>');
    const threads = [{ id: 'T1', status: 'resolved', anchor: { exact: 'quick', prefix: '', suffix: '' } }];
    const { detached } = anchorThreads(root, threads);
    expect(root.querySelector('mark')).toBeNull();
    expect(detached).toEqual([]);
  });
});
