// EmptyState.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import EmptyState from './EmptyState';

describe('EmptyState', () => {
  it('renders the message and optional action', () => {
    render(<EmptyState message="No users yet." action={<button>Add</button>} />);
    expect(screen.getByText('No users yet.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });
});
