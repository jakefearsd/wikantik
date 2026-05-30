import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import GraphLoadingFallback from './GraphLoadingFallback.jsx';

describe('GraphLoadingFallback', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders a Spinner with role="status"', () => {
    render(<GraphLoadingFallback />);
    expect(screen.getByRole('status')).toBeTruthy();
  });

  it('spinner label is "Loading page graph…"', () => {
    render(<GraphLoadingFallback />);
    const spinner = screen.getByRole('status');
    expect(spinner.getAttribute('aria-label')).toBe('Loading page graph…');
  });

  it('does not show the slow hint initially', () => {
    render(<GraphLoadingFallback />);
    expect(screen.queryByText(/still working/i)).toBeNull();
  });

  it('shows slow hint after 2 seconds', async () => {
    vi.useFakeTimers();
    render(<GraphLoadingFallback />);
    expect(screen.queryByText(/still working/i)).toBeNull();
    await act(async () => { vi.advanceTimersByTime(2001); });
    expect(screen.getByText(/still working/i)).toBeTruthy();
  });
});
