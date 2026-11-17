import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { useState } from 'react';

const spy = vi.hoisted(() => ({ renders: 0 }));
vi.mock('../../utils/math', () => ({ renderMath: vi.fn(() => Promise.resolve()) }));
vi.mock('../../utils/fences/renderFences', () => ({ renderFences: vi.fn(() => Promise.resolve()) }));
vi.mock('./workBodyRenderProbe', () => ({ probe: () => { spy.renders += 1; } }));
import WorkBody from './WorkBody';
import { renderMath } from '../../utils/math';
import { renderFences } from '../../utils/fences/renderFences';

function Parent({ html }) {
  const [n, setN] = useState(0);
  return (<div><button onClick={() => setN(n + 1)}>tick {n}</button><WorkBody html={html} /></div>);
}

describe('WorkBody', () => {
  it('does not render again when only the parent re-renders', () => {
    render(<Parent html="<p>hello</p>" />);
    const before = spy.renders;
    expect(before).toBe(1);
    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByRole('button'));
    expect(spy.renders).toBe(before);
    expect(screen.getByText('hello')).toBeInTheDocument();
  });

  it('renders again when the HTML changes', () => {
    const { rerender } = render(<WorkBody html="<p>one</p>" />);
    const before = spy.renders;
    rerender(<WorkBody html="<p>two</p>" />);
    expect(spy.renders).toBe(before + 1);
  });

  it('says there is no description when the HTML is empty', () => {
    render(<WorkBody html="" />);
    expect(screen.getByText('No description.')).toBeInTheDocument();
  });

  it('shows the caller\'s own empty text, or nothing at all when it is blank', () => {
    const { container, rerender } = render(<WorkBody html="" empty="Nothing here yet." />);
    expect(screen.getByText('Nothing here yet.')).toBeInTheDocument();
    rerender(<WorkBody html="" empty="" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('uses the given class name for the rendered body', () => {
    const { container } = render(<WorkBody html="<p>hi</p>" className="custom-body" />);
    expect(container.querySelector('div.custom-body')).not.toBeNull();
  });

  it('marks mentioned names as chips and re-marks them when the names change', () => {
    const { container, rerender } = render(<WorkBody html="<p>ping @Ana Alvarez and @Bob Brown</p>" mentions={['Ana Alvarez']} />);
    expect([...container.querySelectorAll('.comment-mention-chip')].map((c) => c.textContent)).toEqual(['@Ana Alvarez']);
    rerender(<WorkBody html="<p>ping @Ana Alvarez and @Bob Brown</p>" mentions={['Ana Alvarez', 'Bob Brown']} />);
    expect(container.querySelectorAll('.comment-mention-chip').length).toBeGreaterThanOrEqual(1);
  });

  it('does not draw math or diagrams into an empty body', () => {
    renderMath.mockClear();
    renderFences.mockClear();
    render(<WorkBody html="" />);
    expect(renderMath).not.toHaveBeenCalled();
    expect(renderFences).not.toHaveBeenCalled();
  });

  it('logs and keeps the text when math or diagram rendering fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderMath.mockImplementationOnce(() => Promise.reject(new Error('math down')));
    renderFences.mockImplementationOnce(() => Promise.reject(new Error('fence down')));
    render(<WorkBody html="<p>still shown</p>" />);
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkBody: math rendering failed', expect.any(Error)));
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkBody: diagram rendering failed', expect.any(Error)));
    expect(screen.getByText('still shown')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('tells the renderers a drawing is stale once the body unmounts', () => {
    renderMath.mockClear();
    const { unmount } = render(<WorkBody html="<p>x</p>" />);
    const isStale = renderMath.mock.calls[0][1];
    expect(isStale()).toBe(false);
    unmount();
    expect(isStale()).toBe(true);
  });
});
