import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GraphLegend from './GraphLegend.jsx';

describe('GraphLegend', () => {
  const defaultProps = {
    hubDegreeThreshold: 12,
    edgeTypes: ['links_to', 'related_to', 'part_of'],
    timestamp: '14:32:07',
  };

  it('renders node role legend items', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/hub/i)).toBeTruthy();
    expect(screen.getByText(/normal/i)).toBeTruthy();
    expect(screen.getByText(/orphan/i)).toBeTruthy();
    expect(screen.getByText(/stub/i)).toBeTruthy();
    expect(screen.getByText(/restricted/i)).toBeTruthy();
  });

  it('shows dynamic hub threshold', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/12/)).toBeTruthy();
  });

  it('lists edge types', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText('links_to')).toBeTruthy();
    expect(screen.getByText('related_to')).toBeTruthy();
  });

  it('shows directionality convention', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/one-way/i)).toBeTruthy();
    expect(screen.getByText(/bidirectional/i)).toBeTruthy();
  });

  it('collapses and expands', () => {
    render(<GraphLegend {...defaultProps} />);
    const toggle = screen.getByText(/legend/i);
    fireEvent.click(toggle);
    expect(screen.queryByText('links_to')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('links_to')).toBeTruthy();
  });

  it('shows timestamp', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/14:32:07/)).toBeTruthy();
  });
});
