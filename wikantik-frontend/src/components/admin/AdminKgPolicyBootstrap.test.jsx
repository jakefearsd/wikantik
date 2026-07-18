import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import AdminKgPolicyBootstrap from './AdminKgPolicyBootstrap';
import { api } from '../../api/client';

// Minimal corpus: one default-include cluster, one default-exclude cluster,
// one unknown cluster (not in either DEFAULT list → should be unchecked/skip).
const corpusClusters = [
  { cluster: 'personal-finance', page_count: 25 },
  { cluster: 'van-life',         page_count: 8  },
  { cluster: 'obscure-topic',    page_count: 3  },
];

describe('AdminKgPolicyBootstrap', () => {
  beforeEach(() => {
    vi.spyOn(api.admin.kgPolicy, 'listClusters').mockResolvedValue({
      clusters: corpusClusters,
    });
    vi.spyOn(api.admin.kgPolicy, 'bootstrap').mockResolvedValue({ ok: true });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---- 1. Pre-checks default include / exclude rows ----

  it('pre-checks default include / exclude rows correctly', async () => {
    render(<AdminKgPolicyBootstrap />);

    expect(await screen.findByText('personal-finance')).toBeInTheDocument();

    // personal-finance is a DEFAULT_INCLUDE cluster → Include radio selected
    const rows = document.querySelectorAll('tbody tr');
    const pfRow = Array.from(rows).find(r => r.textContent.includes('personal-finance'));
    const pfRadios = pfRow.querySelectorAll('input[type="radio"]');
    // [Include, Exclude, Skip]
    expect(pfRadios[0].checked).toBe(true);   // Include
    expect(pfRadios[1].checked).toBe(false);  // Exclude
    expect(pfRadios[2].checked).toBe(false);  // Skip

    // van-life is a DEFAULT_EXCLUDE cluster → Exclude radio selected
    const vlRow = Array.from(rows).find(r => r.textContent.includes('van-life'));
    const vlRadios = vlRow.querySelectorAll('input[type="radio"]');
    expect(vlRadios[0].checked).toBe(false);  // Include
    expect(vlRadios[1].checked).toBe(true);   // Exclude
    expect(vlRadios[2].checked).toBe(false);  // Skip

    // obscure-topic is in neither list → Skip radio selected
    const otRow = Array.from(rows).find(r => r.textContent.includes('obscure-topic'));
    const otRadios = otRow.querySelectorAll('input[type="radio"]');
    expect(otRadios[0].checked).toBe(false);  // Include
    expect(otRadios[1].checked).toBe(false);  // Exclude
    expect(otRadios[2].checked).toBe(true);   // Skip
  });

  // ---- 2. Submitting calls bootstrap with picked lists + reason ----

  it('Commit bootstrap calls api.admin.kgPolicy.bootstrap with correct include/exclude/reason', async () => {
    render(<AdminKgPolicyBootstrap />);

    expect(await screen.findByText('personal-finance')).toBeInTheDocument();

    // Default reason is pre-populated; leave as-is
    fireEvent.click(screen.getByRole('button', { name: /Commit bootstrap/i }));

    await waitFor(() => expect(api.admin.kgPolicy.bootstrap).toHaveBeenCalledOnce());

    const callArg = api.admin.kgPolicy.bootstrap.mock.calls[0][0];
    expect(callArg.include).toContain('personal-finance');
    expect(callArg.exclude).toContain('van-life');
    // obscure-topic is skipped — should not appear in either list
    expect(callArg.include).not.toContain('obscure-topic');
    expect(callArg.exclude).not.toContain('obscure-topic');
    expect(callArg.reason).toBe('bootstrap initial config');
  });

  // ---- 3. Confirmation banner shown after success ----

  it('shows confirmation banner after successful bootstrap', async () => {
    render(<AdminKgPolicyBootstrap />);

    expect(await screen.findByText('personal-finance')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Commit bootstrap/i }));

    expect(await screen.findByText(/Bootstrap applied/i)).toBeInTheDocument();

    expect(screen.getByRole('link', { name: /Open dashboard/i })).toBeInTheDocument();
  });
});
