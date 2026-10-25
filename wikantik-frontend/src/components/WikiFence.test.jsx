import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';

vi.mock('../utils/fences/drawFence', () => ({ drawFence: vi.fn(() => Promise.resolve('drawn')) }));
import { drawFence } from '../utils/fences/drawFence';
import WikiFence from './WikiFence';

describe('WikiFence', () => {
  beforeEach(() => { drawFence.mockClear(); });

  it('drawsOncePerSource', () => {
    const { rerender, container } = render(<WikiFence language="mermaid" source="flowchart TD" />);
    expect(drawFence).toHaveBeenCalledTimes(1);
    rerender(<WikiFence language="mermaid" source="flowchart TD" />);
    expect(drawFence).toHaveBeenCalledTimes(1);
    const figure = container.querySelector('figure.wk-fence[data-fence="mermaid"][data-fence-kind="browser"]');
    expect(figure.querySelector('pre.wk-fence-source > code.language-mermaid').textContent).toBe('flowchart TD');
  });

  it('redrawsWhenSourceChanges', () => {
    const { rerender, container } = render(<WikiFence language="mermaid" source="a" />);
    rerender(<WikiFence language="mermaid" source="b" />);
    expect(drawFence).toHaveBeenCalledTimes(2);
    expect(container.querySelector('code').textContent).toBe('b');
  });

  it('gives the source a copy button, as the reader view does', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    try {
      const { container, rerender } = render(<WikiFence language="mermaid" source={'flowchart TD\n A-->B'} />);
      const buttons = () => container.querySelectorAll('pre.wk-fence-source > button.code-copy-btn');
      expect(buttons()).toHaveLength(1);
      buttons()[0].click();
      expect(writeText).toHaveBeenCalledWith('flowchart TD\n A-->B');
      rerender(<WikiFence language="mermaid" source="b" />);
      expect(buttons()).toHaveLength(1);
      // The copy button never leaks into the source the drawing is made from.
      expect(container.querySelector('pre.wk-fence-source > code').textContent).toBe('b');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('marks a superseded draw stale', () => {
    const { rerender } = render(<WikiFence language="mermaid" source="a" />);
    const firstStale = drawFence.mock.calls[0][1];
    expect(firstStale()).toBe(false);
    rerender(<WikiFence language="mermaid" source="b" />);
    expect(firstStale()).toBe(true);
  });

  it('serverKindKeepsLastDrawingWhilePending', async () => {
    let settle;
    drawFence.mockImplementationOnce((figure) => {
      const host = document.createElement('div');
      host.className = 'wk-fence-drawing';
      host.id = 'first';
      figure.insertBefore(host, figure.querySelector('pre'));
      return Promise.resolve('drawn');
    });
    drawFence.mockImplementationOnce((figure) => new Promise((resolve) => {
      settle = () => {
        const host = document.createElement('div');
        host.className = 'wk-fence-drawing';
        host.id = 'second';
        figure.insertBefore(host, figure.querySelector('pre'));
        resolve('drawn');
      };
    }));
    const { rerender, container } = render(<WikiFence language="plantuml" source="a" />);
    await Promise.resolve();
    expect(container.querySelector('#first')).not.toBeNull();
    rerender(<WikiFence language="plantuml" source="b" />);
    const kept = container.querySelector('#first');
    expect(kept).not.toBeNull();
    expect(kept.hasAttribute('data-fence-pending')).toBe(true);
    expect(container.querySelector('figure').dataset.fenceKind).toBe('server');
    await act(async () => { settle(); });
    expect(container.querySelector('#first')).toBeNull();
    expect(container.querySelector('#second')).not.toBeNull();
  });

  it('browserKindDropsTheOldDrawingImmediately', async () => {
    drawFence.mockImplementationOnce((figure) => {
      const host = document.createElement('div');
      host.className = 'wk-fence-drawing';
      host.id = 'first';
      figure.insertBefore(host, figure.querySelector('pre'));
      return Promise.resolve('drawn');
    });
    const { rerender, container } = render(<WikiFence language="mermaid" source="a" />);
    rerender(<WikiFence language="mermaid" source="b" />);
    expect(container.querySelector('#first')).toBeNull();
  });
});
