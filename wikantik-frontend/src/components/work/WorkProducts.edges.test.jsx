import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listProducts: vi.fn(), createProduct: vi.fn(), updateProduct: vi.fn(), archiveProduct: vi.fn(), productHistory: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn(() => ({ capabilities: { work: true }, loading: false })) }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listProducts, createProduct, updateProduct, archiveProduct, productHistory } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import WorkProducts from './WorkProducts';

const FULL = {
  id: 3, key: 'wikantik', name: 'Wikantik', repoUrl: 'https://example.com/r.git', defaultBranch: 'main',
  wikiCluster: 'Wikantik', autonomyCeiling: 'supervised', deployGate: 'agent', archivedAt: null,
};
const BARE = { id: 4, key: 'bare', archivedAt: null };

function renderProducts() {
  useAuth.mockReturnValue({ user: { authenticated: true, roles: ['Admin'] }, loading: false });
  render(
    <MemoryRouter initialEntries={['/work/products']}>
      <Routes>
        <Route path="/work/products" element={<WorkProducts />} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}
const refusal = (message, status) => Object.assign(new Error(message), { status });

beforeEach(() => {
  vi.clearAllMocks();
  listProducts.mockResolvedValue([FULL]);
  createProduct.mockResolvedValue({});
  productHistory.mockResolvedValue({ events: [] });
  updateProduct.mockResolvedValue({});
  archiveProduct.mockResolvedValue(undefined);
});

describe('WorkProducts edges', () => {
  it('describes a product with a repository, branch and cluster on one line, with its own ceiling and gate', async () => {
    renderProducts();
    expect(await screen.findByText('https://example.com/r.git · branch main · cluster Wikantik')).toBeInTheDocument();
    expect(screen.getByText('Agents may do at most: Supervised. Deployments are approved by an agent or a person.')).toBeInTheDocument();
  });

  it('says plainly that a bare product has no repository or cluster, and shows the default ceiling and gate', async () => {
    listProducts.mockResolvedValue([BARE]);
    renderProducts();
    expect(await screen.findByText('No repository or cluster set')).toBeInTheDocument();
    expect(screen.getByText('Agents may do at most: Delegated. Deployments are approved by a person.')).toBeInTheDocument();
  });

  it('treats a missing product list as empty', async () => {
    listProducts.mockResolvedValue(null);
    renderProducts();
    expect(await screen.findByText('No products yet.')).toBeInTheDocument();
  });

  it('does not list archived products', async () => {
    listProducts.mockResolvedValue([FULL, { ...BARE, key: 'gone', name: 'Gone', archivedAt: '2026-01-01T00:00:00Z' }]);
    renderProducts();
    expect(await screen.findByText('Wikantik')).toBeInTheDocument();
    expect(screen.queryByText('Gone')).toBeNull();
  });

  it('shows a load failure without a message as the fallback sentence', async () => {
    listProducts.mockRejectedValue(new Error(''));
    renderProducts();
    expect(await screen.findByText('Could not load the products.')).toBeInTheDocument();
  });

  it('asks for a name when the key is valid and the name is blank', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'good-key' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name.');
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('creates a product with blanks sent as null, trimmed values, and the chosen ceiling and gate', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'new-prod' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: ' New ' } });
    fireEvent.change(screen.getByLabelText('Repository URL'), { target: { value: '  ' } });
    fireEvent.change(screen.getByLabelText('Wiki cluster'), { target: { value: ' Cl ' } });
    fireEvent.change(screen.getByLabelText('Agents may do at most'), { target: { value: 'autonomous' } });
    fireEvent.change(screen.getByLabelText('Deployments are approved by'), { target: { value: 'agent' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledWith({
      key: 'new-prod', name: 'New', repoUrl: null, defaultBranch: null, wikiCluster: 'Cl',
      autonomyCeiling: 'autonomous', deployGate: 'agent',
    }));
    expect(toast.success).toHaveBeenCalledWith('Created new-prod');
    await waitFor(() => expect(listProducts).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('button', { name: 'New product' })).toBeInTheDocument();
  });

  it('cancelling the new product form brings back the New product button and creates nothing', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'New product' })).toBeInTheDocument();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('shows a non-403 save failure on the form in the server words and keeps what was typed', async () => {
    createProduct.mockRejectedValue(refusal('Key already in use.', 409));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'wikantik' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Dup' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('Key already in use.')).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toHaveValue('Dup');
    expect(screen.getByRole('button', { name: 'Create product' })).not.toBeDisabled();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('falls back to a plain sentence when a save fails without a message', async () => {
    updateProduct.mockRejectedValue(new Error(''));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    expect(await screen.findByText('Could not save the product.')).toBeInTheDocument();
  });

  it('editing pre-fills every field of a full product and saves them unchanged', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(screen.getByLabelText('Repository URL')).toHaveValue('https://example.com/r.git');
    expect(screen.getByLabelText('Default branch')).toHaveValue('main');
    expect(screen.getByLabelText('Wiki cluster')).toHaveValue('Wikantik');
    expect(screen.getByLabelText('Agents may do at most')).toHaveValue('supervised');
    expect(screen.getByLabelText('Deployments are approved by')).toHaveValue('agent');
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledWith('wikantik', {
      name: 'Wikantik', repoUrl: 'https://example.com/r.git', defaultBranch: 'main', wikiCluster: 'Wikantik',
      autonomyCeiling: 'supervised', deployGate: 'agent',
    }));
    expect(toast.success).toHaveBeenCalledWith('Product saved');
  });

  it('editing a bare product starts with empty text fields and the default ceiling and gate, and sends blanks as null', async () => {
    listProducts.mockResolvedValue([BARE]);
    renderProducts();
    await screen.findByText('bare');
    fireEvent.click(screen.getByRole('button', { name: 'Edit bare' }));
    expect(screen.getByLabelText('Name')).toHaveValue('');
    expect(screen.getByLabelText('Repository URL')).toHaveValue('');
    expect(screen.getByLabelText('Agents may do at most')).toHaveValue('delegated');
    expect(screen.getByLabelText('Deployments are approved by')).toHaveValue('human');
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Bare' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledWith('bare', {
      name: 'Bare', repoUrl: null, defaultBranch: null, wikiCluster: null, autonomyCeiling: 'delegated', deployGate: 'human',
    }));
  });

  it('cancelling an edit shows the product row again', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Edit wikantik' })).toBeInTheDocument();
    expect(updateProduct).not.toHaveBeenCalled();
  });

  it('cancelling the archive confirmation archives nothing', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Archive wikantik' }));
    const dialog = screen.getByRole('alertdialog');
    expect(within(dialog).getByText(/It will no longer be offered on items/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(archiveProduct).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Archive wikantik' })).toBeInTheDocument();
  });

  it('shows the refusal in the confirmation when archiving fails, and keeps it open', async () => {
    archiveProduct.mockRejectedValue(refusal('Product still has open items.', 409));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Archive wikantik' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive wikantik' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('Product still has open items.');
    expect(toast.error).not.toHaveBeenCalled();
    expect(listProducts).toHaveBeenCalledTimes(1);
  });

  it('uses a plain default when archiving fails without a message', async () => {
    archiveProduct.mockRejectedValue(new Error(''));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Archive wikantik' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive wikantik' }));
    expect(await within(screen.getByRole('alertdialog')).findByRole('alert')).toHaveTextContent('Could not archive the product.');
  });
});
