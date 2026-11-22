import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../api/work', () => ({ getWatch: vi.fn(), setWatch: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { getWatch, setWatch } from '../../api/work';
import WorkWatchButton from './WorkWatchButton';

beforeEach(() => { vi.clearAllMocks(); vi.spyOn(console, 'warn').mockImplementation(() => {}); });

describe('WorkWatchButton', () => {
  it('mutingCallsSetWatch', async () => {
    getWatch.mockResolvedValue({ state: 'watching' });
    setWatch.mockResolvedValue({ state: 'muted' });
    render(<WorkWatchButton itemKey="PLAT-7" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Watching' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Mute this item' }));
    await waitFor(() => expect(setWatch).toHaveBeenCalledWith('PLAT-7', 'muted'));
    expect(await screen.findByRole('button', { name: 'Muted' })).toBeInTheDocument();
  });

  it('theMenuClosesOnEscapeAndItsItemsAreButtons', async () => {
    getWatch.mockResolvedValue({ state: 'none' });
    render(<WorkWatchButton itemKey="PLAT-7" />);
    const trigger = await screen.findByRole('button', { name: 'Watch' });
    fireEvent.click(trigger);
    const items = screen.getAllByRole('menuitem');
    expect(items).toHaveLength(3);
    items.forEach((i) => expect(i.tagName).toBe('BUTTON')); // reachable by Tab, activated by Enter or Space
    fireEvent.keyDown(items[0], { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(trigger).toHaveFocus();
  });

  it('showsWatchWhenNotWatching', async () => {
    getWatch.mockResolvedValue({ state: 'none' });
    render(<WorkWatchButton itemKey="PLAT-7" />);
    expect(await screen.findByRole('button', { name: 'Watch' })).toBeEnabled();
  });

  it('isDisabledOnAnArchivedProject', async () => {
    getWatch.mockResolvedValue({ state: 'none' });
    render(<WorkWatchButton itemKey="PLAT-7" archived />);
    expect(await screen.findByRole('button', { name: 'Watch' })).toBeDisabled();
  });

  it('aFailedSaveKeepsTheStateAndSays', async () => {
    getWatch.mockResolvedValue({ state: 'watching' });
    setWatch.mockRejectedValue(new Error('refused'));
    render(<WorkWatchButton itemKey="PLAT-7" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Watching' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Mute this item' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('refused'));
    expect(screen.getByRole('button', { name: 'Watching' })).toBeInTheDocument();
  });

  it('isAbsentWhenTheStateCannotBeLoaded', async () => {
    getWatch.mockRejectedValue(new Error('down'));
    const { container } = render(<WorkWatchButton itemKey="PLAT-7" />);
    await waitFor(() => expect(getWatch).toHaveBeenCalled());
    expect(container.querySelector('button')).toBeNull();
  });
});
