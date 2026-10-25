import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';

vi.mock('../utils/fences/mermaidRenderer.js', () => ({
  draw: vi.fn(async (host, source) => {
    host.innerHTML = `<svg viewBox="0 0 100 100"><title>${source.trim().replace(/[<&]/g, '')}</title></svg>`;
  }),
}));
import * as rendererModule from '../utils/fences/mermaidRenderer.js';
import { FENCE_RENDERERS } from '../utils/fences/registry';
import WikiFence from './WikiFence';

beforeEach(() => {
  vi.spyOn(FENCE_RENDERERS.mermaid, 'load').mockImplementation(async () => rendererModule);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => { vi.restoreAllMocks(); });

const drawnTitle = (c) => c.querySelector('.wk-fence-drawing svg title')?.textContent;

describe('WikiFence with the real drawFence', () => {
  it('draws the second source after an edit (stale state is reset)', async () => {
    const { rerender, container } = render(<WikiFence language="mermaid" source="graph A" />);
    await waitFor(() => expect(drawnTitle(container)).toBe('graph A'));
    rerender(<WikiFence language="mermaid" source="graph B" />);
    await waitFor(() => expect(drawnTitle(container)).toBe('graph B'));
    const figure = container.querySelector('figure');
    expect(figure.dataset.fenceState).toBe('drawn');
    expect(figure.querySelectorAll('.wk-fence-drawing')).toHaveLength(1);
  });

  it('draws once a refused source is fixed', async () => {
    const { rerender, container } = render(<WikiFence language="mermaid" source={'%%{init: {}}%%\ngraph A'} />);
    await waitFor(() => expect(container.querySelector('figure').dataset.fenceState).toBe('refused'));
    expect(container.querySelector('.wk-fence-error')).not.toBeNull();
    rerender(<WikiFence language="mermaid" source="graph A" />);
    await waitFor(() => expect(drawnTitle(container)).toBe('graph A'));
    expect(container.querySelector('.wk-fence-error')).toBeNull();
  });
});
