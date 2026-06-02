import { describe, it, expect } from 'vitest';
import { createElement } from 'react';
import { render } from '@testing-library/react';
import ReactMarkdown from 'react-markdown';
import rehypeSourceLine from './rehypeSourceLine';

const el = (tagName, line, children = []) => ({
  type: 'element',
  tagName,
  position: line == null ? undefined : { start: { line } },
  properties: {},
  children,
});

describe('rehypeSourceLine', () => {
  it('stamps data-line from node.position on each element', () => {
    const tree = { type: 'root', children: [el('p', 3), el('h2', 7, [{ type: 'text', value: 'hi' }])] };
    rehypeSourceLine()(tree);
    expect(tree.children[0].properties['data-line']).toBe(3);
    expect(tree.children[1].properties['data-line']).toBe(7);
  });

  it('recurses into nested children', () => {
    const tree = { type: 'root', children: [el('ul', 2, [el('li', 2), el('li', 3)])] };
    rehypeSourceLine()(tree);
    expect(tree.children[0].children[0].properties['data-line']).toBe(2);
    expect(tree.children[0].children[1].properties['data-line']).toBe(3);
  });

  it('leaves elements without a position untouched', () => {
    const tree = { type: 'root', children: [el('p', null)] };
    rehypeSourceLine()(tree);
    expect(tree.children[0].properties['data-line']).toBeUndefined();
  });

  // Integration: proves react-markdown v9 carries source positions through to
  // the rehype stage, so data-line actually lands on rendered elements (the
  // whole click-to-source feature depends on this).
  it('emits data-line on elements rendered by react-markdown', () => {
    const md = '# Title\n\nParagraph one.\n\nParagraph two.';
    const { container } = render(
      createElement(ReactMarkdown, { rehypePlugins: [rehypeSourceLine] }, md)
    );
    expect(container.querySelectorAll('[data-line]').length).toBeGreaterThan(0);
    expect(container.querySelector('h1')?.getAttribute('data-line')).toBe('1');
    // the second paragraph is on source line 5
    const paras = container.querySelectorAll('p');
    expect(paras[paras.length - 1].getAttribute('data-line')).toBe('5');
  });
});
