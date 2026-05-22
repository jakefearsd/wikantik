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

  it('adds the accent class to the value when accent is set', () => {
    render(<MetricCard label="KG proposals" value="17" accent />);
    const value = screen.getByText('17');
    expect(value).toHaveClass('metric-card-value');
    expect(value).toHaveClass('accent');
  });

  it('omits the accent class from the value when accent is unset', () => {
    render(<MetricCard label="KG proposals" value="17" />);
    expect(screen.getByText('17')).not.toHaveClass('accent');
  });

  it('adds the dim class to the card when dim is set', () => {
    const { container } = render(<MetricCard label="KG proposals" value="17" dim />);
    const card = container.querySelector('.metric-card');
    expect(card).toHaveClass('dim');
  });

  it('omits the dim class from the card when dim is unset', () => {
    const { container } = render(<MetricCard label="KG proposals" value="17" />);
    expect(container.querySelector('.metric-card')).not.toHaveClass('dim');
  });

  it('renders children (e.g. a feed list) inside the card', () => {
    render(
      <MetricCard label="Recent changes">
        <ul><li>edited Main</li><li>created Sandbox</li></ul>
      </MetricCard>,
    );
    expect(screen.getByText('edited Main')).toBeInTheDocument();
    expect(screen.getByText('created Sandbox')).toBeInTheDocument();
  });

  it('renders a value of 0 (not treated as absent)', () => {
    render(<MetricCard label="Pending proposals" value={0} />);
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('omits the value div when value is null/undefined', () => {
    const { container } = render(<MetricCard label="No value" meta="just meta" />);
    expect(container.querySelector('.metric-card-value')).toBeNull();
    expect(screen.getByText('just meta')).toBeInTheDocument();
  });
});
