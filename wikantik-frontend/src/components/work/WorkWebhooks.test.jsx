import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('../../api/work', () => ({
  getMe: vi.fn(), listWebhooks: vi.fn(), addWebhook: vi.fn(), updateWebhook: vi.fn(), removeWebhook: vi.fn(),
}));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { getMe, listWebhooks, addWebhook, updateWebhook, removeWebhook } from '../../api/work';
import WorkWebhooks from './WorkWebhooks';

const HOOK = {
  id: 4, format: 'generic', target: 'https://hooks.example.com/…', eventTypes: ['status'], ownerName: 'Ana Alvarez',
  createdAt: '2026-11-01T10:00:00Z', lastDeliveredAt: null, failingSince: null, lastError: null, disabledAt: null, paused: false,
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  getMe.mockResolvedValue({ projects: [], notify: { emailAvailable: true, webhooksAvailable: true } });
  listWebhooks.mockResolvedValue([HOOK]);
});

const show = (props = {}) => render(<WorkWebhooks projectKey="PLAT" role="lead" {...props} />);

describe('WorkWebhooks', () => {
  it('theSecretIsShownOnceAfterAdding', async () => {
    addWebhook.mockResolvedValue({ webhook: { ...HOOK, id: 5 }, secret: 's3cr3t-value' });
    const logs = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'error')];
    show();
    fireEvent.change(await screen.findByLabelText('Address (https)'), { target: { value: 'https://hooks.example.com/x' } });
    fireEvent.click(screen.getByLabelText('status'));
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }));
    await waitFor(() => expect(addWebhook).toHaveBeenCalledWith('PLAT',
      { url: 'https://hooks.example.com/x', format: 'generic', eventTypes: ['status'] }));
    expect(await screen.findByText('s3cr3t-value')).toBeInTheDocument();
    expect(screen.getByText(/Copy this secret now. It cannot be shown again./)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('s3cr3t-value')).toBeNull();
    expect(document.body.textContent).not.toContain('s3cr3t-value');
    logs.forEach((l) => l.mock.calls.forEach((c) => expect(JSON.stringify(c)).not.toContain('s3cr3t-value')));
  });

  it('aMemberSeesNoWebhookSection', async () => {
    const { container } = show({ role: 'member' });
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(getMe).not.toHaveBeenCalled();
    expect(listWebhooks).not.toHaveBeenCalled();
  });

  it('anAdminWhoIsNoLeadSeesTheSection', async () => {
    show({ role: 'member', isAdmin: true });
    expect(await screen.findByRole('heading', { name: 'Webhooks' })).toBeInTheDocument();
  });

  it('hiddenWhenWebhooksAreOff', async () => {
    getMe.mockResolvedValue({ projects: [], notify: { emailAvailable: true, webhooksAvailable: false } });
    const { container } = show();
    await waitFor(() => expect(getMe).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(listWebhooks).not.toHaveBeenCalled();
  });

  it('aPausedWebhookSaysWhy', async () => {
    listWebhooks.mockResolvedValue([{ ...HOOK, paused: true }]);
    updateWebhook.mockResolvedValue({ ...HOOK, paused: false });
    show();
    expect(await screen.findByText('Its owner is no longer a lead; save it to take it over.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Take over' }));
    await waitFor(() => expect(updateWebhook).toHaveBeenCalledWith('PLAT', 4, { eventTypes: ['status'] }));
  });

  it('listsFailureAndSwitchedOffState', async () => {
    listWebhooks.mockResolvedValue([{ ...HOOK, failingSince: '2026-11-20T10:00:00Z', lastError: 'HTTP 500', disabledAt: '2026-11-21T10:00:00Z' }]);
    show();
    expect(await screen.findByText(/HTTP 500/)).toBeInTheDocument();
    expect(screen.getByText(/Switched off/)).toBeInTheDocument();
    expect(screen.getByText('Owner: Ana Alvarez')).toBeInTheDocument();
    updateWebhook.mockResolvedValue({ ...HOOK });
    fireEvent.click(screen.getByRole('button', { name: 'Turn on' }));
    await waitFor(() => expect(updateWebhook).toHaveBeenCalledWith('PLAT', 4, { enabled: true }));
  });

  it('removingAsksFirst', async () => {
    removeWebhook.mockResolvedValue(undefined);
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    expect(removeWebhook).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Yes, remove it' }));
    await waitFor(() => expect(removeWebhook).toHaveBeenCalledWith('PLAT', 4));
  });

  it('aFailedListKeepsNothingFakeAndOffersRetry', async () => {
    listWebhooks.mockRejectedValueOnce(new Error('down'));
    show();
    listWebhooks.mockResolvedValue([HOOK]);
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('https://hooks.example.com/…')).toBeInTheDocument();
  });

  it('aRefusedAddShowsTheReasonAndNoSecret', async () => {
    addWebhook.mockRejectedValue(Object.assign(new Error('Give an https address.'), { status: 400 }));
    show();
    fireEvent.change(await screen.findByLabelText('Address (https)'), { target: { value: 'https://10.0.0.1/x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }));
    expect(await screen.findByText('Give an https address.')).toBeInTheDocument();
  });
});
