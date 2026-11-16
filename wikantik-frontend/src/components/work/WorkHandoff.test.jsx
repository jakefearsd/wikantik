import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import WorkHandoff from './WorkHandoff';

const MEMBERS = [
  { principal: 'ana-login', principalKind: 'user', displayName: 'Ana Alvarez' },
  { principal: 'bob-login', principalKind: 'user', displayName: 'Bob Brown' },
];
const QUEUES = [{ key: 'reviewer', name: 'Reviewer', retiredAt: null }, { key: 'implementer', name: 'Implementer', retiredAt: null }];
const STATUS_OPTIONS = ['ready', 'in_progress', 'review'].map((v) => ({ value: v, label: v }));

function setup(props = {}) {
  const onSend = vi.fn(() => Promise.resolve(true));
  render(<WorkHandoff status="in_progress" assignee="bob-login" statusOptions={STATUS_OPTIONS}
    members={MEMBERS} queues={QUEUES} onSend={onSend} {...props} />);
  fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
  return { onSend };
}
const send = () => fireEvent.click(screen.getByRole('button', { name: 'Send handoff' }));

describe('WorkHandoff', () => {
  it('handOffToQueueSendsQueue: the queue alone, with the status and the note', async () => {
    const { onSend } = setup();
    fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: ' ready for review ' } });
    send();
    await waitFor(() => expect(onSend).toHaveBeenCalledWith('review', { queue: 'reviewer' }, 'ready for review'));
  });

  it('a queue target never offers In progress; a draft of it falls back to ready', async () => {
    const { onSend } = setup(); // item is in_progress with a person
    const status = screen.getByLabelText('Status after handoff');
    expect([...status.options].map((o) => o.value)).toContain('in_progress');
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
    expect([...status.options].map((o) => o.value)).toEqual(['ready', 'review']);
    expect(status.value).toBe('ready');
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'queued' } });
    send();
    await waitFor(() => expect(onSend).toHaveBeenCalledWith('ready', { queue: 'reviewer' }, 'queued'));
  });

  it('going back to a person offers In progress again', () => {
    setup();
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    expect([...screen.getByLabelText('Status after handoff').options].map((o) => o.value)).toContain('in_progress');
  });

  it('handing to a person sends the person alone', async () => {
    const { onSend } = setup();
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'yours' } });
    send();
    await waitFor(() => expect(onSend).toHaveBeenCalledWith('in_progress', { assignee: 'ana-login' }, 'yours'));
  });

  it('switching the choice from a queue to a person never sends both', async () => {
    const { onSend } = setup();
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'ana-login' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'n' } });
    send();
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    expect(onSend.mock.calls[0][1]).toEqual({ assignee: 'ana-login' });
  });

  it('noteRequired: nothing is sent without a note', () => {
    const { onSend } = setup();
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:reviewer' } });
    expect(screen.getByLabelText('Handoff note')).toBeRequired();
    expect(screen.getByRole('button', { name: 'Send handoff' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: '  ' } });
    send();
    expect(onSend).not.toHaveBeenCalled();
  });

  it('a hand-off must change the target: a status-only change is an ordinary update', () => {
    setup();
    fireEvent.change(screen.getByLabelText('Status after handoff'), { target: { value: 'review' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'n' } });
    expect(screen.getByRole('button', { name: 'Send handoff' })).toBeDisabled();
    expect(screen.getByText(/Choose a different person or queue/)).toBeInTheDocument();
  });

  it('offers no agents and no retired queues, and no unassigned target', () => {
    setup({ queues: [...QUEUES, { key: 'old', name: 'Old', retiredAt: '2026-01-01T00:00:00Z' }],
      members: [...MEMBERS, { principal: '3', principalKind: 'agent', displayName: 'codex@docker2' }] });
    const texts = [...screen.getByLabelText('Hand off to').querySelectorAll('option')].map((o) => o.textContent);
    expect(texts).toEqual(['Choose a person or queue', 'Ana Alvarez', 'Bob Brown', 'Queue · Reviewer', 'Queue · Implementer']);
  });

  it('an item in a queue starts with that queue chosen; handing it to another queue sends only that', async () => {
    const { onSend } = (() => {
      const f = vi.fn(() => Promise.resolve(true));
      render(<WorkHandoff status="ready" assignee={null} queue="reviewer" statusOptions={STATUS_OPTIONS}
        members={MEMBERS} queues={QUEUES} onSend={f} />);
      fireEvent.click(screen.getByRole('button', { name: 'Hand off' }));
      return { onSend: f };
    })();
    expect(screen.getByLabelText('Hand off to').value).toBe('queue:reviewer');
    fireEvent.change(screen.getByLabelText('Hand off to'), { target: { value: 'queue:implementer' } });
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'back' } });
    send();
    await waitFor(() => expect(onSend).toHaveBeenCalledWith('ready', { queue: 'implementer' }, 'back'));
  });

  it('tells the person that a claimed item loses its claim', () => {
    setup({ claimHolder: 'codex@docker2' });
    expect(screen.getByText('codex@docker2 is working on this; handing it off releases its claim.')).toBeInTheDocument();
  });

  it('says nothing about claims for an unclaimed item', () => {
    setup();
    expect(screen.queryByText(/releases its claim/)).toBeNull();
  });

  it('shows the server refusal in the form and keeps what was typed', async () => {
    const { onSend } = setup({ error: 'Pick a target.' });
    expect(screen.getByRole('alert')).toHaveTextContent('Pick a target.');
    fireEvent.change(screen.getByLabelText('Handoff note'), { target: { value: 'kept' } });
    expect(screen.getByLabelText('Handoff note').value).toBe('kept');
    expect(onSend).not.toHaveBeenCalled();
  });
});
