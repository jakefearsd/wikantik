import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/work', () => ({
  listChanges: vi.fn(), openChange: vi.fn(), requestReview: vi.fn(), reviewChange: vi.fn(), mergeChange: vi.fn(),
  abandonChange: vi.fn(), acknowledgeFlag: vi.fn(),
}));
import { acknowledgeFlag, listChanges, openChange, requestReview, reviewChange, mergeChange, abandonChange } from '../../api/work';
import WorkChanges from './WorkChanges';

const change = (id, state, extra = {}) => ({
  id, itemKey: 'PLAT-7', productKey: 'wikantik', branch: `work/PLAT-7-${id}`, baseBranch: 'main', headSha: 'aaaaaaa1bbbb',
  reviewUrl: null, state, mergeKind: null, mergedSha: null, openedByName: 'codex', openedByKind: 'agent',
  reviewedByName: null, reviewedByKind: null, approvedForHead: false, mergedByName: null, mergedByKind: null, ...extra,
});

const rowOf = (branch) => screen.getByText(branch).closest('li');

beforeEach(() => {
  vi.clearAllMocks();
  for (const fn of [openChange, requestReview, reviewChange, mergeChange, abandonChange]) fn.mockResolvedValue({});
});

describe('WorkChanges', () => {
  it('shows every state, how a change was merged, and who did what', async () => {
    listChanges.mockResolvedValue({ changes: [
      change(1, 'open'),
      change(2, 'in_review', { approvedForHead: true, reviewedByName: 'claude', reviewedByKind: 'agent', reviewUrl: 'https://example.test/pr/2' }),
      change(3, 'merged', { mergeKind: 'reviewed', mergedSha: 'ccccccc3dddd', mergedByName: 'Jake Fear', mergedByKind: 'human' }),
      change(4, 'merged', { mergeKind: 'direct', mergedSha: 'eeeeeee4', mergedByName: 'Jake Fear', mergedByKind: 'human' }),
      change(5, 'merged', { mergeKind: 'unreviewed', mergedSha: 'fffffff5', mergedByName: 'Jake Fear', mergedByKind: 'human' }),
      change(6, 'abandoned'),
    ] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    expect(await screen.findByText('work/PLAT-7-1')).toBeInTheDocument();
    expect(within(rowOf('work/PLAT-7-1')).getByText('open')).toBeInTheDocument();
    expect(rowOf('work/PLAT-7-1')).toHaveTextContent('opened by codex (agent) · head aaaaaaa1');
    expect(rowOf('work/PLAT-7-2')).toHaveTextContent('approved by claude (agent)');
    expect(within(rowOf('work/PLAT-7-2')).getByRole('link', { name: 'review' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(rowOf('work/PLAT-7-3')).getByText('merged after review')).toBeInTheDocument();
    expect(rowOf('work/PLAT-7-3')).toHaveTextContent('merged by Jake Fear · ccccccc3');
    expect(within(rowOf('work/PLAT-7-4')).getByText('merged directly')).toBeInTheDocument();
    expect(within(rowOf('work/PLAT-7-5')).getByText('merged without review')).toBeInTheDocument();
    expect(within(rowOf('work/PLAT-7-6')).getByText('abandoned')).toBeInTheDocument();
    // a closed change offers nothing to do
    expect(within(rowOf('work/PLAT-7-3')).queryByRole('button')).toBeNull();
    expect(within(rowOf('work/PLAT-7-6')).queryByRole('button')).toBeNull();
  });

  it('never turns a non-http review link into an anchor', async () => {
    listChanges.mockResolvedValue({ changes: [change(1, 'open', { reviewUrl: 'javascript:alert(1)' })] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-1');
    expect(screen.queryByRole('link', { name: 'review' })).toBeNull();
  });

  it('a viewer sees the changes and no controls', async () => {
    listChanges.mockResolvedValue({ changes: [change(1, 'in_review')] });
    render(<WorkChanges itemKey="PLAT-7" />);
    await screen.findByText('work/PLAT-7-1');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('opens a change and reloads the list', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    const onChanged = vi.fn();
    render(<WorkChanges itemKey="PLAT-7" canEdit onChanged={onChanged} />);
    expect(await screen.findByText('No changes.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'work/PLAT-7-spa' } });
    fireEvent.change(screen.getByLabelText('Newest commit'), { target: { value: 'abc1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    await waitFor(() => expect(openChange).toHaveBeenCalledWith('PLAT-7',
      { branch: 'work/PLAT-7-spa', headSha: 'abc1234', reviewUrl: null }));
    await waitFor(() => expect(listChanges).toHaveBeenCalledTimes(2));
    expect(onChanged).toHaveBeenCalled();
    expect(screen.queryByLabelText('Branch')).toBeNull();
  });

  it('records a merged change in one step', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Record a merged change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'main' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record merged change' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Name the merged commit.');
    expect(openChange).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Merged commit'), { target: { value: '9b01c77' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record merged change' }));
    await waitFor(() => expect(openChange).toHaveBeenCalledWith('PLAT-7', { branch: 'main', mergedSha: '9b01c77' }));
  });

  it('shows a refusal in place and keeps what was typed', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    openChange.mockRejectedValue(Object.assign(new Error('This item already has an open change on that branch.'), { status: 400 }));
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'work/PLAT-7-dup' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already has an open change on that branch');
    expect(screen.getByLabelText('Branch')).toHaveValue('work/PLAT-7-dup');
  });

  it('asks for a review, approves, sends back, merges and abandons', async () => {
    listChanges.mockResolvedValue({ changes: [change(1, 'open'), change(2, 'in_review')] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-1');
    fireEvent.click(within(rowOf('work/PLAT-7-1')).getByRole('button', { name: 'Ask for review' }));
    await waitFor(() => expect(requestReview).toHaveBeenCalledWith(1));
    await waitFor(() => expect(within(rowOf('work/PLAT-7-2')).getByRole('button', { name: 'Approve' })).not.toBeDisabled());

    fireEvent.change(screen.getByLabelText('Review note for work/PLAT-7-2'), { target: { value: 'needs a test' } });
    fireEvent.click(within(rowOf('work/PLAT-7-2')).getByRole('button', { name: 'Send back' }));
    await waitFor(() => expect(reviewChange).toHaveBeenCalledWith(2, { approved: false, note: 'needs a test' }));
    await waitFor(() => expect(within(rowOf('work/PLAT-7-2')).getByRole('button', { name: 'Approve' })).not.toBeDisabled());

    fireEvent.click(within(rowOf('work/PLAT-7-2')).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(reviewChange).toHaveBeenCalledWith(2, { approved: true, note: 'needs a test' }));
    await waitFor(() => expect(within(rowOf('work/PLAT-7-2')).getByRole('button', { name: 'Record merge' })).not.toBeDisabled());

    // the merged commit defaults to the change's head
    fireEvent.click(within(rowOf('work/PLAT-7-2')).getByRole('button', { name: 'Record merge' }));
    await waitFor(() => expect(mergeChange).toHaveBeenCalledWith(2, { mergedSha: 'aaaaaaa1bbbb' }));
    await waitFor(() => expect(within(rowOf('work/PLAT-7-1')).getByRole('button', { name: 'Abandon' })).not.toBeDisabled());

    fireEvent.click(within(rowOf('work/PLAT-7-1')).getByRole('button', { name: 'Abandon' }));
    expect(abandonChange).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Abandon' }));
    await waitFor(() => expect(abandonChange).toHaveBeenCalledWith(1, { note: null }));
  });

  it('asks before abandoning, sends the reason, shows a refusal in the strip and cancels without a call', async () => {
    listChanges.mockResolvedValue({ changes: [change(1, 'open')] });
    abandonChange.mockRejectedValueOnce(new Error('Already merged.'));
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-1');
    fireEvent.click(screen.getByRole('button', { name: 'Abandon' }));
    const strip = screen.getByRole('alertdialog');
    expect(strip).toHaveTextContent('Abandon this change? It cannot be reopened.');
    fireEvent.change(within(strip).getByLabelText('Why (optional)'), { target: { value: ' superseded ' } });
    fireEvent.click(within(strip).getByRole('button', { name: 'Abandon' }));
    await waitFor(() => expect(abandonChange).toHaveBeenCalledWith(1, { note: 'superseded' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('Already merged.');
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(abandonChange).toHaveBeenCalledTimes(1);
  });

  it('a 409 on a merge shows the server message', async () => {
    listChanges.mockResolvedValue({ changes: [change(2, 'in_review')] });
    mergeChange.mockRejectedValue(Object.assign(new Error('This change has no approval for its current commit.'),
      { status: 409, body: { reason: 'not_approved' } }));
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-2');
    fireEvent.click(screen.getByRole('button', { name: 'Record merge' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('no approval for its current commit');
    expect(screen.getByLabelText('Merged commit for work/PLAT-7-2')).toHaveValue('aaaaaaa1bbbb');
  });

  it('a failed first load shows an inline error with Retry, never an empty list', async () => {
    listChanges.mockRejectedValueOnce(new Error('boom'));
    listChanges.mockResolvedValue({ changes: [change(1, 'open')] });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
    expect(screen.queryByText('No changes.')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('work/PLAT-7-1')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    warn.mockRestore();
  });

  it('a failed reload keeps the last good list', async () => {
    listChanges.mockResolvedValueOnce({ changes: [change(1, 'open')] });
    listChanges.mockRejectedValue(new Error('down'));
    requestReview.mockResolvedValue({});
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-1');
    fireEvent.click(screen.getByRole('button', { name: 'Ask for review' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('down');
    expect(screen.getByText('work/PLAT-7-1')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('never turns a non-web review link such as ftp into an anchor, but does link https', async () => {
    listChanges.mockResolvedValue({ changes: [
      change(1, 'open', { reviewUrl: 'ftp://example.test/pr/1' }), change(2, 'open', { reviewUrl: 'not a url' }),
    ] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-1');
    expect(screen.queryByRole('link', { name: 'review' })).toBeNull();
  });

  it('says someone when a change has no named opener, approver or merger, and shows odd states as they are', async () => {
    listChanges.mockResolvedValue({ changes: [
      change(1, 'open', { openedByName: null, headSha: null }),
      change(2, 'in_review', { approvedForHead: true, reviewedByName: null }),
      change(3, 'merged', { mergedByName: null, mergedSha: 'abcdef123456' }),
      change(4, 'superseded'),
    ] });
    render(<WorkChanges itemKey="PLAT-7" />);
    await screen.findByText('work/PLAT-7-1');
    expect(rowOf('work/PLAT-7-1')).toHaveTextContent('opened by someone');
    expect(rowOf('work/PLAT-7-1')).not.toHaveTextContent('head');
    expect(rowOf('work/PLAT-7-2')).toHaveTextContent('approved by someone');
    expect(within(rowOf('work/PLAT-7-3')).getByText('merged')).toBeInTheDocument();
    expect(rowOf('work/PLAT-7-3')).toHaveTextContent('merged by someone · abcdef12');
    expect(within(rowOf('work/PLAT-7-4')).getByText('superseded')).toBeInTheDocument();
  });

  it('approves and sends back with no note when none was typed, and will not record a merge with a blank commit', async () => {
    listChanges.mockResolvedValue({ changes: [change(2, 'in_review', { headSha: null })] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-2');
    expect(screen.getByRole('button', { name: 'Record merge' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(reviewChange).toHaveBeenCalledWith(2, { approved: true, note: null }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Send back' })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: 'Send back' }));
    await waitFor(() => expect(reviewChange).toHaveBeenCalledWith(2, { approved: false, note: null }));
  });

  it('treats a missing list as no changes', async () => {
    listChanges.mockResolvedValue(undefined);
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    expect(await screen.findByText('No changes.')).toBeInTheDocument();
  });

  it('asks for a branch name before opening a change, and Cancel closes the form and clears the message', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Name the branch.');
    expect(openChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByLabelText('Branch')).toBeNull();
    expect(screen.getByRole('button', { name: 'Open a change' })).toBeInTheDocument();
  });

  it('sends the review link when one is given and trims the branch', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: '  work/x  ' } });
    fireEvent.change(screen.getByLabelText('Review link'), { target: { value: ' https://example.test/pr/9 ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    await waitFor(() => expect(openChange).toHaveBeenCalledWith('PLAT-7',
      { branch: 'work/x', headSha: null, reviewUrl: 'https://example.test/pr/9' }));
  });

  it('gives a generic message when a refusal carries none', async () => {
    listChanges.mockResolvedValue({ changes: [change(1, 'open')] });
    abandonChange.mockRejectedValue({});
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-1');
    fireEvent.click(screen.getByRole('button', { name: 'Abandon' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Abandon' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That did not work.');
  });

  it('ignores a second submit while the first is still in flight', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    let release;
    openChange.mockReturnValue(new Promise((r) => { release = r; }));
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'work/once' } });
    const form = screen.getByLabelText('Branch').closest('form');
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(openChange).toHaveBeenCalledTimes(1);
    release({});
    await waitFor(() => expect(screen.queryByLabelText('Branch')).toBeNull());
  });

  it('does not offer Approve once the approval stands on the current head; Send back stays', async () => {
    listChanges.mockResolvedValue({ changes: [change(2, 'in_review', { approvedForHead: true, reviewedByName: 'Ada' })] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('work/PLAT-7-2');
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Send back' })).toBeInTheDocument();
  });

  describe('code host', () => {
    const hosted = (id, extra = {}) => change(id, 'merged', {
      source: 'codehost', mergeKind: 'unreviewed', mergedSha: 'abcdef123456', openedByName: null, openedByKind: null,
      externalOpenedBy: 'octocat', mergedByName: null, externalMergedBy: 'octocat', policyFlag: 'unreviewed',
      firstCommitAt: '2026-01-01T00:00:00Z', ...extra,
    });

    it('shows the host chip, the outside author (not a link), the first commit and the flag', async () => {
      listChanges.mockResolvedValue({ changes: [hosted(1)] });
      render(<WorkChanges itemKey="PLAT-7" canEdit />);
      await screen.findByText('work/PLAT-7-1');
      const row = rowOf('work/PLAT-7-1');
      expect(within(row).getByText('via GitHub')).toBeInTheDocument();
      expect(row).toHaveTextContent('opened by octocat (outside the wiki)');
      expect(row).toHaveTextContent('merged by octocat (outside the wiki)');
      expect(row).toHaveTextContent(/first commit /);
      expect(row.querySelectorAll('.work-outside-author')).toHaveLength(2);
      expect(within(row).queryByRole('link')).toBeNull();
      expect(within(row).getByText('outside policy: without review')).toBeInTheDocument();
      expect(row).not.toHaveTextContent('someone');
    });

    it('omits the arrow when there is no base branch', async () => {
      listChanges.mockResolvedValue({ changes: [hosted(1, { baseBranch: null })] });
      render(<WorkChanges itemKey="PLAT-7" />);
      await screen.findByText('work/PLAT-7-1');
      expect(rowOf('work/PLAT-7-1')).not.toHaveTextContent('→');
    });

    it('labels a direct flag', async () => {
      listChanges.mockResolvedValue({ changes: [hosted(1, { mergeKind: 'direct', policyFlag: 'direct' })] });
      render(<WorkChanges itemKey="PLAT-7" />);
      expect(await screen.findByText('outside policy: direct commit')).toBeInTheDocument();
    });

    it('acknowledges: disabled in flight, flag leaves only after the server confirms, then reloads', async () => {
      let resolve;
      acknowledgeFlag.mockReturnValue(new Promise((r) => { resolve = r; }));
      listChanges.mockResolvedValueOnce({ changes: [hosted(1)] });
      listChanges.mockResolvedValue({ changes: [hosted(1, { flagAcknowledgedByName: 'Ana Alvarez', flagAcknowledgedAt: '2026-01-02T00:00:00Z' })] });
      const onChanged = vi.fn();
      render(<WorkChanges itemKey="PLAT-7" canAcknowledge onChanged={onChanged} />);
      const btn = await screen.findByRole('button', { name: /Acknowledge the flag/ });
      fireEvent.click(btn);
      expect(acknowledgeFlag).toHaveBeenCalledWith(1);
      expect(btn).toBeDisabled();
      expect(screen.getByText('outside policy: without review')).toBeInTheDocument();
      resolve({});
      expect(await screen.findByText('acknowledged by Ana Alvarez')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Acknowledge the flag/ })).toBeNull();
      expect(onChanged).toHaveBeenCalled();
    });

    it('a refused acknowledgement says so in words and keeps the flag and button', async () => {
      acknowledgeFlag.mockRejectedValue(new Error('That flag is already acknowledged.'));
      listChanges.mockResolvedValue({ changes: [hosted(1)] });
      render(<WorkChanges itemKey="PLAT-7" canAcknowledge />);
      fireEvent.click(await screen.findByRole('button', { name: /Acknowledge the flag/ }));
      expect(await screen.findByRole('alert')).toHaveTextContent('already acknowledged');
      expect(screen.getByRole('button', { name: /Acknowledge the flag/ })).toBeEnabled();
      expect(screen.getByText('outside policy: without review')).toBeInTheDocument();
    });

    it('offers no acknowledge control without canAcknowledge', async () => {
      listChanges.mockResolvedValue({ changes: [hosted(1)] });
      render(<WorkChanges itemKey="PLAT-7" canEdit />);
      await screen.findByText('outside policy: without review');
      expect(screen.queryByRole('button', { name: /Acknowledge/ })).toBeNull();
    });

    it('a superseded row shows no flag, only where it was recorded again', async () => {
      listChanges.mockResolvedValue({ changes: [hosted(1, { mergeKind: 'direct', policyFlag: 'direct', supersededBy: 5 })] });
      render(<WorkChanges itemKey="PLAT-7" canAcknowledge />);
      await screen.findByText('work/PLAT-7-1');
      const row = rowOf('work/PLAT-7-1');
      expect(row).toHaveTextContent('recorded again as change 5');
      expect(within(row).queryByText(/outside policy/)).toBeNull();
      expect(within(row).queryByRole('button')).toBeNull();
    });

    it('a flag that clears on its own is gone after the next load', async () => {
      listChanges.mockResolvedValueOnce({ changes: [hosted(1)] });
      listChanges.mockResolvedValue({ changes: [hosted(1, { policyFlag: null, mergeKind: 'reviewed' })] });
      const { rerender } = render(<WorkChanges itemKey="PLAT-7" refreshKey={0} />);
      await screen.findByText('outside policy: without review');
      rerender(<WorkChanges itemKey="PLAT-7" refreshKey={1} />);
      await waitFor(() => expect(screen.queryByText(/outside policy/)).toBeNull());
    });
  });

  it('sends firstCommitAt as an ISO instant when given', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'work/x' } });
    fireEvent.change(screen.getByLabelText('First commit (optional)'), { target: { value: '2026-11-02T09:30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    await waitFor(() => expect(openChange).toHaveBeenCalledWith('PLAT-7',
      { branch: 'work/x', headSha: null, reviewUrl: null, firstCommitAt: new Date('2026-11-02T09:30').toISOString() }));
  });

  it('omits firstCommitAt when blank', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'work/x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    await waitFor(() => expect(openChange).toHaveBeenCalledWith('PLAT-7', { branch: 'work/x', headSha: null, reviewUrl: null }));
  });

  it('refuses a first commit year outside 1970-9999 without sending it', async () => {
    listChanges.mockResolvedValue({ changes: [] });
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    await screen.findByText('No changes.');
    fireEvent.click(screen.getByRole('button', { name: 'Open a change' }));
    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'work/x' } });
    fireEvent.change(screen.getByLabelText('First commit (optional)'), { target: { value: '1901-01-01T00:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open change' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('between 1970 and 9999');
    expect(openChange).not.toHaveBeenCalled();
  });
});
