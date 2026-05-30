import { describe, it, expect } from 'vitest';
import { highlightTerms } from './highlight';
import React from 'react';
import { render } from '@testing-library/react';

// Helper: render the highlight output to a DOM string for easy assertion
function renderHighlight(text, query) {
  const nodes = highlightTerms(text, query);
  const { container } = render(React.createElement(React.Fragment, null, ...nodes));
  return container;
}

describe('highlightTerms', () => {
  it('returns a single text node when query is empty', () => {
    const nodes = highlightTerms('hello world', '');
    expect(nodes).toHaveLength(1);
    expect(nodes[0]).toBe('hello world');
  });

  it('returns a single text node when text is empty', () => {
    const nodes = highlightTerms('', 'foo');
    expect(nodes).toHaveLength(1);
    expect(nodes[0]).toBe('');
  });

  it('wraps a single match in <mark>', () => {
    const container = renderHighlight('hello world', 'hello');
    const marks = container.querySelectorAll('mark');
    expect(marks).toHaveLength(1);
    expect(marks[0].textContent).toBe('hello');
  });

  it('is case-insensitive', () => {
    const container = renderHighlight('Hello World', 'hello');
    const marks = container.querySelectorAll('mark');
    expect(marks).toHaveLength(1);
    expect(marks[0].textContent).toBe('Hello');
  });

  it('highlights multiple terms', () => {
    const container = renderHighlight('foo bar baz', 'foo baz');
    const marks = container.querySelectorAll('mark');
    expect(marks).toHaveLength(2);
    expect(marks[0].textContent).toBe('foo');
    expect(marks[1].textContent).toBe('baz');
  });

  it('leaves non-matching text unchanged', () => {
    const container = renderHighlight('hello world', 'xyz');
    expect(container.querySelectorAll('mark')).toHaveLength(0);
    expect(container.textContent).toBe('hello world');
  });

  it('escapes regex special characters in query (e.g. "c++")', () => {
    // Should not throw and should still match literally
    const container = renderHighlight('version c++', 'c++');
    const marks = container.querySelectorAll('mark');
    expect(marks).toHaveLength(1);
    expect(marks[0].textContent).toBe('c++');
  });

  it('escapes dot, parens, brackets', () => {
    expect(() => renderHighlight('file.txt and (foo)', '.')).not.toThrow();
    const container = renderHighlight('file.txt', '.');
    const marks = container.querySelectorAll('mark');
    // '.' should only match literal '.'
    expect(marks[0]?.textContent).toBe('.');
  });

  it('handles multiple occurrences of the same term', () => {
    const container = renderHighlight('the cat sat on the mat', 'the');
    const marks = container.querySelectorAll('mark');
    expect(marks).toHaveLength(2);
  });

  it('returns React nodes (not raw HTML strings)', () => {
    const nodes = highlightTerms('hello world', 'hello');
    // At least one node should be a React element (object), not a string
    expect(nodes.some((n) => typeof n === 'object' && n !== null)).toBe(true);
  });

  it('handles null/undefined gracefully', () => {
    expect(() => highlightTerms(null, 'foo')).not.toThrow();
    expect(() => highlightTerms('foo', null)).not.toThrow();
    const nodes = highlightTerms(null, 'foo');
    expect(nodes).toHaveLength(1);
  });
});
