import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { pinFragmentTarget } from './fragmentScroll';

const FRAME_MS = 16;

describe('pinFragmentTarget', () => {
  let root;
  let tops; // id -> current viewport top reported for that element

  function mount(html) {
    root = document.createElement('article');
    root.innerHTML = html;
    document.body.appendChild(root);
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] });
    tops = {};
    Element.prototype.scrollIntoView = vi.fn(function landAtTop() { tops[this.id] = 0; });
    Element.prototype.getBoundingClientRect = vi.fn(function rect() {
      const top = tops[this.id] ?? 0;
      return { top, bottom: top + 20, left: 0, right: 100, width: 100, height: 20, x: 0, y: top };
    });
  });

  afterEach(() => {
    root?.remove();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('scrolls the fragment target to the top of the viewport immediately', () => {
    mount('<h2 id="intro">Intro</h2><h2 id="details">Details</h2>');

    const stop = pinFragmentTarget(root, 'details');

    expect(stop).toBeTypeOf('function');
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    expect(Element.prototype.scrollIntoView.mock.contexts[0].id).toBe('details');
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'start', behavior: 'instant' });
    stop();
  });

  it('returns null and scrolls nothing when the id is not rendered inside root', () => {
    mount('<h2 id="intro">Intro</h2>');
    const outside = document.createElement('h2');
    outside.id = 'elsewhere';
    document.body.appendChild(outside);

    expect(pinFragmentTarget(root, 'missing')).toBeNull();
    expect(pinFragmentTarget(root, 'elsewhere')).toBeNull();
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();
    outside.remove();
  });

  it('re-lands the section when late layout work (a font swap, KaTeX) pushes it away', () => {
    mount('<h2 id="details">Details</h2>');
    const stop = pinFragmentTarget(root, 'details');

    tops.details = 1138; // the article grew above the section after the first scroll
    vi.advanceTimersByTime(FRAME_MS);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(2);
    stop();
  });

  it('leaves a section that stays put alone', () => {
    mount('<h2 id="details">Details</h2>');
    const stop = pinFragmentTarget(root, 'details');

    vi.advanceTimersByTime(FRAME_MS * 10);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    stop();
  });

  it('follows the section by id when a refetch replaces the article DOM', () => {
    mount('<h2 id="details">Details</h2>');
    const stop = pinFragmentTarget(root, 'details');

    root.innerHTML = '<h2 id="details">Details</h2>';
    tops.details = 900;
    vi.advanceTimersByTime(FRAME_MS);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(2);
    expect(Element.prototype.scrollIntoView.mock.contexts[1]).toBe(root.querySelector('#details'));
    stop();
  });

  it.each(['wheel', 'touchstart', 'keydown', 'pointerdown', 'hashchange', 'popstate'])(
    'hands control back at the reader\'s first %s', (type) => {
      mount('<h2 id="details">Details</h2>');
      pinFragmentTarget(root, 'details');

      window.dispatchEvent(new Event(type));
      tops.details = 700;
      vi.advanceTimersByTime(FRAME_MS * 5);

      expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    });

  it('stops after the settle window', () => {
    mount('<h2 id="details">Details</h2>');
    pinFragmentTarget(root, 'details', { settleMs: 1000 });

    vi.advanceTimersByTime(1000);
    tops.details = 700;
    vi.advanceTimersByTime(FRAME_MS * 5);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('stop() ends the pin and removes its input listeners', () => {
    mount('<h2 id="details">Details</h2>');
    const removeSpy = vi.spyOn(window, 'removeEventListener');
    const stop = pinFragmentTarget(root, 'details');

    stop();
    tops.details = 700;
    vi.advanceTimersByTime(FRAME_MS * 5);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    expect(removeSpy.mock.calls.map(([type]) => type).sort())
      .toEqual(['hashchange', 'keydown', 'pointerdown', 'popstate', 'touchstart', 'wheel']);
  });
});
