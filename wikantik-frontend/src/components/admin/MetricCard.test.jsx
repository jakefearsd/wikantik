// MetricCard.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import MetricCard from './MetricCard';

describe('MetricCard', () => {
  it('renders label, value and meta', () => {
    render(<MetricCard label="KG proposals" value="17" meta="pending" />);
    expect(screen.getByText('KG proposals')).toBeInTheDocument();
    expect(screen.getByText('17')).toBeInTheDocument();
    expect(screen.getByText('pending')).toBeInTheDocument();
  });

  it('renders an unavailable state when degraded', () => {
    render(<MetricCard label="KG proposals" degraded />);
    expect(screen.getByText(/unavailable/i)).toBeInTheDocument();
  });

  it('links to its section when "to" is provided', () => {
    render(<MemoryRouter><MetricCard label="KG proposals" value="17" to="/admin/knowledge-graph" /></MemoryRouter>);
    expect(screen.getByRole('link')).toHaveAttribute('href', '/admin/knowledge-graph');
  });
});
