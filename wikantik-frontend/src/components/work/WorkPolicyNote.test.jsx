import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../api/work', () => ({ getItemPolicy: vi.fn(), acknowledgeItem: vi.fn() }));
import { getItemPolicy, acknowledgeItem } from '../../api/work';
import WorkPolicyNote from './WorkPolicyNote';

const policy = (effective, extra = {}) => ({
  effective: { autonomy: 'delegated', allowDirect: false, doneMeans: 'merged', ...effective },
  ackRequired: false, ackWaiting: false, acknowledgedBy: null, acknowledgedAt: null, ...extra,
});

beforeEach(() => vi.clearAllMocks());

describe('WorkPolicyNote', () => {
  it('says the effective level is inherited when the item sets none', async () => {
    getItemPolicy.mockResolvedValue(policy());
    render(<WorkPolicyNote itemKey="PLAT-7" />);
    const note = await screen.findByTestId('policy-note');
    expect(note).toHaveTextContent('Effective level: Delegated (inherited)');
    expect(note).toHaveTextContent('Direct commits by agents are not allowed.');
    expect(note).toHaveTextContent('Done means merged.');
  });

  it('says so when the item sets the level itself', async () => {
    getItemPolicy.mockResolvedValue(policy({ autonomy: 'supervised' }));
    render(<WorkPolicyNote itemKey="PLAT-7" ownAutonomy="supervised" />);
    expect(await screen.findByTestId('policy-note')).toHaveTextContent('Effective level: Supervised (set on this item)');
  });

  it("names the product's ceiling when it caps what the item asks for", async () => {
    getItemPolicy.mockResolvedValue(policy({ autonomy: 'delegated', allowDirect: true, doneMeans: 'in_production' }));
    render(<WorkPolicyNote itemKey="PLAT-7" ownAutonomy="autonomous" />);
    const note = await screen.findByTestId('policy-note');
    expect(note).toHaveTextContent("Effective level: Delegated (this item says Autonomous; the product's ceiling caps it)");
    expect(note).toHaveTextContent('Direct commits by agents are allowed.');
    expect(note).toHaveTextContent('Done means running in production.');
  });

  it('offers Acknowledge on an item an agent closed alone, and reloads after', async () => {
    getItemPolicy.mockResolvedValueOnce(policy({ autonomy: 'autonomous' }, { ackRequired: true, ackWaiting: true }))
      .mockResolvedValue(policy({ autonomy: 'autonomous' },
        { ackRequired: true, ackWaiting: false, acknowledgedBy: 'Mia Member', acknowledgedByName: 'Mia Member', acknowledgedAt: '2026-11-17T10:00:00Z' }));
    acknowledgeItem.mockResolvedValue({});
    const onChanged = vi.fn();
    render(<WorkPolicyNote itemKey="PLAT-7" canAcknowledge onChanged={onChanged} />);
    expect(await screen.findByRole('status')).toHaveTextContent('An agent closed this with no person in the path.');
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge' }));
    await waitFor(() => expect(acknowledgeItem).toHaveBeenCalledWith('PLAT-7'));
    expect(await screen.findByText(/acknowledged by Mia Member/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).toBeNull();
    expect(onChanged).toHaveBeenCalled();
  });

  it('shows the state without the button to someone who may not acknowledge', async () => {
    getItemPolicy.mockResolvedValue(policy({}, { ackRequired: true, ackWaiting: true }));
    render(<WorkPolicyNote itemKey="PLAT-7" />);
    expect(await screen.findByRole('status')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).toBeNull();
  });

  it('shows a refusal in place', async () => {
    getItemPolicy.mockResolvedValue(policy({}, { ackRequired: true, ackWaiting: true }));
    acknowledgeItem.mockRejectedValue(new Error('A person acknowledges work closed without one.'));
    render(<WorkPolicyNote itemKey="PLAT-7" canAcknowledge />);
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A person acknowledges work closed without one.');
  });

  it('renders nothing when the policy cannot be read', async () => {
    getItemPolicy.mockRejectedValue(new Error('boom'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { container } = render(<WorkPolicyNote itemKey="PLAT-7" />);
    await waitFor(() => expect(getItemPolicy).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
    warn.mockRestore();
  });
});
