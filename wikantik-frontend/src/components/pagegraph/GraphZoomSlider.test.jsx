import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import GraphZoomSlider from './GraphZoomSlider.jsx';
import { SLIDER_MIN_ZOOM, SLIDER_MAX_ZOOM } from './zoom-scale.js';

function makeFakeCy({ zoom = 1, minZoom = 0.1, maxZoom = 4, width = 800, height = 600 } = {}) {
  let currentZoom = zoom;
  const listeners = new Map();
  return {
    zoom: vi.fn((arg) => {
      if (arg === undefined) return currentZoom;
      currentZoom = typeof arg === 'number' ? arg : arg.level;
      (listeners.get('zoom') || []).forEach((cb) => cb());
      return undefined;
    }),
    minZoom: () => minZoom,
    maxZoom: () => maxZoom,
    width: () => width,
    height: () => height,
    on: vi.fn((evt, cb) => {
      if (!listeners.has(evt)) listeners.set(evt, []);
      listeners.get(evt).push(cb);
    }),
    off: vi.fn((evt, cb) => {
      const arr = listeners.get(evt) || [];
      listeners.set(evt, arr.filter((x) => x !== cb));
    }),
    __listeners: listeners,
    __setZoom: (z) => { currentZoom = z; },
  };
}

describe('GraphZoomSlider', () => {
  beforeEach(() => {
    // Run RAF callbacks synchronously so state updates flush in tests.
    vi.stubGlobal('requestAnimationFrame', (cb) => { cb(); return 1; });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
  });

  afterEach(() => {
    delete window.cy;
    vi.unstubAllGlobals();
  });

  it('renders a range input and two zoom buttons', () => {
    const { container, getByTitle } = render(<GraphZoomSlider layoutDone={false} />);
    expect(container.querySelector('input[type="range"]')).toBeTruthy();
    expect(getByTitle('Zoom in')).toBeTruthy();
    expect(getByTitle('Zoom out')).toBeTruthy();
  });

  it('does not subscribe to cy until layout is done', () => {
    const cy = makeFakeCy();
    window.cy = cy;
    render(<GraphZoomSlider layoutDone={false} />);
    expect(cy.on).not.toHaveBeenCalled();
  });

  it('subscribes to zoom events and unsubscribes on unmount', () => {
    const cy = makeFakeCy();
    window.cy = cy;
    const { unmount } = render(<GraphZoomSlider layoutDone={true} />);
    expect(cy.on).toHaveBeenCalledWith('zoom', expect.any(Function));
    unmount();
    expect(cy.off).toHaveBeenCalledWith('zoom', expect.any(Function));
  });

  it('clamps requested zoom to canvas bounds', () => {
    const cy = makeFakeCy({ zoom: 1, minZoom: 0.5, maxZoom: 2 });
    window.cy = cy;
    const { container } = render(<GraphZoomSlider layoutDone={true} />);
    const input = container.querySelector('input[type="range"]');
    fireEvent.change(input, { target: { value: '1' } });
    const call = cy.zoom.mock.calls.find((c) => typeof c[0] === 'object');
    expect(call[0].level).toBeLessThanOrEqual(2);
    expect(call[0].level).toBeGreaterThanOrEqual(0.5);
  });

  it('anchors the zoom at the canvas center', () => {
    const cy = makeFakeCy({ width: 1000, height: 400 });
    window.cy = cy;
    const { container } = render(<GraphZoomSlider layoutDone={true} />);
    fireEvent.change(container.querySelector('input[type="range"]'), { target: { value: '0.5' } });
    const call = cy.zoom.mock.calls.find((c) => typeof c[0] === 'object');
    expect(call[0].renderedPosition).toEqual({ x: 500, y: 200 });
  });

  it('nudges zoom in by a linear step when + is clicked', () => {
    const cy = makeFakeCy({ zoom: 1 });
    window.cy = cy;
    const { getByTitle } = render(<GraphZoomSlider layoutDone={true} />);
    fireEvent.click(getByTitle('Zoom in'));
    const call = cy.zoom.mock.calls.find((c) => typeof c[0] === 'object');
    expect(call[0].level).toBeCloseTo(1.2);
  });

  it('nudges zoom out by a linear step when - is clicked', () => {
    const cy = makeFakeCy({ zoom: 1 });
    window.cy = cy;
    const { getByTitle } = render(<GraphZoomSlider layoutDone={true} />);
    fireEvent.click(getByTitle('Zoom out'));
    const call = cy.zoom.mock.calls.find((c) => typeof c[0] === 'object');
    expect(call[0].level).toBeCloseTo(0.8);
  });

  it('is a no-op when cy is unavailable', () => {
    window.cy = null;
    const { getByTitle } = render(<GraphZoomSlider layoutDone={true} />);
    expect(() => fireEvent.click(getByTitle('Zoom in'))).not.toThrow();
  });

  it('reflects the current zoom as a slider position', () => {
    const mid = Math.sqrt(SLIDER_MIN_ZOOM * SLIDER_MAX_ZOOM);
    const cy = makeFakeCy({ zoom: mid });
    window.cy = cy;
    const { container } = render(<GraphZoomSlider layoutDone={true} />);
    const input = container.querySelector('input[type="range"]');
    expect(parseFloat(input.value)).toBeCloseTo(0.5, 2);
  });
});
