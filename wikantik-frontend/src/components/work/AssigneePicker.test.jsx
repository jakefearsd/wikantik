import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AssigneePicker from './AssigneePicker';

const MEMBERS = [
  { principal: 'ana-login', principalKind: 'user', role: 'lead', displayName: 'Ana Alvarez' },
  { principal: 'bob-login', principalKind: 'user', role: 'member', displayName: 'Bob Brown' },
  { principal: '3', principalKind: 'agent', role: 'member', displayName: 'codex@docker2' },
  { principal: 'ops', principalKind: 'group', role: 'member', displayName: 'ops' },
];
const QUEUES = [
  { key: 'implementer', name: 'Implementer', retiredAt: null },
  { key: 'reviewer', name: 'Reviewer', retiredAt: null },
  { key: 'old', name: 'Old queue', retiredAt: '2026-10-01T00:00:00Z' },
];
const optionTexts = () => [...screen.getByLabelText('Assignee').querySelectorAll('option')].map((o) => o.textContent);

describe('AssigneePicker', () => {
  it('offersMembersAndActiveQueues', () => {
    render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{}} onChange={() => {}} />);
    expect(optionTexts()).toEqual(['Unassigned', 'Ana Alvarez', 'Bob Brown', 'Queue · Implementer', 'Queue · Reviewer']);
  });

  it('doesNotOfferReporters', () => {
    const withReporter = [...MEMBERS, { principal: 'rita-login', principalKind: 'user', role: 'reporter', displayName: 'Rita Reporter' }];
    render(<AssigneePicker members={withReporter} queues={QUEUES} value={{}} onChange={() => {}} />);
    expect(optionTexts().join('|')).not.toContain('Rita Reporter');
    expect(optionTexts()).toContain('Bob Brown');
  });

  it('agentsAreNotOffered, nor groups or retired queues', () => {
    render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{}} onChange={() => {}} />);
    const texts = optionTexts().join('|');
    expect(texts).not.toContain('codex@docker2');
    expect(texts).not.toContain('ops');
    expect(texts).not.toContain('Old queue');
  });

  it('choosingQueueClearsPerson: a queue is sent alone, a person is sent alone', () => {
    const onChange = vi.fn();
    render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{ assignee: 'bob-login' }} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'queue:reviewer' } });
    expect(onChange).toHaveBeenLastCalledWith({ queue: 'reviewer' });
    expect(onChange.mock.calls[0][0]).not.toHaveProperty('assignee');
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'ana-login' } });
    expect(onChange).toHaveBeenLastCalledWith({ assignee: 'ana-login' });
    expect(onChange.mock.calls[1][0]).not.toHaveProperty('queue');
  });

  it('shows the current queue even when it is retired, and the current person even when no longer a member', () => {
    const { rerender } = render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{ queue: 'old' }}
      labels={{ queueName: 'Old queue' }} onChange={() => {}} />);
    expect(screen.getByLabelText('Assignee').value).toBe('queue:old');
    expect(screen.getByRole('option', { name: 'Queue · Old queue' })).toBeInTheDocument();
    rerender(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{ assignee: 'gone' }}
      labels={{ assigneeName: 'Gail G' }} onChange={() => {}} />);
    expect(screen.getByRole('option', { name: 'Gail G' })).toBeInTheDocument();
  });

  it('unassigning a queued item clears the queue; allowNone=false offers no Unassigned', () => {
    const onChange = vi.fn();
    const { rerender } = render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{ queue: 'reviewer' }} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
    expect(onChange).toHaveBeenLastCalledWith({ queue: null });
    rerender(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{}} allowNone={false} onChange={onChange} ariaLabel="Assignee" />);
    expect(optionTexts()).not.toContain('Unassigned');
  });

  it('names a queue by its key when it has no name, and falls back to neutral words for a vanished target', () => {
    const { rerender } = render(<AssigneePicker members={[]} queues={[{ key: 'triage' }]} value={{ queue: 'gone' }} onChange={() => {}} />);
    expect(optionTexts()).toContain('Queue · triage');
    expect(screen.getByRole('option', { name: 'Queue · a queue' })).toBeInTheDocument();
    rerender(<AssigneePicker members={[]} queues={[]} value={{ assignee: 'gone' }} onChange={() => {}} />);
    expect(screen.getByRole('option', { name: 'Former member' })).toBeInTheDocument();
  });

  it('reports nothing when the empty choice is picked and unassigning is not allowed', () => {
    const onChange = vi.fn();
    render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{}} allowNone={false} placeholder="Pick someone" onChange={onChange} />);
    expect(optionTexts()[0]).toBe('Pick someone');
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'ana-login' } });
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith({ assignee: 'ana-login' });
  });

  it('unassigning a person clears the assignee', () => {
    const onChange = vi.fn();
    render(<AssigneePicker members={MEMBERS} queues={QUEUES} value={{ assignee: 'bob-login' }} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: '' } });
    expect(onChange).toHaveBeenCalledWith({ assignee: null });
  });
});
