import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ getShippedIn: vi.fn() }));
import { getShippedIn } from '../../api/work';
import WorkShippedIn from './WorkShippedIn';

const renderIt = (props = {}) => render(<MemoryRouter><WorkShippedIn itemKey="PLAT-7" {...props} /></MemoryRouter>);

beforeEach(() => vi.clearAllMocks());

describe('WorkShippedIn', () => {
  it('names each deployment that carried the item, linked, separated by commas', async () => {
    getShippedIn.mockResolvedValue({ shippedIn: [
      { id: 4, product: 'wikantik', version: '2.5.26', environment: 'production' },
      { id: 5, product: 'wikantik', version: '2.5.27', environment: 'staging' },
    ] });
    renderIt();
    const line = await screen.findByTestId('shipped-in');
    expect(line).toHaveTextContent('Shipped in wikantik 2.5.26 (production), wikantik 2.5.27 (staging)');
    expect(screen.getByRole('link', { name: 'wikantik 2.5.27' })).toHaveAttribute('href', '/work/deployments/5');
    expect(getShippedIn).toHaveBeenCalledWith('PLAT-7');
  });

  it('shows nothing when the item has not shipped, or the reply is empty', async () => {
    getShippedIn.mockResolvedValue({ shippedIn: [] });
    const first = renderIt();
    await waitFor(() => expect(getShippedIn).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('shipped-in')).toBeNull();
    first.unmount();
    getShippedIn.mockResolvedValue(undefined);
    const { container } = renderIt();
    await waitFor(() => expect(getShippedIn).toHaveBeenCalledTimes(2));
    expect(container).toBeEmptyDOMElement();
  });

  it('shows nothing and logs when the lookup fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    getShippedIn.mockRejectedValue(new Error('boom'));
    const { container } = renderIt();
    await waitFor(() => expect(warn).toHaveBeenCalledWith(expect.stringContaining('WorkShippedIn'), expect.any(Error)));
    expect(container).toBeEmptyDOMElement();
    warn.mockRestore();
  });

  it('looks again when the refresh key changes', async () => {
    getShippedIn.mockResolvedValue({ shippedIn: [] });
    const { rerender } = renderIt({ refreshKey: 0 });
    await waitFor(() => expect(getShippedIn).toHaveBeenCalledTimes(1));
    getShippedIn.mockResolvedValue({ shippedIn: [{ id: 4, product: 'wikantik', version: '2.5.26', environment: 'production' }] });
    rerender(<MemoryRouter><WorkShippedIn itemKey="PLAT-7" refreshKey={1} /></MemoryRouter>);
    expect(await screen.findByTestId('shipped-in')).toBeInTheDocument();
    expect(getShippedIn).toHaveBeenCalledTimes(2);
  });
});
