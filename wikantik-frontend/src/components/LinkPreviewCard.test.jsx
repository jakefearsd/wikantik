import { describe, it, expect, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import LinkPreviewCard from './LinkPreviewCard';

const rect = { bottom: 10, left: 20 };

describe('LinkPreviewCard', () => {
  it('renders title, type, cluster and summary', () => {
    render(<LinkPreviewCard rect={rect} result={{ status: 'ok', data: { title: 'Hub', type: 'hub', cluster: 'finance', summary: 'About funds.', excerpt: 'ex' } }} />);
    const card = screen.getByTestId('link-preview-card');
    expect(card).toHaveTextContent('Hub');
    expect(card).toHaveTextContent('hub');
    expect(card).toHaveTextContent('finance');
    expect(card).toHaveTextContent('About funds.');
    expect(card).not.toHaveTextContent('ex');
  });
  it('falls back to the excerpt without a summary', () => {
    render(<LinkPreviewCard rect={rect} result={{ status: 'ok', data: { title: 'T', excerpt: 'An excerpt.' } }} />);
    expect(screen.getByTestId('link-preview-card')).toHaveTextContent('An excerpt.');
  });
  it('shows a loading line while pending', () => {
    render(<LinkPreviewCard rect={rect} result={null} />);
    expect(screen.getByTestId('link-preview-card')).toHaveTextContent('Loading');
  });
  it('shows Not created yet for missing targets', () => {
    render(<LinkPreviewCard rect={rect} result={{ status: 'missing' }} />);
    expect(screen.getByTestId('link-preview-card')).toHaveTextContent('Not created yet');
  });
  it('renders markup as literal text', () => {
    render(<LinkPreviewCard rect={rect} result={{ status: 'ok', data: { title: 'T', summary: 'a <b>bold</b> move' } }} />);
    const card = screen.getByTestId('link-preview-card');
    expect(card.querySelector('b')).toBeNull();
    expect(card).toHaveTextContent('a <b>bold</b> move');
  });

  describe('viewport clamping', () => {
    const saved = { w: window.innerWidth, h: window.innerHeight };
    afterEach(() => { window.innerWidth = saved.w; window.innerHeight = saved.h; });

    it('keeps a card for a link near the bottom-right corner inside the viewport', () => {
      window.innerWidth = 800;
      window.innerHeight = 600;
      render(<LinkPreviewCard rect={{ left: 780, top: 570, bottom: 590 }} result={{ status: 'missing' }} />);
      const card = screen.getByTestId('link-preview-card');
      expect(parseInt(card.style.left, 10)).toBeLessThanOrEqual(800 - 8 - 352);
      expect(parseInt(card.style.top, 10)).toBeLessThan(570); // flipped above the link
    });
  });
});
