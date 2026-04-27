import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AdminKgPolicyExplain from './AdminKgPolicyExplain';
import { api } from '../../api/client';

const explainResult = {
  canonical_id: 'personal-finance/budget-basics',
  page_name: 'BudgetBasics',
  cluster: 'personal-finance',
  system_page: false,
  frontmatter_override: null,
  cluster_policy: 'include',
  effective_action: 'include',
  exclusion_reason: null,
};

describe('AdminKgPolicyExplain', () => {
  beforeEach(() => {
    vi.spyOn(api.admin.kgPolicy, 'explain').mockResolvedValue(explainResult);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---- 1. Submitting calls explain with trimmed value ----

  it('submitting a query calls explain with the trimmed value', async () => {
    render(<AdminKgPolicyExplain />);

    fireEvent.change(screen.getByPlaceholderText(/Page name or canonical_id/i), {
      target: { value: '  BudgetBasics  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Explain/i }));

    await waitFor(() =>
      expect(api.admin.kgPolicy.explain).toHaveBeenCalledWith('BudgetBasics'),
    );
  });

  // ---- 2. Renders the four-step trace from the response ----

  it('renders the four-step trace from the API response', async () => {
    render(<AdminKgPolicyExplain />);

    fireEvent.change(screen.getByPlaceholderText(/Page name or canonical_id/i), {
      target: { value: 'BudgetBasics' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Explain/i }));

    await waitFor(() => expect(screen.getByText('BudgetBasics')).toBeInTheDocument());

    // Cluster row
    expect(screen.getByText('personal-finance')).toBeInTheDocument();

    // System page: no
    expect(screen.getByText('no')).toBeInTheDocument();

    // Frontmatter override: none
    expect(screen.getByText('(none)')).toBeInTheDocument();

    // Cluster policy
    expect(screen.getByText('include')).toBeInTheDocument();

    // Effective action — rendered as INCLUDE
    expect(screen.getByText('INCLUDE')).toBeInTheDocument();

    // Open page link
    const link = screen.getByRole('link', { name: /Open page/i });
    expect(link).toHaveAttribute('href', `/wiki/${encodeURIComponent('BudgetBasics')}`);
  });

  // ---- 3. API error surfaces as error message ----

  it('surfaces an API error as an error message', async () => {
    vi.spyOn(api.admin.kgPolicy, 'explain').mockRejectedValueOnce(
      new Error('page not found'),
    );

    render(<AdminKgPolicyExplain />);

    fireEvent.change(screen.getByPlaceholderText(/Page name or canonical_id/i), {
      target: { value: 'NonExistentPage' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Explain/i }));

    await waitFor(() =>
      expect(screen.getByText('page not found')).toBeInTheDocument(),
    );
  });
});
