import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WorkFilterBar from './WorkFilterBar';

const PROPS = {
  projects: [{ key: 'PLAT', name: 'Platform' }, { key: 'OPS', name: 'Ops' }],
  products: [{ key: 'wik', name: 'Wikantik' }], queues: [{ key: 'triage', name: 'Triage' }],
};

describe('WorkFilterBar', () => {
  it('shows the value', () => {
    render(<WorkFilterBar {...PROPS} onApply={vi.fn()}
      value={{ projects: ['PLAT'], status: ['ready'], label: ['ui', 'api'], assignee: 'me', q: 'cache', sort: 'due', watching: true }} />);
    expect(screen.getByLabelText('PLAT')).toBeChecked();
    expect(screen.getByLabelText('OPS')).not.toBeChecked();
    expect(screen.getByLabelText('Ready')).toBeChecked();
    expect(screen.getByLabelText('Labels')).toHaveValue('ui, api');
    expect(screen.getByLabelText('Assignee')).toHaveValue('me');
    expect(screen.getByLabelText('Assignee')).toHaveAttribute('placeholder', 'Display name or me');
    expect(screen.getByLabelText('Text')).toHaveValue('cache');
    expect(screen.getByLabelText('Sort')).toHaveValue('due');
    expect(screen.getByLabelText('Watching')).toBeChecked();
  });

  it('apply emits the draft', () => {
    const onApply = vi.fn();
    render(<WorkFilterBar {...PROPS} onApply={onApply} value={{ status: ['ready'] }} />);
    fireEvent.click(screen.getByLabelText('OPS'));
    fireEvent.click(screen.getByLabelText('Review'));
    fireEvent.change(screen.getByLabelText('Labels'), { target: { value: 'ui, api' } });
    fireEvent.change(screen.getByLabelText('Reporter'), { target: { value: 'Ada Lovelace' } });
    fireEvent.change(screen.getByLabelText('Queue'), { target: { value: 'triage' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(onApply).toHaveBeenCalledWith({
      status: ['ready', 'review'], projects: ['OPS'], label: ['ui', 'api'], reporter: 'Ada Lovelace', queue: 'triage',
    });
  });

  it('clear emits an empty filter', () => {
    const onApply = vi.fn();
    render(<WorkFilterBar {...PROPS} onApply={onApply} value={{ status: ['ready'] }} />);
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(onApply).toHaveBeenCalledWith({});
  });

  it('a sprint id is shown when given', () => {
    render(<WorkFilterBar {...PROPS} onApply={vi.fn()} value={{ sprint: '12' }} />);
    expect(screen.getByLabelText('Sprint')).toHaveValue('12');
    expect(screen.getByRole('option', { name: 'Sprint 12' })).toBeInTheDocument();
  });
});
