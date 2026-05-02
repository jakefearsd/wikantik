import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GraphErrorState from './GraphErrorState.jsx';

const wrap = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe('GraphErrorState', () => {
  it('shows empty message with refresh button', () => {
    const onRetry = vi.fn();
    wrap(<GraphErrorState variant="empty" onRetry={onRetry} />);
    expect(screen.getByText('The knowledge graph is empty.')).toBeTruthy();
    fireEvent.click(screen.getByText('Refresh'));
    expect(onRetry).toHaveBeenCalled();
  });

  it('shows empty-for-you without action', () => {
    wrap(<GraphErrorState variant="empty-for-you" />);
    expect(screen.getByText(/don't have permission to view any/)).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows unauthorized with sign-in link', () => {
    wrap(<GraphErrorState variant="unauthorized" />);
    expect(screen.getByText('Sign in to view the knowledge graph.')).toBeTruthy();
    expect(screen.getByText('Sign in')).toBeTruthy();
  });

  it('shows forbidden without action', () => {
    wrap(<GraphErrorState variant="forbidden" />);
    expect(screen.getByText(/don't have permission to view the knowledge graph/)).toBeTruthy();
  });

  it('shows server error with retry button', () => {
    const onRetry = vi.fn();
    wrap(<GraphErrorState variant="server" onRetry={onRetry} />);
    expect(screen.getByText('The graph service is unavailable right now.')).toBeTruthy();
    fireEvent.click(screen.getByText('Try again'));
    expect(onRetry).toHaveBeenCalled();
  });

  it('shows malformed with retry button', () => {
    const onRetry = vi.fn();
    wrap(<GraphErrorState variant="malformed" onRetry={onRetry} />);
    expect(screen.getByText(/snapshot was invalid/)).toBeTruthy();
    fireEvent.click(screen.getByText('Try again'));
    expect(onRetry).toHaveBeenCalled();
  });
});
