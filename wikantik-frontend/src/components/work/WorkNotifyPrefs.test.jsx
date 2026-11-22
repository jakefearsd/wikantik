import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../api/work', () => ({ getNotifyPrefs: vi.fn(), setNotifyPrefs: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { getNotifyPrefs, setNotifyPrefs } from '../../api/work';
import WorkNotifyPrefs from './WorkNotifyPrefs';

beforeEach(() => vi.clearAllMocks());

describe('WorkNotifyPrefs', () => {
  it('theChoiceIsHiddenWhenEmailIsOff', async () => {
    getNotifyPrefs.mockResolvedValue({ email: 'off', emailAvailable: false, webhooksAvailable: false });
    render(<WorkNotifyPrefs />);
    expect(await screen.findByText('Email notifications are off on this wiki.')).toBeInTheDocument();
    expect(screen.queryByRole('radio')).toBeNull();
  });

  it('choosingDailySavesIt', async () => {
    getNotifyPrefs.mockResolvedValue({ email: 'each', emailAvailable: true, webhooksAvailable: false });
    setNotifyPrefs.mockResolvedValue({ email: 'daily', emailAvailable: true, webhooksAvailable: false });
    render(<WorkNotifyPrefs />);
    expect(await screen.findByLabelText('Each update, batched')).toBeChecked();
    fireEvent.click(screen.getByLabelText('Once a day'));
    await waitFor(() => expect(setNotifyPrefs).toHaveBeenCalledWith('daily'));
    await waitFor(() => expect(screen.getByLabelText('Once a day')).toBeChecked());
  });

  it('aFailedSaveKeepsTheChoiceAndSaysSo', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getNotifyPrefs.mockResolvedValue({ email: 'each', emailAvailable: true });
    setNotifyPrefs.mockRejectedValue(new Error('nope'));
    render(<WorkNotifyPrefs />);
    fireEvent.click(await screen.findByLabelText('Off'));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('nope'));
    expect(screen.getByLabelText('Each update, batched')).toBeChecked();
  });

  it('aFailedLoadOffersRetry', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getNotifyPrefs.mockRejectedValueOnce(new Error('down'));
    render(<WorkNotifyPrefs />);
    getNotifyPrefs.mockResolvedValue({ email: 'off', emailAvailable: true });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByLabelText('Off')).toBeChecked();
  });
});
