import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listProducts: vi.fn(), createProduct: vi.fn(), updateProduct: vi.fn(), archiveProduct: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listProducts, createProduct, updateProduct, archiveProduct } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';
import WorkProducts from './WorkProducts';

const PRODUCT = {
  id: 3, key: 'wikantik', name: 'Wikantik', repoUrl: 'https://example.com/r.git', defaultBranch: 'main',
  wikiCluster: 'Wikantik', autonomyCeiling: 'delegated', deployGate: 'human', archivedAt: null,
};

function renderProducts(admin = true, authLoading = false) {
  useAuth.mockReturnValue(authLoading ? { user: null, loading: true }
    : { user: { authenticated: true, roles: admin ? ['Admin'] : [] }, loading: false });
  render(
    <MemoryRouter initialEntries={['/work/products']}>
      <Routes>
        <Route path="/work/products" element={<WorkProducts />} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  listProducts.mockResolvedValue([PRODUCT]);
  createProduct.mockResolvedValue({});
  updateProduct.mockResolvedValue({});
  archiveProduct.mockResolvedValue(undefined);
});

describe('WorkProducts', () => {
  it('adminOnly', async () => {
    renderProducts(false);
    expect(await screen.findByText('HOME')).toBeInTheDocument();
    expect(listProducts).not.toHaveBeenCalled();
  });

  it('lists products with their ceiling and deploy gate, both live', async () => {
    renderProducts();
    expect(await screen.findByText('Wikantik')).toBeInTheDocument();
    expect(screen.getAllByText(/Autonomy ceiling delegated, deploy gate human\./).length).toBeGreaterThan(0);
    expect(screen.queryByText(/later release/)).toBeNull();
  });

  it('createValidatesKeyPattern', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    for (const bad of ['A', 'Bad', '1abc', 'a', 'has space', `a${'b'.repeat(40)}`]) {
      fireEvent.change(screen.getByLabelText('Key'), { target: { value: bad } });
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'N' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
      expect(await screen.findByText(/Key must start with a lowercase letter/)).toBeInTheDocument();
    }
    expect(createProduct).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'my-prod' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledWith(expect.objectContaining({ key: 'my-prod', name: 'N' })));
  });

  it('edits a product without allowing the key to change', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(screen.getByLabelText('Key')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Wikantik 2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalledWith('wikantik', expect.objectContaining({ name: 'Wikantik 2' })));
    expect(updateProduct.mock.calls[0][1]).not.toHaveProperty('key');
  });

  it('archives only after inline confirmation', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Archive wikantik' }));
    expect(archiveProduct).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, archive wikantik' }));
    await waitFor(() => expect(archiveProduct).toHaveBeenCalledWith('wikantik'));
  });

  it('shows an empty state and an error with Retry', async () => {
    listProducts.mockResolvedValueOnce([]);
    renderProducts();
    expect(await screen.findByText(/No products yet/)).toBeInTheDocument();
  });

  it('retries a failed load', async () => {
    listProducts.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce([PRODUCT]);
    renderProducts();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Wikantik')).toBeInTheDocument();
  });

  it('shows the server reason in a toast on 403', async () => {
    createProduct.mockRejectedValue(Object.assign(new Error('Admins only.'), { status: 403 }));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'okay' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'N' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Admins only.'));
  });

  it('does not redirect an admin while the signed-in user is still loading', async () => {
    renderProducts(true, true);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('HOME')).not.toBeInTheDocument();
    expect(listProducts).not.toHaveBeenCalled();
  });

  it('a double click on the archive confirmation archives once', async () => {
    let release;
    archiveProduct.mockReturnValue(new Promise((r) => { release = r; }));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Archive wikantik' }));
    const yes = screen.getByRole('button', { name: 'Yes, archive wikantik' });
    fireEvent.click(yes);
    fireEvent.click(yes);
    expect(archiveProduct).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() => expect(listProducts).toHaveBeenCalledTimes(2));
  });
});
