import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GraphToolbar from './GraphToolbar.jsx';

describe('GraphToolbar', () => {
  const defaultProps = {
    onFitToView: vi.fn(),
    onRefresh: vi.fn(),
    onToggleAnomalies: vi.fn(),
    onToggleEdgeType: vi.fn(),
    edgeTypes: ['links_to', 'related_to'],
    hiddenEdgeTypes: new Set(),
    onlyAnomalies: false,
    timestamp: '14:32:07',
  };

  it('renders fit-to-view button', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/fit/i));
    expect(defaultProps.onFitToView).toHaveBeenCalled();
  });

  it('renders refresh button', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/refresh/i));
    expect(defaultProps.onRefresh).toHaveBeenCalled();
  });

  it('renders anomalies toggle', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/orphans/i));
    expect(defaultProps.onToggleAnomalies).toHaveBeenCalled();
  });

  it('shows active state on anomalies toggle when active', () => {
    render(<GraphToolbar {...defaultProps} onlyAnomalies={true} />);
    const btn = screen.getByText(/orphans/i);
    expect(btn.className).toContain('active');
  });

  it('shows snapshot timestamp', () => {
    render(<GraphToolbar {...defaultProps} />);
    expect(screen.getByText(/14:32:07/)).toBeTruthy();
  });

  it('opens filter popover and toggles edge type', () => {
    render(<GraphToolbar {...defaultProps} />);
    fireEvent.click(screen.getByText(/filter/i));
    expect(screen.getByText('links_to')).toBeTruthy();
    fireEvent.click(screen.getByText('links_to'));
    expect(defaultProps.onToggleEdgeType).toHaveBeenCalledWith('links_to');
  });
});
