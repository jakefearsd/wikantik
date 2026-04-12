import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GraphDetailsDrawer from './GraphDetailsDrawer.jsx';

const wrap = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe('GraphDetailsDrawer', () => {
  const node = {
    id: 'aaa', name: 'TestPage', type: 'page', role: 'hub',
    provenance: 'HUMAN_AUTHORED', sourcePage: 'TestPage',
    degreeIn: 5, degreeOut: 3, restricted: false,
  };

  const edges = [
    { source: 'bbb', target: 'aaa', relationshipType: 'links_to',
      neighborId: 'bbb', neighborName: 'Neighbor1', neighborRestricted: false, direction: 'in' },
    { source: 'aaa', target: 'ccc', relationshipType: 'related_to',
      neighborId: 'ccc', neighborName: 'Neighbor2', neighborRestricted: false, direction: 'out' },
    { source: 'aaa', target: 'ddd', relationshipType: 'links_to',
      neighborId: 'ddd', neighborName: null, neighborRestricted: true, direction: 'out' },
  ];

  const defaultProps = {
    selectedNode: node,
    incidentEdges: edges,
    onClose: vi.fn(),
    onSelectNeighbor: vi.fn(),
    onOpenPage: vi.fn(),
  };

  it('renders node name and metadata', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    expect(screen.getByText('TestPage')).toBeTruthy();
    expect(screen.getByText(/hub/i)).toBeTruthy();
    expect(screen.getByText(/type: page/i)).toBeTruthy();
  });

  it('shows degree counts', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    expect(screen.getByText(/5 in/i)).toBeTruthy();
    expect(screen.getByText(/3 out/i)).toBeTruthy();
  });

  it('renders edge rows', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    expect(screen.getByText('Neighbor1')).toBeTruthy();
    expect(screen.getByText('Neighbor2')).toBeTruthy();
  });

  it('clicking edge row fires onSelectNeighbor', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    fireEvent.click(screen.getByText('Neighbor1'));
    expect(defaultProps.onSelectNeighbor).toHaveBeenCalledWith('bbb');
  });

  it('restricted neighbor rows show lock and are not clickable', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    const restricted = screen.getByText(/restricted/);
    fireEvent.click(restricted);
    expect(defaultProps.onSelectNeighbor).not.toHaveBeenCalledWith('ddd');
  });

  it('close button fires onClose', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    fireEvent.click(screen.getByText('\u00D7'));
    expect(defaultProps.onClose).toHaveBeenCalled();
  });

  it('open page button fires onOpenPage', () => {
    wrap(<GraphDetailsDrawer {...defaultProps} />);
    fireEvent.click(screen.getByText(/open page/i));
    expect(defaultProps.onOpenPage).toHaveBeenCalledWith('TestPage');
  });

  it('open page disabled for stub nodes', () => {
    const stubNode = { ...node, role: 'stub', sourcePage: null };
    wrap(<GraphDetailsDrawer {...defaultProps} selectedNode={stubNode} />);
    const btn = screen.getByText(/open page/i);
    expect(btn.disabled).toBe(true);
  });

  it('open page disabled for restricted nodes', () => {
    const restrictedNode = { ...node, role: 'restricted', restricted: true, name: null };
    wrap(<GraphDetailsDrawer {...defaultProps} selectedNode={restrictedNode} />);
    const btn = screen.getByText(/open page/i);
    expect(btn.disabled).toBe(true);
  });
});
