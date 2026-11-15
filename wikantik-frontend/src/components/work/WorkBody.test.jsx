import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useState } from 'react';

const spy = vi.hoisted(() => ({ renders: 0 }));
vi.mock('../../utils/math', () => ({ renderMath: vi.fn(() => Promise.resolve()) }));
vi.mock('../../utils/fences/renderFences', () => ({ renderFences: vi.fn(() => Promise.resolve()) }));
vi.mock('./workBodyRenderProbe', () => ({ probe: () => { spy.renders += 1; } }));
import WorkBody from './WorkBody';

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
});
