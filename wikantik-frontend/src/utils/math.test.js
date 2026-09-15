import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('katex', () => ({
  default: { render: vi.fn() },
}));

import katex from 'katex';
import { renderMath } from './math';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('renderMath', () => {
  it('does nothing when container is null', () => {
    expect(() => renderMath(null)).not.toThrow();
    expect(katex.render).not.toHaveBeenCalled();
  });

  it('does nothing when container is undefined', () => {
    expect(() => renderMath(undefined)).not.toThrow();
    expect(katex.render).not.toHaveBeenCalled();
  });

  it('renders inline math spans with displayMode false and marks them rendered', () => {
    const container = document.createElement('div');
    const span = document.createElement('span');
    span.className = 'math-inline';
    span.textContent = 'x^2';
    container.appendChild(span);

    renderMath(container);

    expect(katex.render).toHaveBeenCalledWith('x^2', span, { displayMode: false, throwOnError: false });
    expect(span.classList.contains('math-rendered')).toBe(true);
  });

  it('renders display math divs with displayMode true and marks them rendered', () => {
    const container = document.createElement('div');
    const div = document.createElement('div');
    div.className = 'math-display';
    div.textContent = '\\sum_{i=0}^n i';
    container.appendChild(div);

    renderMath(container);

    expect(katex.render).toHaveBeenCalledWith('\\sum_{i=0}^n i', div, { displayMode: true, throwOnError: false });
    expect(div.classList.contains('math-rendered')).toBe(true);
  });

  it('skips elements already marked math-rendered', () => {
    const container = document.createElement('div');
    const span = document.createElement('span');
    span.className = 'math-inline math-rendered';
    span.textContent = 'x';
    container.appendChild(span);

    renderMath(container);

    expect(katex.render).not.toHaveBeenCalled();
  });

  it('marks an inline element math-error (not math-rendered) when katex.render throws', () => {
    katex.render.mockImplementation(() => { throw new Error('bad math'); });
    const container = document.createElement('div');
    const span = document.createElement('span');
    span.className = 'math-inline';
    span.textContent = 'x^';
    container.appendChild(span);

    renderMath(container);

    expect(span.classList.contains('math-error')).toBe(true);
    expect(span.classList.contains('math-rendered')).toBe(false);
  });

  it('marks a display element math-error when katex.render throws', () => {
    katex.render.mockImplementation(() => { throw new Error('bad math'); });
    const container = document.createElement('div');
    const div = document.createElement('div');
    div.className = 'math-display';
    div.textContent = '\\bad';
    container.appendChild(div);

    renderMath(container);

    expect(div.classList.contains('math-error')).toBe(true);
    expect(div.classList.contains('math-rendered')).toBe(false);
  });

  it('renders every matching element independently', () => {
    const container = document.createElement('div');
    const a = document.createElement('span');
    a.className = 'math-inline';
    a.textContent = 'a';
    const b = document.createElement('span');
    b.className = 'math-inline';
    b.textContent = 'b';
    const c = document.createElement('div');
    c.className = 'math-display';
    c.textContent = 'c';
    container.append(a, b, c);

    renderMath(container);

    expect(katex.render).toHaveBeenCalledTimes(3);
  });
});
