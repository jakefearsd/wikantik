import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../api/work', () => ({ registerAgent: vi.fn(), updateAgent: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { registerAgent, updateAgent } from '../../api/work';
import WorkAgentForm from './WorkAgentForm';

const QUEUES = [
  { key: 'triage', name: 'Triage', retiredAt: null },
  { key: 'build', name: 'Build', retiredAt: null },
  { key: 'old-q', name: 'Old queue', retiredAt: '2026-01-01T00:00:00Z' },
];
const AGENT = { id: 7, name: 'triage-bot', vendor: 'acme', runtime: 'claude', queues: ['triage', 'old-q'] };
const onRegistered = vi.fn();
const onSaved = vi.fn();
const onCancel = vi.fn();
const refusal = (message, status, body) => Object.assign(new Error(message), { status, body });

function renderForm(props = {}) {
  return render(<WorkAgentForm queues={QUEUES} onRegistered={onRegistered} onSaved={onSaved} onCancel={onCancel} {...props} />);
}
const name = (v) => fireEvent.change(screen.getByLabelText('Agent name'), { target: { value: v } });
const submit = (label = 'Register') => fireEvent.click(screen.getByRole('button', { name: label }));

beforeEach(() => {
  vi.clearAllMocks();
  registerAgent.mockResolvedValue({ agent: { name: 'new-bot' }, token: 'tok' });
  updateAgent.mockResolvedValue({});
});

describe('WorkAgentForm', () => {
  it('offers only active queues when registering and says so when there are none to join', () => {
    const { unmount } = renderForm();
    expect(screen.getByLabelText('Triage')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Old queue/)).toBeNull();
    unmount();
    renderForm({ queues: [QUEUES[2]] });
    expect(screen.getByText('No queues to join yet.')).toBeInTheDocument();
  });

  it.each(['Bad', '-x', '', 'a@', 'a@B', `${'a'.repeat(41)}`])('refuses the agent name "%s" before calling the server', (bad) => {
    renderForm();
    name(bad);
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent(/Agent name is lowercase letters/);
    expect(screen.getByLabelText('Agent name')).toHaveAttribute('aria-invalid', 'true');
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('registers with trimmed vendor and runtime and the queues that were ticked, then hands over the minted key', async () => {
    renderForm();
    name('new-bot@host.lan');
    fireEvent.change(screen.getByLabelText('Vendor'), { target: { value: ' acme ' } });
    fireEvent.change(screen.getByLabelText('Runtime'), { target: { value: ' claude ' } });
    fireEvent.click(screen.getByLabelText('Triage'));
    fireEvent.click(screen.getByLabelText('Build'));
    fireEvent.click(screen.getByLabelText('Triage')); // ticked then unticked
    submit();
    await waitFor(() => expect(registerAgent).toHaveBeenCalledWith(
      { name: 'new-bot@host.lan', vendor: 'acme', runtime: 'claude', queues: ['build'] }));
    await waitFor(() => expect(onRegistered).toHaveBeenCalledWith({ agent: { name: 'new-bot' }, token: 'tok' }));
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('a second submit while the first is in flight is ignored', async () => {
    let release;
    registerAgent.mockReturnValue(new Promise((r) => { release = r; }));
    renderForm();
    name('new-bot');
    const form = screen.getByLabelText('Agent name').closest('form');
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(registerAgent).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Register' })).toBeDisabled();
    release({ agent: { name: 'new-bot' }, token: 't' });
    await waitFor(() => expect(onRegistered).toHaveBeenCalledTimes(1));
  });

  it('when locked, Register is disabled, points at the explanation, and a submit sends nothing', () => {
    renderForm({ locked: true, lockedHint: 'why-locked' });
    name('new-bot');
    const button = screen.getByRole('button', { name: 'Register' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-describedby', 'why-locked');
    fireEvent.submit(button.closest('form'));
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('editing is never locked and says Save agent', async () => {
    renderForm({ agent: AGENT, locked: true, lockedHint: 'why-locked' });
    const button = screen.getByRole('button', { name: 'Save agent' });
    expect(button).not.toBeDisabled();
    expect(button).not.toHaveAttribute('aria-describedby');
  });

  it('editing pre-fills, keeps the retired queue the agent serves ticked and labelled, and saves all four fields', async () => {
    renderForm({ agent: AGENT });
    expect(screen.getByLabelText('Agent name')).toHaveValue('triage-bot');
    expect(screen.getByLabelText('Vendor')).toHaveValue('acme');
    expect(screen.getByLabelText('Runtime')).toHaveValue('claude');
    expect(screen.getByLabelText('Old queue (retired)')).toBeChecked();
    submit('Save agent');
    await waitFor(() => expect(updateAgent).toHaveBeenCalledWith(7,
      { name: 'triage-bot', vendor: 'acme', runtime: 'claude', queues: ['triage', 'old-q'] }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(toast.success).toHaveBeenCalledWith('Saved triage-bot');
    expect(registerAgent).not.toHaveBeenCalled();
  });

  it('editing an agent that has no vendor, runtime or queues starts with empty fields', () => {
    renderForm({ agent: { id: 9, name: 'bare' } });
    expect(screen.getByLabelText('Vendor')).toHaveValue('');
    expect(screen.getByLabelText('Runtime')).toHaveValue('');
    expect(screen.getByLabelText('Triage')).not.toBeChecked();
  });

  it('shows a refusal in the server words on the form and lets the person try again', async () => {
    registerAgent.mockRejectedValue(refusal('Only leads register agents.', 403));
    renderForm();
    name('new-bot');
    submit();
    expect(await screen.findByText('Only leads register agents.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register' })).not.toBeDisabled();
    expect(screen.getByLabelText('Agent name')).toHaveValue('new-bot');
  });

  it('puts a conflict on the name next to the name field', async () => {
    registerAgent.mockRejectedValue(refusal('An agent with this name exists.', 409, { reason: 'exists' }));
    renderForm();
    name('triage-bot');
    submit();
    expect(await screen.findByText('An agent with this name exists.')).toHaveAttribute('id', 'work-a-err-name');
  });

  it('puts a queues complaint next to the queue list', async () => {
    registerAgent.mockRejectedValue(refusal('Unknown queue.', 422, { field: 'queues' }));
    renderForm();
    name('new-bot');
    submit();
    expect(await screen.findByText('Unknown queue.')).toHaveAttribute('id', 'work-a-err-queues');
    expect(screen.getByRole('group', { name: 'Queues' })).toHaveAttribute('aria-describedby', 'work-a-err-queues');
  });

  it('uses a plain default sentence when the server gave none, different for register and edit', async () => {
    registerAgent.mockRejectedValue(new Error(''));
    updateAgent.mockRejectedValue(new Error(''));
    const { unmount } = renderForm();
    name('new-bot');
    submit();
    expect(await screen.findByText('Could not register the agent.')).toBeInTheDocument();
    unmount();
    renderForm({ agent: AGENT });
    submit('Save agent');
    expect(await screen.findByText('Could not save the agent.')).toBeInTheDocument();
  });

  it('Cancel calls onCancel without touching the server', () => {
    renderForm();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();
    expect(registerAgent).not.toHaveBeenCalled();
  });
});
