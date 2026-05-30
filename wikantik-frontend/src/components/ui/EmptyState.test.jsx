// EmptyState.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import EmptyState from './EmptyState';

describe('EmptyState', () => {
  it('renders the message', () => {
    render(<EmptyState message="No items found." />);
    expect(screen.getByText('No items found.')).toBeInTheDocument();
  });

  it('renders the action when provided', () => {
    render(<EmptyState message="No users yet." action={<button>Add User</button>} />);
    expect(screen.getByRole('button', { name: 'Add User' })).toBeInTheDocument();
  });

  it('does not render action when not provided', () => {
    const { container } = render(<EmptyState message="No items." />);
    const actionDiv = container.querySelector('.admin-empty-action');
    expect(actionDiv).not.toBeInTheDocument();
  });

  it('renders icon when provided', () => {
    render(
      <EmptyState message="No results." icon={<span data-testid="test-icon">📭</span>} />
    );
    const icon = screen.getByTestId('test-icon');
    expect(icon).toBeInTheDocument();
  });

  it('does not render icon wrapper when icon is not provided', () => {
    const { container } = render(<EmptyState message="No items." />);
    const iconDiv = container.querySelector('.empty-state-icon');
    expect(iconDiv).not.toBeInTheDocument();
  });

  it('renders all props together', () => {
    render(
      <EmptyState
        message="Empty collection."
        icon={<span data-testid="icon">🎯</span>}
        action={<button>Create</button>}
      />
    );
    expect(screen.getByTestId('icon')).toBeInTheDocument();
    expect(screen.getByText('Empty collection.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
  });
});
