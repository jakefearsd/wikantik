import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GraphLegend from './GraphLegend.jsx';
import { shapeForCluster } from './graph-style.js';

describe('GraphLegend', () => {
  const defaultProps = {
    hubDegreeThreshold: 12,
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

  it('shows single page-link edge entry', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText('Page link')).toBeTruthy();
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
    expect(screen.queryByText('Page link')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('Page link')).toBeTruthy();
  });

  it('shows timestamp', () => {
    render(<GraphLegend {...defaultProps} />);
    expect(screen.getByText(/14:32:07/)).toBeTruthy();
  });
});

describe('GraphLegend cluster shapes', () => {
  const clusterProps = {
    hubDegreeThreshold: 10,
    timestamp: '14:32:07',
    clusters: [
      { name: 'math', color: '#2563eb' },
      { name: 'science', color: '#dc2626' },
    ],
  };

  it('renders a Clusters section when clusters prop is provided', () => {
    render(<GraphLegend {...clusterProps} />);
    expect(screen.getByText('Clusters')).toBeTruthy();
  });

  it('renders each cluster name', () => {
    render(<GraphLegend {...clusterProps} />);
    expect(screen.getByText('math')).toBeTruthy();
    expect(screen.getByText('science')).toBeTruthy();
  });

  it('renders a shape glyph aria-label for each cluster', () => {
    render(<GraphLegend {...clusterProps} />);
    const mathShape = shapeForCluster('math');
    expect(screen.getByLabelText(`shape: ${mathShape}`)).toBeTruthy();
  });

  it('does not render Clusters section when clusters prop is empty', () => {
    render(<GraphLegend hubDegreeThreshold={10} timestamp="14:32:07" clusters={[]} />);
    expect(screen.queryByText('Clusters')).toBeNull();
  });

  it('does not render Clusters section when clusters prop is absent', () => {
    render(<GraphLegend hubDegreeThreshold={10} timestamp="14:32:07" />);
    expect(screen.queryByText('Clusters')).toBeNull();
  });
});
