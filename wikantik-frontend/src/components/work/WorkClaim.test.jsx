import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import WorkClaim from './WorkClaim';

const at = (h, m, day = 16) => new Date(2026, 10, day, h, m).toISOString();
const NOW = new Date(2026, 10, 16, 14, 0);
const CLAIMED = {
  key: 'PLAT-7', status: 'in_progress', queue: 'implementer', queueName: 'Implementer',
  claimedByAgent: 3, claimedByName: 'codex@docker2', claimedAt: at(13, 5), claimExpiresAt: at(15, 40),
};
const UNCLAIMED = { key: 'PLAT-7', status: 'ready', queue: 'implementer', queueName: 'Implementer', claimedByAgent: null };
const renderClaim = (props = {}) => render(<WorkClaim item={CLAIMED} canRelease now={NOW} onRelease={vi.fn(() => Promise.resolve(true))} {...props} />);

describe('WorkClaim', () => {
  it('showsHolderSinceAndExpiry', () => {
    renderClaim();
    const panel = screen.getByRole('region', { name: 'Claim' });
    expect(panel).toHaveTextContent('Claimed by codex@docker2');
    expect(panel).toHaveTextContent('since 13:05');
    expect(panel).toHaveTextContent('until 15:40');
    expect(panel).not.toHaveTextContent('lapsed');
  });

  it('a lapsed claim says so in words', () => {
    renderClaim({ item: { ...CLAIMED, claimExpiresAt: at(13, 30) } });
    expect(screen.getByRole('region', { name: 'Claim' })).toHaveTextContent('lapsed at 13:30');
    expect(screen.getByRole('region', { name: 'Claim' })).toHaveClass('claim-expired');
  });

  it('memberMayReleaseWithNote: confirming first, saying the item goes back to its queue', async () => {
    const onRelease = vi.fn(() => Promise.resolve(true));
    renderClaim({ onRelease });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    const confirm = screen.getByRole('alertdialog', { name: 'Release claim' });
    expect(confirm).toHaveTextContent("codex@docker2's claim");
    expect(confirm).toHaveTextContent('back to the Implementer queue');
    expect(onRelease).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Release note (optional)'), { target: { value: 'stuck' } });
    fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
    await waitFor(() => expect(onRelease).toHaveBeenCalledWith('stuck'));
  });

  it('release can be cancelled with Escape and sends nothing', () => {
    const onRelease = vi.fn();
    renderClaim({ onRelease });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    fireEvent.keyDown(screen.getByLabelText('Release note (optional)'), { key: 'Escape' });
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(onRelease).not.toHaveBeenCalled();
  });

  it('viewerSeesNoRelease: the state is shown without an action', () => {
    renderClaim({ canRelease: false });
    expect(screen.getByRole('region', { name: 'Claim' })).toHaveTextContent('Claimed by codex@docker2');
    expect(screen.queryByRole('button', { name: 'Release claim' })).toBeNull();
  });

  it('claimableHintWhenReadyInQueue', () => {
    render(<WorkClaim item={UNCLAIMED} canRelease now={NOW} onRelease={vi.fn()} />);
    expect(screen.getByText('Claimable by agents serving Implementer')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release claim' })).toBeNull();
  });

  it('shows nothing for an unclaimed item that agents cannot claim', () => {
    const { container } = render(<WorkClaim item={{ ...UNCLAIMED, status: 'draft' }} canRelease now={NOW} onRelease={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
    const none = render(<WorkClaim item={{ ...UNCLAIMED, queue: null }} canRelease now={NOW} onRelease={vi.fn()} />);
    expect(none.container).toBeEmptyDOMElement();
  });

  it('a claim whose holder cannot be named says an agent, never an id', () => {
    renderClaim({ item: { ...CLAIMED, claimedByName: null } });
    expect(screen.getByRole('region', { name: 'Claim' })).toHaveTextContent('Claimed by an agent');
  });

  it('names a generic queue when the claimable item\'s queue has no name', () => {
    render(<WorkClaim item={{ ...UNCLAIMED, queueName: null }} now={NOW} onRelease={vi.fn()} />);
    expect(screen.getByText('Claimable by agents serving this queue')).toBeInTheDocument();
  });

  it('says the item goes unclaimed when it is in no queue, and "its" queue when the queue has no name', () => {
    renderClaim({ item: { ...CLAIMED, queue: null, queueName: null } });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    expect(screen.getByRole('alertdialog', { name: 'Release claim' })).toHaveTextContent('The item goes unclaimed.');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('says back to its queue when the queue has no name', () => {
    renderClaim({ item: { ...CLAIMED, queueName: null } });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    expect(screen.getByRole('alertdialog')).toHaveTextContent('The item goes back to its queue.');
  });

  it('closes the confirmation, clears the note and returns focus once the release succeeds', async () => {
    renderClaim();
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    fireEvent.change(screen.getByLabelText('Release note (optional)'), { target: { value: '  stuck  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    const reopen = screen.getByRole('button', { name: 'Release claim' });
    await waitFor(() => expect(reopen).toHaveFocus());
    fireEvent.click(reopen);
    expect(screen.getByLabelText('Release note (optional)')).toHaveValue('');
  });

  it('keeps the confirmation and the note open when the release is not accepted', async () => {
    const onRelease = vi.fn(() => Promise.resolve(false));
    renderClaim({ onRelease });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    fireEvent.change(screen.getByLabelText('Release note (optional)'), { target: { value: 'stuck' } });
    fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
    await waitFor(() => expect(onRelease).toHaveBeenCalledWith('stuck'));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Release it' })).toBeEnabled());
    expect(screen.getByLabelText('Release note (optional)')).toHaveValue('stuck');
  });

  it('logs and re-enables the buttons when the release call throws', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onRelease = vi.fn(() => Promise.reject(new Error('boom')));
    renderClaim({ onRelease });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    fireEvent.click(screen.getByRole('button', { name: 'Release it' }));
    await waitFor(() => expect(warn).toHaveBeenCalledWith('WorkClaim: release failed', expect.any(Error)));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Release it' })).toBeEnabled());
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('sends only one release while the first is still pending and disables the form', async () => {
    let finish;
    const onRelease = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    renderClaim({ onRelease });
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    const go = screen.getByRole('button', { name: 'Release it' });
    fireEvent.click(go);
    await waitFor(() => expect(go).toBeDisabled());
    expect(screen.getByLabelText('Release note (optional)')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    fireEvent.click(go);
    expect(onRelease).toHaveBeenCalledTimes(1);
    finish(false);
    await waitFor(() => expect(go).toBeEnabled());
  });

  it('ignores keys other than Escape inside the confirmation', () => {
    renderClaim();
    fireEvent.click(screen.getByRole('button', { name: 'Release claim' }));
    fireEvent.keyDown(screen.getByLabelText('Release note (optional)'), { key: 'a' });
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  describe('when the expiry passes with the page open', () => {
    afterEach(() => vi.useRealTimers());

    it('flips from until to lapsed at the expiry instant, without polling', () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(2026, 10, 16, 15, 39, 0));
      render(<WorkClaim item={CLAIMED} canRelease onRelease={vi.fn()} />);
      const panel = screen.getByRole('region', { name: 'Claim' });
      expect(panel).toHaveTextContent('until 15:40');
      expect(panel).not.toHaveClass('claim-expired');
      act(() => { vi.advanceTimersByTime(61 * 1000); });
      expect(screen.getByRole('region', { name: 'Claim' })).toHaveTextContent('claim lapsed at 15:40');
      expect(screen.getByRole('region', { name: 'Claim' })).toHaveClass('claim-expired');
    });

    it('clears its timer on unmount', () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(2026, 10, 16, 15, 39, 0));
      const { unmount } = render(<WorkClaim item={CLAIMED} canRelease onRelease={vi.fn()} />);
      expect(vi.getTimerCount()).toBe(1);
      unmount();
      expect(vi.getTimerCount()).toBe(0);
    });
  });
});
