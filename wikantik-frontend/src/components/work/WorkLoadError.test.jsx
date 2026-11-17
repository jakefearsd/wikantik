import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WorkLoadError from './WorkLoadError';

describe('WorkLoadError', () => {
  it('shows a neutral Not found, with no Retry, for a 404', () => {
    render(<WorkLoadError error={{ status: 404, message: 'No such project secret' }} onRetry={vi.fn()} />);
    expect(screen.getByText('Not found')).toBeInTheDocument();
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('shows the error message and a Retry button that calls onRetry', () => {
    const onRetry = vi.fn();
    render(<WorkLoadError error={new Error('Server on fire')} onRetry={onRetry} />);
    expect(screen.getByText('Server on fire')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('falls back to the given words when the error has no message', () => {
    render(<WorkLoadError error={{ status: 500 }} fallback="Could not load the thing." onRetry={vi.fn()} />);
    expect(screen.getByText('Could not load the thing.')).toBeInTheDocument();
  });

  it('falls back to the default words when there is neither a message nor a fallback, even with no error object', () => {
    render(<WorkLoadError onRetry={vi.fn()} />);
    expect(screen.getByText('Could not load this.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
