import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/work', () => ({
  listChanges: vi.fn(), openChange: vi.fn(), requestReview: vi.fn(), reviewChange: vi.fn(), mergeChange: vi.fn(),
  abandonChange: vi.fn(),
}));
import { listChanges, openChange, requestReview, reviewChange, mergeChange, abandonChange } from '../../api/work';
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
    await waitFor(() => expect(abandonChange).toHaveBeenCalledWith(1, {}));
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

  it('a failed load shows an empty list, not an error page', async () => {
    listChanges.mockRejectedValue(new Error('boom'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<WorkChanges itemKey="PLAT-7" canEdit />);
    expect(await screen.findByText('No changes.')).toBeInTheDocument();
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
});
