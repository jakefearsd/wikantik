import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../api/work', () => ({ listProducts: vi.fn(), listDeployments: vi.fn(), requestDeployment: vi.fn() }));
import { listProducts, listDeployments, requestDeployment } from '../../api/work';
import WorkDeployments from './WorkDeployments';

const dep = (id, state, extra = {}) => ({
  id, product: 'wikantik', version: `2.5.${id}`, environment: 'production', state, requestedByName: 'codex',
  requestedByKind: 'agent', items: [{ key: 'PLAT-1', title: 'One', status: 'review' }], mayApprove: false, mayRun: true, ...extra,
});
const renderPage = () => render(<MemoryRouter><WorkDeployments /></MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  listProducts.mockResolvedValue([{ key: 'wikantik', name: 'Wikantik' }, { key: 'jakemon', name: 'Jakemon' },
    { key: 'old', name: 'Old', archived: true }]);
  listDeployments.mockResolvedValue({ deployments: [dep(26, 'requested'), dep(25, 'verified')] });
});

describe('WorkDeployments', () => {
  it('lists the first product\'s deployments with their state and who asked', async () => {
    renderPage();
    const first = (await screen.findByRole('link', { name: 'wikantik 2.5.26' })).closest('li');
    expect(screen.getByRole('link', { name: 'wikantik 2.5.26' })).toHaveAttribute('href', '/work/deployments/26');
    expect(within(first).getByText('waiting for approval')).toBeInTheDocument();
    expect(first).toHaveTextContent('requested by codex (agent) · 1 item');
    expect(screen.getByText('verified')).toBeInTheDocument();
    expect(listDeployments).toHaveBeenCalledWith('wikantik');
    expect(screen.queryByRole('option', { name: 'Old' })).toBeNull();
  });

  it('switches product', async () => {
    renderPage();
    await screen.findByRole('link', { name: 'wikantik 2.5.26' });
    listDeployments.mockResolvedValue({ deployments: [] });
    fireEvent.change(screen.getByLabelText('Product'), { target: { value: 'jakemon' } });
    await waitFor(() => expect(listDeployments).toHaveBeenLastCalledWith('jakemon'));
    expect(await screen.findByText('No deployments of this product that you can see.')).toBeInTheDocument();
  });

  it('requests a deployment carrying the named items, then reloads', async () => {
    requestDeployment.mockResolvedValue(dep(27, 'requested'));
    renderPage();
    await screen.findByRole('link', { name: 'wikantik 2.5.26' });
    fireEvent.click(screen.getByRole('button', { name: 'Request' }));
    fireEvent.click(screen.getByRole('button', { name: 'Request deployment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter the version.');
    fireEvent.change(screen.getByLabelText('Version'), { target: { value: '2.5.27' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request deployment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Name the items this version ships.');
    fireEvent.change(screen.getByLabelText('Items it ships'), { target: { value: 'PLAT-12, PLAT-15  PLAT-16' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request deployment' }));
    await waitFor(() => expect(requestDeployment).toHaveBeenCalledWith(
      { product: 'wikantik', version: '2.5.27', environment: null, items: ['PLAT-12', 'PLAT-15', 'PLAT-16'] }));
    await waitFor(() => expect(listDeployments).toHaveBeenCalledTimes(2));
    expect(screen.queryByLabelText('Version')).toBeNull();
  });

  it('shows a refusal in the form and keeps what was typed', async () => {
    requestDeployment.mockRejectedValue(Object.assign(new Error('PLAT-12 does not target this product.'), { status: 400 }));
    renderPage();
    await screen.findByRole('link', { name: 'wikantik 2.5.26' });
    fireEvent.click(screen.getByRole('button', { name: 'Request' }));
    fireEvent.change(screen.getByLabelText('Version'), { target: { value: '2.5.27' } });
    fireEvent.change(screen.getByLabelText('Items it ships'), { target: { value: 'PLAT-12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request deployment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('PLAT-12 does not target this product.');
    expect(screen.getByLabelText('Version')).toHaveValue('2.5.27');
  });

  it('says so when there is nothing to deploy', async () => {
    listProducts.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('There are no products to deploy.')).toBeInTheDocument();
  });

  it('sends the environment when one is typed and Cancel closes the form', async () => {
    requestDeployment.mockResolvedValue(dep(27, 'requested'));
    renderPage();
    await screen.findByRole('link', { name: 'wikantik 2.5.26' });
    fireEvent.click(screen.getByRole('button', { name: 'Request' }));
    expect(screen.queryByRole('button', { name: 'Request' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Version')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Request' }));
    fireEvent.change(screen.getByLabelText('Version'), { target: { value: ' 2.5.27 ' } });
    fireEvent.change(screen.getByLabelText('Environment'), { target: { value: ' staging ' } });
    fireEvent.change(screen.getByLabelText('Items it ships'), { target: { value: 'PLAT-12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request deployment' }));
    await waitFor(() => expect(requestDeployment).toHaveBeenCalledWith(
      { product: 'wikantik', version: '2.5.27', environment: 'staging', items: ['PLAT-12'] }));
  });

  it('gives a plain default when a request is refused with no reason', async () => {
    requestDeployment.mockRejectedValue({});
    renderPage();
    await screen.findByRole('link', { name: 'wikantik 2.5.26' });
    fireEvent.click(screen.getByRole('button', { name: 'Request' }));
    fireEvent.change(screen.getByLabelText('Version'), { target: { value: '2.5.27' } });
    fireEvent.change(screen.getByLabelText('Items it ships'), { target: { value: 'PLAT-12' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request deployment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not request the deployment.');
  });

  it('treats a failed product load as having no products', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    listProducts.mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText('There are no products to deploy.')).toBeInTheDocument();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('treats a failed deployment load as an empty list', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    listDeployments.mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText('No deployments of this product that you can see.')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('copes with a missing product list and a missing deployment list', async () => {
    listProducts.mockResolvedValue(undefined);
    const first = renderPage();
    expect(await screen.findByText('There are no products to deploy.')).toBeInTheDocument();
    first.unmount();
    listProducts.mockResolvedValue([{ key: 'wikantik' }]);
    listDeployments.mockResolvedValue(undefined);
    renderPage();
    expect(await screen.findByText('No deployments of this product that you can see.')).toBeInTheDocument();
    expect(screen.getByLabelText('Product')).toHaveTextContent('wikantik');
  });

  it('shows an unknown state as it is, an unnamed requester as someone, and the plural of items', async () => {
    listDeployments.mockResolvedValue({ deployments: [
      dep(30, 'mystery', { requestedByName: null, items: [{ key: 'A-1' }, { key: 'A-2' }] }),
    ] });
    renderPage();
    const row = (await screen.findByRole('link', { name: 'wikantik 2.5.30' })).closest('li');
    expect(within(row).getByText('mystery')).toBeInTheDocument();
    expect(row).toHaveTextContent('requested by someone · 2 items');
  });
});
