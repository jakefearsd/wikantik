import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { useRef } from 'react';

vi.mock('./usePagePreview', () => ({ loadPreview: vi.fn() }));
import { loadPreview } from './usePagePreview';
import { useLinkPreview, previewTargetOf } from './useLinkPreview';

function Host() {
  const ref = useRef(null);
  const { card } = useLinkPreview(ref);
  return (
    <>
      <div ref={ref}>
        <a href="/wiki/IndexFundsHub">hub</a>
        <a className="createpage" href="/wiki/Nope">nope</a>
        <a className="createpage" href="/edit/Nope%20Page">nope2</a>
        <a href="/edit/Plain">plainedit</a>
        <a href="https://x.example/">ext</a>
        <a href="/attach/P/f.png">att</a>
        <a href="#sec">anchor</a>
      </div>
      {card}
    </>
  );
}

const anchor = (href) => { const a = document.createElement('a'); a.setAttribute('href', href); return a; };
const advance = (ms) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

describe('useLinkPreview', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    loadPreview.mockResolvedValue({ status: 'ok', data: { title: 'Hub', type: 'hub', cluster: 'finance', summary: 'About index funds.' } });
    Element.prototype.getBoundingClientRect = () => ({ top: 0, left: 0, bottom: 10, right: 10, width: 10, height: 10 });
  });
  afterEach(() => { vi.useRealTimers(); });

  it('shows the card 400 ms after hovering an internal link', async () => {
    render(<Host />);
    fireEvent.mouseOver(screen.getByText('hub'));
    await advance(399);
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
    await advance(1);
    const card = screen.getByTestId('link-preview-card');
    expect(card).toHaveTextContent('Hub');
    expect(card).toHaveTextContent('About index funds.');
    expect(loadPreview).toHaveBeenCalledWith('IndexFundsHub', null, expect.anything());
  });

  it('keeps the card while the pointer moves into it within 200 ms, closes after leaving both', async () => {
    render(<Host />);
    const link = screen.getByText('hub');
    fireEvent.mouseOver(link);
    await advance(400);
    fireEvent.mouseOut(link);
    await advance(100);
    fireEvent.mouseEnter(screen.getByTestId('link-preview-card'));
    await advance(500);
    expect(screen.getByTestId('link-preview-card')).toBeInTheDocument();
    fireEvent.mouseLeave(screen.getByTestId('link-preview-card'));
    await advance(199);
    expect(screen.getByTestId('link-preview-card')).toBeInTheDocument();
    await advance(1);
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
  });

  it('shows "Not created yet" for a createpage link without fetching', async () => {
    render(<Host />);
    fireEvent.mouseOver(screen.getByText('nope'));
    await advance(400);
    expect(screen.getByTestId('link-preview-card')).toHaveTextContent('Not created yet');
    expect(loadPreview).not.toHaveBeenCalled();
  });

  it('shows "Not created yet" for a server createpage /edit/ link without fetching', async () => {
    render(<Host />);
    fireEvent.mouseOver(screen.getByText('nope2'));
    await advance(400);
    expect(screen.getByTestId('link-preview-card')).toHaveTextContent('Not created yet');
    expect(loadPreview).not.toHaveBeenCalled();
  });

  it('gives a plain /edit/ link (no createpage class) no card', async () => {
    render(<Host />);
    fireEvent.mouseOver(screen.getByText('plainedit'));
    await advance(500);
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
    expect(previewTargetOf(anchor('/edit/Plain'))).toBeNull();
  });

  it('decodes the /edit/ name of a createpage anchor', () => {
    const a = anchor('/edit/Nope%20Page'); a.className = 'createpage';
    expect(previewTargetOf(a)).toEqual({ name: 'Nope Page', section: null, missing: true });
  });

  it('ignores external, attachment and same-page anchors', async () => {
    render(<Host />);
    for (const t of ['ext', 'att', 'anchor']) fireEvent.mouseOver(screen.getByText(t));
    await advance(500);
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
    expect(loadPreview).not.toHaveBeenCalled();
  });

  it('opens on keyboard focus and closes on Escape', async () => {
    render(<Host />);
    fireEvent.focusIn(screen.getByText('hub'));
    await advance(400);
    expect(screen.getByTestId('link-preview-card')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
  });

  it('closes on scroll', async () => {
    render(<Host />);
    fireEvent.mouseOver(screen.getByText('hub'));
    await advance(400);
    expect(screen.getByTestId('link-preview-card')).toBeInTheDocument();
    fireEvent.scroll(window);
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
  });

  it('a pointer that leaves before the open delay never shows the card or fetches', async () => {
    render(<Host />);
    const link = screen.getByText('hub');
    fireEvent.mouseOver(link);
    await advance(250);
    fireEvent.mouseOut(link);
    await advance(300);
    expect(screen.queryByTestId('link-preview-card')).toBeNull();
    expect(loadPreview).not.toHaveBeenCalled();
  });

  it('re-entering the anchor already showing does not reload the card', async () => {
    render(<Host />);
    const link = screen.getByText('hub');
    fireEvent.mouseOver(link);
    await advance(400);
    expect(loadPreview).toHaveBeenCalledTimes(1);
    fireEvent.mouseOut(link);
    fireEvent.mouseOver(link);
    await advance(600);
    expect(loadPreview).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('link-preview-card')).toHaveTextContent('About index funds.');
  });

  it('extracts the section from a hash', () => {
    expect(previewTargetOf(anchor('/wiki/Page#usage'))).toEqual({ name: 'Page', section: 'usage', missing: false });
    expect(previewTargetOf(anchor('Page#usage'))).toEqual({ name: 'Page', section: 'usage', missing: false });
  });
});
