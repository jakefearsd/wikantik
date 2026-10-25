import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

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
});
