import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('katex', () => ({
  default: { render: vi.fn() },
}));
vi.mock('katex/dist/katex.min.css', () => ({}));

import katex from 'katex';
import { renderMath } from './math';

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => { vi.restoreAllMocks(); });

describe('renderMath', () => {
  it('does nothing when container is null', async () => {
    await expect(renderMath(null)).resolves.toBeUndefined();
    expect(katex.render).not.toHaveBeenCalled();
  });

  it('does nothing when container is undefined', async () => {
    await expect(renderMath(undefined)).resolves.toBeUndefined();
    expect(katex.render).not.toHaveBeenCalled();
  });

  it('renders inline math spans with displayMode false and marks them rendered', async () => {
    const container = document.createElement('div');
    const span = document.createElement('span');
    span.className = 'math-inline';
    span.textContent = 'x^2';
    container.appendChild(span);

    await renderMath(container);

    expect(katex.render).toHaveBeenCalledWith('x^2', span, { displayMode: false, throwOnError: false });
    expect(span.classList.contains('math-rendered')).toBe(true);
  });

  it('renders display math divs with displayMode true and marks them rendered', async () => {
    const container = document.createElement('div');
    const div = document.createElement('div');
    div.className = 'math-display';
    div.textContent = '\\sum_{i=0}^n i';
    container.appendChild(div);

    await renderMath(container);

    expect(katex.render).toHaveBeenCalledWith('\\sum_{i=0}^n i', div, { displayMode: true, throwOnError: false });
    expect(div.classList.contains('math-rendered')).toBe(true);
  });

  it('skips elements already marked math-rendered', async () => {
    const container = document.createElement('div');
    const span = document.createElement('span');
    span.className = 'math-inline math-rendered';
    span.textContent = 'x';
    container.appendChild(span);

    await renderMath(container);

    expect(katex.render).not.toHaveBeenCalled();
  });

  it('marks an inline element math-error (not math-rendered) when katex.render throws', async () => {
    katex.render.mockImplementation(() => { throw new Error('bad math'); });
    const container = document.createElement('div');
    const span = document.createElement('span');
    span.className = 'math-inline';
    span.textContent = 'x^';
    container.appendChild(span);

    await renderMath(container);

    expect(span.classList.contains('math-error')).toBe(true);
    expect(span.classList.contains('math-rendered')).toBe(false);
  });

  it('marks a display element math-error when katex.render throws', async () => {
    katex.render.mockImplementation(() => { throw new Error('bad math'); });
    const container = document.createElement('div');
    const div = document.createElement('div');
    div.className = 'math-display';
    div.textContent = '\\bad';
    container.appendChild(div);

    await renderMath(container);

    expect(div.classList.contains('math-error')).toBe(true);
    expect(div.classList.contains('math-rendered')).toBe(false);
  });

  it('does not load KaTeX at all for a container without math', async () => {
    const container = document.createElement('div');
    container.innerHTML = '<p>no math here</p>';
    await renderMath(container);
    expect(katex.render).not.toHaveBeenCalled();
  });

  it('touches nothing when the view went stale while KaTeX was loading', async () => {
    const container = document.createElement('div');
    container.innerHTML = '<span class="math-inline">x</span>';
    await renderMath(container, () => true);
    expect(katex.render).not.toHaveBeenCalled();
    expect(container.querySelector('.math-rendered')).toBeNull();
  });

  it('warns (never swallows silently) when a single expression fails', async () => {
    katex.render.mockImplementation(() => { throw new Error('bad'); });
    const container = document.createElement('div');
    container.innerHTML = '<span class="math-inline">x^</span>';
    await renderMath(container);
    expect(console.warn).toHaveBeenCalled();
  });

  it('renders every matching element independently', async () => {
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

    await renderMath(container);

    expect(katex.render).toHaveBeenCalledTimes(3);
  });
});

describe('renderMath when KaTeX cannot be loaded', () => {
  it('warns and leaves the math as source instead of throwing', async () => {
    vi.resetModules();
    vi.doMock('katex', () => { throw new Error('chunk load failed'); });
    const { renderMath: fresh } = await import('./math');
    const container = document.createElement('div');
    container.innerHTML = '<span class="math-inline">x</span>';
    await expect(fresh(container)).resolves.toBeUndefined();
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('Failed to load KaTeX'), expect.any(Error));
    expect(container.querySelector('.math-rendered')).toBeNull();
    vi.doUnmock('katex');
  });
});
