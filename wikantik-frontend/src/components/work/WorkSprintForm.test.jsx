import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../api/work', () => ({ createSprint: vi.fn() }));
import { createSprint } from '../../api/work';
import WorkSprintForm from './WorkSprintForm';

const fill = (name, starts, ends) => {
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: name } });
  fireEvent.change(screen.getByLabelText('Starts on'), { target: { value: starts } });
  fireEvent.change(screen.getByLabelText('Ends on'), { target: { value: ends } });
};
const open = (props = {}) => render(<WorkSprintForm isOpen projectKey="PLAT" onClose={vi.fn()} {...props} />);

beforeEach(() => vi.clearAllMocks());

describe('WorkSprintForm', () => {
  it('an end before the start is refused before sending', () => {
    open();
    fill('Sprint 1', '2026-12-06', '2026-11-23');
    fireEvent.click(screen.getByRole('button', { name: 'Create sprint' }));
    expect(screen.getByText('The end date must be on or after the start date.')).toBeInTheDocument();
    expect(createSprint).not.toHaveBeenCalled();
  });

  it('requires a name and both dates', () => {
    open();
    fireEvent.click(screen.getByRole('button', { name: 'Create sprint' }));
    expect(screen.getByText('A sprint needs a name.')).toBeInTheDocument();
    expect(screen.getByText('Choose a start date.')).toBeInTheDocument();
    expect(screen.getByText('Choose an end date.')).toBeInTheDocument();
    expect(createSprint).not.toHaveBeenCalled();
  });

  it('sends the trimmed fields and reports the new sprint', async () => {
    const onCreated = vi.fn();
    const onClose = vi.fn();
    createSprint.mockResolvedValue({ id: 9, name: 'Sprint 1' });
    open({ onCreated, onClose });
    fill(' Sprint 1 ', '2026-11-23', '2026-12-06');
    fireEvent.change(screen.getByLabelText('Goal'), { target: { value: 'Ship it' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create sprint' }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: 9, name: 'Sprint 1' }));
    expect(createSprint).toHaveBeenCalledWith('PLAT', { name: 'Sprint 1', goal: 'Ship it', startsOn: '2026-11-23', endsOn: '2026-12-06' });
    expect(onClose).toHaveBeenCalled();
  });

  it("the server's 400 is shown on its field", async () => {
    createSprint.mockRejectedValue(Object.assign(new Error('bad'), { status: 400, body: { field: 'name', message: 'A sprint with that name exists.' } }));
    open();
    fill('Sprint 1', '2026-11-23', '2026-12-06');
    fireEvent.click(screen.getByRole('button', { name: 'Create sprint' }));
    expect(await screen.findByText('A sprint with that name exists.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create sprint' })).not.toBeDisabled();
  });

  it('shows an unplaced failure above the buttons', async () => {
    createSprint.mockRejectedValue(new Error('A person plans a sprint.'));
    open();
    fill('Sprint 1', '2026-11-23', '2026-12-06');
    fireEvent.click(screen.getByRole('button', { name: 'Create sprint' }));
    expect(await screen.findByText('A person plans a sprint.')).toBeInTheDocument();
  });
});
