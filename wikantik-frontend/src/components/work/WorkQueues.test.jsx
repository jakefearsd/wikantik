import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/work', () => ({
  createQueue: vi.fn(), updateQueue: vi.fn(), retireQueue: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { createQueue, updateQueue, retireQueue } from '../../api/work';
import WorkQueues from './WorkQueues';

const ACTIVE = { key: 'triage', name: 'Triage', description: 'Incoming work', retiredAt: null };
const RETIRED = { key: 'old-q', name: 'Old queue', description: '', retiredAt: '2026-01-01T00:00:00Z' };
const reload = vi.fn();

function renderQueues(props = {}) {
  return render(<WorkQueues queues={[ACTIVE]} reload={reload} {...props} />);
}
const refusal = (message, status, body) => Object.assign(new Error(message), { status, body });

function openCreate() {
  fireEvent.click(screen.getByRole('button', { name: 'New queue' }));
}
function fillCreate(key, name) {
  fireEvent.change(screen.getByLabelText('Queue key'), { target: { value: key } });
  fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: name } });
}

beforeEach(() => {
  vi.clearAllMocks();
  createQueue.mockResolvedValue({});
  updateQueue.mockResolvedValue({});
  retireQueue.mockResolvedValue(undefined);
});

describe('WorkQueues', () => {
  it('says there are no queues yet when the list is empty', () => {
    renderQueues({ queues: [] });
    expect(screen.getByText('No queues yet.')).toBeInTheDocument();
  });

  it('lists active queues before retired ones whatever order they arrive in', () => {
    renderQueues({ queues: [RETIRED, ACTIVE, { ...ACTIVE, key: 'second', name: 'Second' }] });
    const rows = screen.getAllByTestId('queue-row').map((r) => within(r).getByRole('strong').textContent);
    expect(rows).toEqual(['Triage', 'Second', 'Old queue']);
    expect(within(screen.getAllByTestId('queue-row')[2]).getByText('retired')).toBeInTheDocument();
  });

  it('keeps several retired queues together after the active ones', () => {
    renderQueues({ queues: [RETIRED, { ...RETIRED, key: 'older', name: 'Older' }, ACTIVE] });
    const names = screen.getAllByTestId('queue-row').map((r) => within(r).getByRole('strong').textContent);
    expect(names).toEqual(['Triage', 'Old queue', 'Older']);
  });

  it('offers no create, edit or retire controls to someone who cannot manage queues', () => {
    renderQueues({ canManage: false });
    expect(screen.getByText('Triage')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New queue' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit queue triage' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Retire queue triage' })).toBeNull();
  });

  it('cancelling the new-queue form brings the New queue button back', () => {
    renderQueues();
    openCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'New queue' })).toBeInTheDocument();
  });

  it('asks for a name when the key is fine but the name is blank', () => {
    renderQueues();
    openCreate();
    fillCreate('good-key', '   ');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name.');
    expect(screen.getByLabelText('Queue name')).toHaveAttribute('aria-invalid', 'true');
    expect(createQueue).not.toHaveBeenCalled();
  });

  it.each(['A', 'x', '1abc', 'has space', `a${'b'.repeat(30)}`])('refuses the key "%s" before calling the server', (bad) => {
    renderQueues();
    openCreate();
    fillCreate(bad, 'Name');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(screen.getByRole('alert')).toHaveTextContent(/Key must start with a lowercase letter/);
    expect(screen.getByLabelText('Queue key')).toHaveAttribute('aria-invalid', 'true');
    expect(createQueue).not.toHaveBeenCalled();
  });

  it('creates a queue with trimmed fields, tells the admin and reloads', async () => {
    renderQueues();
    openCreate();
    fillCreate('good-key', '  Good  ');
    fireEvent.change(screen.getByLabelText('Queue description'), { target: { value: ' Things ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    await waitFor(() => expect(createQueue).toHaveBeenCalledWith({ key: 'good-key', name: 'Good', description: 'Things' }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(toast.success).toHaveBeenCalledWith('Created queue good-key');
    expect(screen.getByRole('button', { name: 'New queue' })).toBeInTheDocument();
  });

  it('a double click on Create queue submits once', async () => {
    let release;
    createQueue.mockReturnValue(new Promise((r) => { release = r; }));
    renderQueues();
    openCreate();
    fillCreate('good-key', 'Good');
    const form = screen.getByRole('button', { name: 'Create queue' }).closest('form');
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(createQueue).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Create queue' })).toBeDisabled();
    release({});
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('shows a refusal in a toast, in the server words, and keeps the form open', async () => {
    createQueue.mockRejectedValue(refusal('Only admins manage queues.', 403));
    renderQueues();
    openCreate();
    fillCreate('good-key', 'Good');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Only admins manage queues.'));
    expect(screen.getByLabelText('Queue key')).toHaveValue('good-key');
    expect(screen.getByRole('button', { name: 'Create queue' })).not.toBeDisabled();
  });

  it('puts a name complaint from the server on the name field', async () => {
    createQueue.mockRejectedValue(refusal('Name is too long.', 422, { field: 'name' }));
    renderQueues();
    openCreate();
    fillCreate('good-key', 'Good');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(await screen.findByText('Name is too long.')).toHaveAttribute('id', 'work-q-err-name');
    expect(screen.getByLabelText('Queue name')).toHaveAttribute('aria-describedby', 'work-q-err-name');
  });

  it('puts a key complaint from the server on the key field', async () => {
    createQueue.mockRejectedValue(refusal('Key is reserved.', 422, { field: 'key' }));
    renderQueues();
    openCreate();
    fillCreate('good-key', 'Good');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(await screen.findByText('Key is reserved.')).toHaveAttribute('id', 'work-q-err-key');
  });

  it('puts a duplicate-key conflict on the key field', async () => {
    createQueue.mockRejectedValue(refusal('Queue exists.', 409, { reason: 'exists' }));
    renderQueues();
    openCreate();
    fillCreate('triage', 'Again');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(await screen.findByText('Queue exists.')).toHaveAttribute('id', 'work-q-err-key');
  });

  it('shows an unclassified failure as a form-level error, with a default when the server gave no words', async () => {
    createQueue.mockRejectedValue(new Error(''));
    renderQueues();
    openCreate();
    fillCreate('good-key', 'Good');
    fireEvent.click(screen.getByRole('button', { name: 'Create queue' }));
    expect(await screen.findByText('Could not save the queue.')).toBeInTheDocument();
    expect(screen.getByLabelText('Queue key')).not.toHaveAttribute('aria-invalid');
  });

  it('edits a queue: the key is read-only, a blank name is refused, and the save sends trimmed fields', async () => {
    renderQueues();
    fireEvent.click(screen.getByRole('button', { name: 'Edit queue triage' }));
    expect(screen.getByLabelText('Queue key')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: ' ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save queue' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name.');
    expect(updateQueue).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Queue name'), { target: { value: ' Triage 2 ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save queue' }));
    await waitFor(() => expect(updateQueue).toHaveBeenCalledWith('triage', { name: 'Triage 2', description: 'Incoming work' }));
    expect(toast.success).toHaveBeenCalledWith('Queue saved');
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(screen.queryByLabelText('Queue name')).toBeNull();
  });

  it('cancelling an edit shows the row again and hides its Edit and Retire buttons only while editing', () => {
    renderQueues();
    fireEvent.click(screen.getByRole('button', { name: 'Edit queue triage' }));
    expect(screen.queryByRole('button', { name: 'Retire queue triage' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('Incoming work')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retire queue triage' })).toBeInTheDocument();
  });

  it('retires only after the confirmation, and cancelling the confirmation retires nothing', async () => {
    renderQueues();
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    expect(screen.getByText(/It will no longer be offered when assigning work/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(retireQueue).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire triage' }));
    await waitFor(() => expect(retireQueue).toHaveBeenCalledWith('triage'));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(toast.success).toHaveBeenCalledWith('Retired queue triage');
  });

  it('a second click on the retire confirmation while it is in flight retires once', async () => {
    let release;
    retireQueue.mockReturnValue(new Promise((r) => { release = r; }));
    renderQueues();
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    const yes = screen.getByRole('button', { name: 'Yes, retire triage' });
    fireEvent.click(yes);
    fireEvent.click(yes);
    expect(retireQueue).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('shows the refusal when retiring fails, and closes the confirmation', async () => {
    retireQueue.mockRejectedValue(refusal('Queue still has open items.', 409));
    renderQueues();
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire triage' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Queue still has open items.'));
    expect(reload).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Retire queue triage' })).toBeInTheDocument();
  });

  it('falls back to a plain sentence when retiring fails without a message', async () => {
    retireQueue.mockRejectedValue(new Error(''));
    renderQueues();
    fireEvent.click(screen.getByRole('button', { name: 'Retire queue triage' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, retire triage' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Could not retire the queue.'));
  });
});
