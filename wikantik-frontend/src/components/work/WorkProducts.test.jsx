import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({
  listProducts: vi.fn(), createProduct: vi.fn(), updateProduct: vi.fn(), archiveProduct: vi.fn(), productHistory: vi.fn(),
  enableHook: vi.fn(), disableHook: vi.fn(),
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn(() => ({ capabilities: { work: true }, loading: false })) }));
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
vi.mock('../../hooks/useToast', () => ({ useToast: () => toast }));
import { listProducts, createProduct, updateProduct, archiveProduct, productHistory, enableHook, disableHook } from '../../api/work';
import { useCapabilities } from '../../hooks/useCapabilities';
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
  productHistory.mockResolvedValue({ events: [] });
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
    expect(screen.getAllByText(/Agents may do at most: Delegated\. Deployments are approved by a person\./).length).toBeGreaterThan(0);
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

  it('shows the server reason inline on the form on 403, not as a toast', async () => {
    createProduct.mockRejectedValue(Object.assign(new Error('Admins only.'), { status: 403 }));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    fireEvent.change(screen.getByLabelText('Key'), { target: { value: 'okay' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'N' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('Admins only.')).toHaveAttribute('role', 'alert');
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('says what a product key may be and that it cannot be changed later', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    expect(screen.getByText('2 to 40 lowercase letters, digits and hyphens, starting with a letter. It cannot be changed later.')).toBeInTheDocument();
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

  it('leads back to the Work home', async () => {
    renderProducts();
    expect(await screen.findByRole('link', { name: 'Work' })).toHaveAttribute('href', '/work');
  });

  it("shows the product's history when editing", async () => {
    const recent = new Date(Date.now() - 2 * 3600 * 1000).toISOString();
    productHistory.mockResolvedValue({ events: [
      { at: recent, actor: 'Ada', field: 'deploy_gate', from: 'human', to: 'agent' },
      { at: recent, actor: 'Ada', field: 'archived', from: null, to: recent },
    ] });
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(await screen.findByText('2h ago · Ada changed Deployments are approved by from A person approves deployments to An agent may approve deployments')).toBeInTheDocument();
    expect(screen.getByText('2h ago · Ada archived the product')).toBeInTheDocument();
    expect(productHistory).toHaveBeenCalledWith('wikantik');
  });

  it('says when no changes are recorded', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(await screen.findByText('No changes recorded yet.')).toBeInTheDocument();
  });

  it('a failed history load shows the load error, not an empty list', async () => {
    productHistory.mockRejectedValue(new Error('history down'));
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(await screen.findByText('history down')).toHaveAttribute('role', 'alert');
    expect(screen.queryByText('No changes recorded yet.')).toBeNull();
  });

  it('does not ask for history when creating a product', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    expect(productHistory).not.toHaveBeenCalled();
  });

  it('history uses the form labels for fields and values', async () => {
    const recent = new Date(Date.now() - 2 * 3600 * 1000).toISOString();
    productHistory.mockResolvedValue({ events: [
      { at: recent, actor: 'Ada', field: 'autonomy_ceiling', from: 'delegated', to: 'autonomous' },
      { at: recent, actor: 'Ada', field: 'repo_url', from: null, to: 'https://x.test/r' },
      { at: recent, actor: 'Ada', field: 'wiki_cluster', from: 'a', to: 'b' },
    ] });
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(await screen.findByText('2h ago · Ada changed Agents may do at most from Delegated to Autonomous')).toBeInTheDocument();
    expect(screen.getByText('2h ago · Ada changed Repository URL from nothing to https://x.test/r')).toBeInTheDocument();
    expect(screen.getByText('2h ago · Ada changed Wiki cluster from a to b')).toBeInTheDocument();
  });

  it('explains what a product is', async () => {
    renderProducts();
    expect(await screen.findByText('A product is something you build and ship: a repository and its default branch. Items target a product, and deployments ship its versions.')).toBeInTheDocument();
  });

  it('hides New product while a create or edit form is open', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    fireEvent.click(screen.getByRole('button', { name: 'Edit wikantik' }));
    expect(screen.queryByRole('button', { name: 'New product' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    expect(screen.queryByRole('button', { name: 'New product' })).toBeNull();
  });

  describe('code-host hook', () => {
    const SECRET = 'a'.repeat(64);
    const withHook = (hook) => ({ ...PRODUCT, hook });
    const on = (extra = {}) => withHook({ enabled: true, enabledAt: '2026-01-01T00:00:00Z', lastReceivedAt: '2026-01-02T00:00:00Z', ...extra });
    const off = withHook({ enabled: false, enabledAt: null, lastReceivedAt: null });
    beforeEach(() => {
      useCapabilities.mockReturnValue({ capabilities: { work: true, workCodeHost: true }, loading: false });
      enableHook.mockResolvedValue({ secret: SECRET, githubUrl: '/api/work/hooks/wikantik/github', genericUrl: '/api/work/hooks/wikantik/generic' });
      disableHook.mockResolvedValue(undefined);
    });
    afterEach(() => useCapabilities.mockReturnValue({ capabilities: { work: true }, loading: false }));

    it('shows no hook controls when the wiki has code-host hooks off or the row carries no hook', async () => {
      useCapabilities.mockReturnValue({ capabilities: { work: true, workCodeHost: false }, loading: false });
      listProducts.mockResolvedValue([off]);
      renderProducts();
      await screen.findByText('Wikantik');
      expect(screen.queryByText(/Code host:/)).toBeNull();
      expect(screen.queryByRole('button', { name: /hook/i })).toBeNull();
    });

    it('shows nothing for a product whose JSON has no hook', async () => {
      listProducts.mockResolvedValue([PRODUCT]);
      renderProducts();
      await screen.findByText('Wikantik');
      expect(screen.queryByText(/Code host:/)).toBeNull();
    });

    it('says off, then turning on is confirmed and shows the address and the secret once', async () => {
      listProducts.mockResolvedValue([off]);
      renderProducts();
      expect(await screen.findByText('Code host: off')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Turn on the hook for wikantik' }));
      expect(enableHook).not.toHaveBeenCalled();
      fireEvent.click(screen.getByRole('button', { name: 'Yes, turn on' }));
      const panel = await screen.findByRole('dialog', { name: 'Code-host hook secret' });
      expect(enableHook).toHaveBeenCalledWith('wikantik');
      expect(panel).toHaveTextContent(SECRET);
      expect(panel).toHaveTextContent(`${window.location.origin}/api/work/hooks/wikantik/github`);
      expect(panel).toHaveTextContent('cannot be shown again');
      expect(panel).toHaveTextContent('Shown once. In GitHub: Settings → Webhooks → Add webhook; content type application/json; events: Pushes, Pull requests, Pull request reviews.');
      expect(panel).toHaveTextContent(`${window.location.origin}/api/work/hooks/wikantik/generic`);
      expect(within(panel).getByRole('button', { name: 'Copy GitHub address' })).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Copy generic address' })).toBeInTheDocument();
      expect(listProducts).toHaveBeenCalledTimes(2);
      fireEvent.click(within(panel).getByRole('button', { name: 'Done' }));
      expect(screen.queryByText(SECRET, { exact: false })).toBeNull();
      expect(document.body.textContent).not.toContain(SECRET);
    });

    it('never logs the secret', async () => {
      const spies = ['log', 'warn', 'error', 'info'].map((m) => vi.spyOn(console, m).mockImplementation(() => {}));
      listProducts.mockResolvedValue([off]);
      renderProducts();
      fireEvent.click(await screen.findByRole('button', { name: 'Turn on the hook for wikantik' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, turn on' }));
      await screen.findByRole('dialog', { name: 'Code-host hook secret' });
      for (const s of spies) { expect(JSON.stringify(s.mock.calls)).not.toContain(SECRET); s.mockRestore(); }
    });

    it('an enabled hook shows its last delivery, and a new secret is confirmed then shown once', async () => {
      listProducts.mockResolvedValue([on()]);
      renderProducts();
      expect(await screen.findByText(/Code host: on · last delivery /)).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'New secret for wikantik' }));
      expect(screen.getByRole('alertdialog')).toHaveTextContent('The old secret stops working at once');
      fireEvent.click(screen.getByRole('button', { name: 'Yes, make a new secret' }));
      expect(await screen.findByRole('dialog', { name: 'Code-host hook secret' })).toHaveTextContent(SECRET);
    });

    it('turning off asks first, can be cancelled, and then reloads', async () => {
      listProducts.mockResolvedValue([on()]);
      renderProducts();
      fireEvent.click(await screen.findByRole('button', { name: 'Turn off the hook for wikantik' }));
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(disableHook).not.toHaveBeenCalled();
      fireEvent.click(screen.getByRole('button', { name: 'Turn off the hook for wikantik' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, turn off' }));
      await waitFor(() => expect(disableHook).toHaveBeenCalledWith('wikantik'));
      await waitFor(() => expect(listProducts).toHaveBeenCalledTimes(2));
    });

    it('a refusal is said in words, shows no secret and keeps the confirmation open', async () => {
      enableHook.mockRejectedValue(new Error('Only a wiki administrator may do that.'));
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      listProducts.mockResolvedValue([off]);
      renderProducts();
      fireEvent.click(await screen.findByRole('button', { name: 'Turn on the hook for wikantik' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, turn on' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Only a wiki administrator');
      expect(screen.queryByRole('dialog', { name: 'Code-host hook secret' })).toBeNull();
      warn.mockRestore();
    });

    it('a failed reload keeps the products on screen with an inline error', async () => {
      listProducts.mockResolvedValueOnce([off]).mockRejectedValue(new Error('down'));
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      renderProducts();
      fireEvent.click(await screen.findByRole('button', { name: 'Turn on the hook for wikantik' }));
      fireEvent.click(screen.getByRole('button', { name: 'Yes, turn on' }));
      expect(await screen.findByText('down')).toBeInTheDocument();
      expect(screen.getByText('Wikantik')).toBeInTheDocument();
      warn.mockRestore();
    });
  });

  it('links each product to its delivery figures', async () => {
    renderProducts();
    await screen.findByText('Wikantik');
    expect(screen.getByRole('link', { name: 'Delivery figures for wikantik' })).toHaveAttribute('href', '/work/products/wikantik/dora');
  });
});
