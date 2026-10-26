import { describe, it, expect, vi, afterEach } from 'vitest';

vi.mock('../fences/drawFence', () => ({ drawFence: vi.fn() }));
import { drawFence } from '../fences/drawFence';
import { FenceWidget, widgetFor } from './widgets';

afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); drawFence.mockReset(); });

const flush = async () => { for (let i = 0; i < 6; i += 1) await Promise.resolve(); await new Promise((r) => setTimeout(r, 0)); };

describe('FenceWidget (server-kind and failure paths)', () => {
  it('buildsAServerKindEnvelopeForPlantuml', async () => {
    drawFence.mockResolvedValue('drawn');
    const dom = new FenceWidget('plantuml', 'A -> B').toDOM(undefined);
    const figure = dom.querySelector('figure.wk-fence[data-fence="plantuml"][data-fence-kind="server"]');
    expect(figure).not.toBeNull();
    expect(figure.querySelector('code.language-plantuml').textContent).toBe('A -> B');
    expect(dom.className).toContain('cm-lp-fence-block');
    await flush(); // let the lazy drawFence import settle inside this test
  });

  it('staleMeansDetachedFromTheDocument', async () => {
    drawFence.mockResolvedValue('drawn');
    const dom = new FenceWidget('plantuml', 'A -> B').toDOM(undefined);
    await flush();
    const isStale = drawFence.mock.calls[0][1];
    expect(isStale()).toBe(true); // never attached
    document.body.append(dom);
    expect(isStale()).toBe(false);
    dom.remove();
    expect(isStale()).toBe(true);
  });

  it('asksTheViewToRemeasureOnceTheDrawingSettles', async () => {
    drawFence.mockResolvedValue('drawn');
    const view = { requestMeasure: vi.fn() };
    new FenceWidget('plantuml', 'A -> B').toDOM(view);
    await flush();
    expect(view.requestMeasure).toHaveBeenCalledTimes(1);
  });

  it('aViewWithoutRequestMeasureIsTolerated', async () => {
    drawFence.mockResolvedValue('drawn');
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    new FenceWidget('plantuml', 'A -> B').toDOM({});
    await flush();
    expect(warn).not.toHaveBeenCalled();
  });

  it('aFailedDrawIsWarnedAboutWithTheLanguageAndMessage', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    drawFence.mockRejectedValue(new Error('server said no'));
    const view = { requestMeasure: vi.fn() };
    new FenceWidget('plantuml', 'A -> B').toDOM(view);
    await flush();
    expect(warn).toHaveBeenCalledWith('[live-preview] fence draw failed', 'plantuml', 'server said no');
    expect(view.requestMeasure).not.toHaveBeenCalled();
  });

  it('aNonErrorRejectionIsWarnedAboutAsIs', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    drawFence.mockRejectedValue('plain string');
    new FenceWidget('plantuml', 'A -> B').toDOM(undefined);
    await flush();
    expect(warn).toHaveBeenCalledWith('[live-preview] fence draw failed', 'plantuml', 'plain string');
  });

  it('mouseDownRevealsTheSourceThroughTheView', () => {
    drawFence.mockResolvedValue('drawn');
    const view = { requestMeasure: vi.fn(), dispatch: vi.fn(), posAtDOM: vi.fn(() => 17), focus: vi.fn() };
    const dom = new FenceWidget('plantuml', 'A -> B').toDOM(view);
    document.body.append(dom);
    dom.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    expect(view.dispatch).toHaveBeenCalledWith({ selection: { anchor: 17 } });
    expect(view.focus).toHaveBeenCalled();
  });

  it('widgetForBuildsAPlantumlFenceWidgetAndIgnoresEditorEvents', () => {
    const w = widgetFor({ type: 'fence', language: 'plantuml', source: 'x' });
    expect(w).toBeInstanceOf(FenceWidget);
    expect(w.ignoreEvent()).toBe(true);
    expect(w.estimatedHeight).toBe(200);
    expect(w.eq(new FenceWidget('plantuml', 'x'))).toBe(true);
    expect(w.eq({})).toBe(false);
  });
});
