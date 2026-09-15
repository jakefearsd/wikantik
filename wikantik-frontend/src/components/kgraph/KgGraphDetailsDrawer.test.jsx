import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import KgGraphDetailsDrawer from './KgGraphDetailsDrawer';

const renderDrawer = (props) =>
  render(
    <MemoryRouter>
      <KgGraphDetailsDrawer
        selectedNode={null}
        incidentEdges={[]}
        onClose={vi.fn()}
        onSelectNeighbor={vi.fn()}
        {...props}
      />
    </MemoryRouter>
  );

describe('KgGraphDetailsDrawer', () => {
  it('renders nothing when there is no selected node', () => {
    const { container } = renderDrawer({ selectedNode: null });
    expect(container).toBeEmptyDOMElement();
  });

  it('renders node attrs, falling back to em dash for missing fields', () => {
    renderDrawer({
      selectedNode: { name: 'Acme Corp', type: null, provenance: null, status: null, tier: null },
      incidentEdges: [],
    });
    expect(screen.getByRole('heading', { name: 'Acme Corp' })).toBeInTheDocument();
    expect(screen.getByText('Type')).toBeInTheDocument();
    // Type/Status/Tier all render '—'; Provenance renders its own pill (never '—' text alone since it wraps '—').
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('shows the provenance pill with the lowercased CSS class', () => {
    renderDrawer({
      selectedNode: { name: 'Node', provenance: 'CURATED' },
    });
    const pill = screen.getByText('CURATED');
    expect(pill).toHaveClass('kg-prov-curated');
  });

  it('defaults the provenance pill class to "unknown" when provenance is absent', () => {
    renderDrawer({ selectedNode: { name: 'Node' } });
    const pill = screen.getByText('—', { selector: '.kg-prov-pill' });
    expect(pill).toHaveClass('kg-prov-unknown');
  });

  it('renders the cluster row only when a cluster is present', () => {
    const { rerender } = render(
      <MemoryRouter>
        <KgGraphDetailsDrawer
          selectedNode={{ name: 'Node', cluster: 'infra/fleet' }}
          incidentEdges={[]}
          onClose={vi.fn()}
          onSelectNeighbor={vi.fn()}
        />
      </MemoryRouter>
    );
    expect(screen.getByText('Cluster')).toBeInTheDocument();
    expect(screen.getByText('infra/fleet')).toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <KgGraphDetailsDrawer
          selectedNode={{ name: 'Node' }}
          incidentEdges={[]}
          onClose={vi.fn()}
          onSelectNeighbor={vi.fn()}
        />
      </MemoryRouter>
    );
    expect(screen.queryByText('Cluster')).not.toBeInTheDocument();
  });

  it('lists incident edges with direction arrows and neighbor name, invoking onSelectNeighbor on click', () => {
    const onSelectNeighbor = vi.fn();
    renderDrawer({
      selectedNode: { name: 'Node' },
      incidentEdges: [
        { id: 'e1', direction: 'in', neighborId: 'n1', neighborName: 'Neighbor One', relationshipType: 'related_to' },
        { id: 'e2', direction: 'out', neighborId: 'n2', neighborName: null, relationshipType: 'implements' },
      ],
      onSelectNeighbor,
    });

    expect(screen.getByText('Incident edges (2)')).toBeInTheDocument();
    expect(screen.getByText('Neighbor One')).toBeInTheDocument();
    expect(screen.getByText('(restricted)')).toBeInTheDocument();
    expect(screen.getByText('related_to')).toBeInTheDocument();
    expect(screen.getByText('implements')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Neighbor One'));
    expect(onSelectNeighbor).toHaveBeenCalledWith('n1');

    fireEvent.click(screen.getByText('(restricted)'));
    expect(onSelectNeighbor).toHaveBeenCalledWith('n2');
  });

  it('calls onClose when the close button is clicked', () => {
    const onClose = vi.fn();
    renderDrawer({ selectedNode: { name: 'Node' }, onClose });
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('links to the admin knowledge-graph view focused on the node', () => {
    renderDrawer({ selectedNode: { name: 'My Node' } });
    expect(screen.getByRole('link', { name: /Open in admin/ })).toHaveAttribute(
      'href',
      '/admin/knowledge-graph?focus=My%20Node'
    );
  });
});
