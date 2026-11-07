import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import DraftBanner from './DraftBanner.jsx';

describe('DraftBanner', () => {
  afterEach(() => { vi.useRealTimers(); });

  const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString();

  it('theBannerNamesContributorsAndYou', () => {
    const draft = { unpublished: true, contributors: ['Ana', 'Raj'], lastEditAt: hoursAgo(2) };
    const { rerender } = render(<DraftBanner draft={draft} selfName="Kim" />);
    const banner = screen.getByTestId('collab-draft-banner');
    expect(banner.textContent).toContain('Unpublished changes by Ana and Raj');
    expect(banner.textContent).toContain('last edit 2h ago');
    rerender(<DraftBanner draft={{ ...draft, contributors: ['Ana', 'Kim'] }} selfName="Kim" />);
    expect(screen.getByTestId('collab-draft-banner').textContent).toContain('Unpublished changes by Ana and you');
  });

  it('the live region announces the contributors, not the ticking age', () => {
    const draft = { unpublished: true, contributors: ['Ana', 'Raj'], lastEditAt: hoursAgo(2) };
    const { rerender } = render(<DraftBanner draft={draft} selfName="Kim" onPublish={() => {}} />);
    const live = screen.getByRole('status');
    expect(live.textContent).toBe('Unpublished changes by Ana and Raj');
    expect(screen.getByTestId('collab-draft-banner').textContent).toContain('last edit 2h ago');
    expect(live.contains(screen.getByRole('button', { name: 'Publish' }))).toBe(false);
    rerender(<DraftBanner draft={{ ...draft, lastEditAt: hoursAgo(3) }} selfName="Kim" onPublish={() => {}} />);
    expect(screen.getByRole('status').textContent).toBe('Unpublished changes by Ana and Raj');
    rerender(<DraftBanner draft={{ ...draft, contributors: ['Ana'] }} selfName="Kim" onPublish={() => {}} />);
    expect(screen.getByRole('status').textContent).toBe('Unpublished changes by Ana');
  });

  it('theBannerIsHiddenWithoutUnpublishedChanges', () => {
    const { container, rerender } = render(<DraftBanner draft={{ unpublished: false, contributors: ['Ana'], lastEditAt: null }} />);
    expect(container.textContent).toBe('');
    rerender(<DraftBanner draft={null} />);
    expect(container.textContent).toBe('');
  });

  it('without names or a time it still says there are unpublished changes', () => {
    render(<DraftBanner draft={{ unpublished: true, contributors: [], lastEditAt: null }} />);
    const text = screen.getByTestId('collab-draft-banner').textContent;
    expect(text).toContain('Unpublished changes');
    expect(text).not.toContain(' by ');
    expect(text).not.toContain('last edit');
  });

  it('each action calls its handler; an action without a handler is not offered', () => {
    const onPublish = vi.fn();
    const onDiscard = vi.fn();
    const onViewChanges = vi.fn();
    const onDismiss = vi.fn();
    const draft = { unpublished: true, contributors: ['Ana'], lastEditAt: null };
    const { rerender } = render(<DraftBanner draft={draft} onPublish={onPublish} onDiscard={onDiscard} onViewChanges={onViewChanges} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    fireEvent.click(screen.getByRole('button', { name: 'View changes' }));
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect([onPublish, onDiscard, onViewChanges, onDismiss].map((f) => f.mock.calls.length)).toEqual([1, 1, 1, 1]);
    rerender(<DraftBanner draft={draft} onPublish={onPublish} />);
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Publish']);
  });
});
