import { useRef, useEffect, useCallback } from 'react';
import CytoscapeComponent from 'react-cytoscapejs';
import coseBilkent from 'cytoscape-cose-bilkent';
import cytoscape from 'cytoscape';
import { graphStylesheet } from './graph-style.js';

cytoscape.use(coseBilkent);

const LAYOUT_OPTIONS = {
  name: 'cose-bilkent',
  randomize: true,
  animate: false,
  quality: 'default',
  nodeDimensionsIncludeLabels: true,
  idealEdgeLength: 80,
  nodeRepulsion: 4500,
};

const LAYOUT_TIMEOUT_MS = 15000;

export default function GraphCanvas({
  elements, selectedId, hiddenEdgeTypes, onlyAnomalies,
  focusNodeId, onNodeClick, onBackgroundClick, onReady, onLayoutTimeout,
}) {
  const cyRef = useRef(null);
  const layoutTimeoutRef = useRef(null);
  const tooltipRef = useRef(null);

  const attachHandlers = useCallback((cy) => {
    cy.on('tap', 'node', (evt) => {
      onNodeClick(evt.target.id());
    });
    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        onBackgroundClick();
      }
    });

    cy.on('mouseover', 'node', (evt) => {
      const node = evt.target;
      const name = node.data('label');
      if (!name || !tooltipRef.current) return;
      const pos = node.renderedPosition();
      const container = cy.container().getBoundingClientRect();
      tooltipRef.current.textContent = name;
      tooltipRef.current.style.display = 'block';
      tooltipRef.current.style.left = `${pos.x + container.left + 12}px`;
      tooltipRef.current.style.top = `${pos.y + container.top - 8}px`;
    });
    cy.on('mouseout', 'node', () => {
      if (tooltipRef.current) tooltipRef.current.style.display = 'none';
    });

    cy.on('layoutstop', () => {
      if (layoutTimeoutRef.current) {
        clearTimeout(layoutTimeoutRef.current);
        layoutTimeoutRef.current = null;
      }
      if (focusNodeId) {
        const target = cy.getElementById(focusNodeId);
        if (target.length > 0) {
          cy.animate({ center: { eles: target }, zoom: 1.2 }, { duration: 300 });
          onNodeClick(focusNodeId);
        }
      } else {
        cy.fit(undefined, 30);
      }
      onReady();
    });

    layoutTimeoutRef.current = setTimeout(() => {
      cy.stop();
      if (onLayoutTimeout) onLayoutTimeout();
      onReady();
    }, LAYOUT_TIMEOUT_MS);

    window.cy = cy;
  }, [onNodeClick, onBackgroundClick, onReady, onLayoutTimeout]);

  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    cy.elements().removeClass('dimmed');
    if (!selectedId) return;
    const selected = cy.getElementById(selectedId);
    if (selected.length === 0) return;
    selected.select();
    const neighborhood = selected.closedNeighborhood();
    cy.elements().not(neighborhood).addClass('dimmed');
  }, [selectedId]);

  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    cy.edges().forEach(edge => {
      const types = edge.data('relationshipTypes') || [edge.data('relationshipType')];
      const allHidden = hiddenEdgeTypes && types.every(t => hiddenEdgeTypes.has(t));
      if (allHidden) {
        edge.addClass('hidden');
      } else {
        edge.removeClass('hidden');
      }
    });
  }, [hiddenEdgeTypes]);

  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    if (onlyAnomalies) {
      cy.nodes().forEach(node => {
        const role = node.data('role');
        if (role !== 'orphan' && role !== 'stub') {
          node.addClass('hidden');
        }
      });
      cy.edges().addClass('hidden');
    } else {
      cy.nodes().removeClass('hidden');
      cy.edges().removeClass('hidden');
    }
  }, [onlyAnomalies]);

  useEffect(() => {
    if (!cyRef.current) return;
    const cy = cyRef.current;
    let baseZoom = null;

    const applySemanticZoom = () => {
      const zoom = cy.zoom();
      if (baseZoom === null) baseZoom = zoom;
      const ratio = baseZoom / zoom;
      const scale = Math.max(0.3, Math.min(ratio, 3));

      cy.nodes().forEach(node => {
        const bw = node.data('_baseW');
        const bh = node.data('_baseH');
        if (bw != null) {
          node.style('width', bw * scale);
          node.style('height', bh * scale);
        }
        node.style('font-size', 10 * scale);

        const isSelected = node.selected();
        const isNeighbor = selectedId && cy.getElementById(selectedId).closedNeighborhood().has(node);
        if (zoom / baseZoom > 1.5 || (zoom < baseZoom * 0.6 && !isSelected && !isNeighbor)) {
          node.style('label', '');
        } else {
          node.style('label', node.data('label'));
        }
      });

      cy.edges().forEach(edge => {
        const bw = edge.data('compositeWidth') || 1;
        edge.style('width', bw * scale);
        edge.style('font-size', 7 * scale);
        edge.style('arrow-scale', 0.6 * scale);
      });
    };

    const captureBaseSizes = () => {
      cy.nodes().forEach(node => {
        if (node.data('_baseW') == null) {
          node.data('_baseW', node.numericStyle('width'));
          node.data('_baseH', node.numericStyle('height'));
        }
      });
      baseZoom = cy.zoom();
      applySemanticZoom();
    };

    cy.on('zoom', applySemanticZoom);
    cy.on('layoutstop', captureBaseSizes);
    if (cy.nodes().length > 0) captureBaseSizes();

    return () => {
      cy.off('zoom', applySemanticZoom);
      cy.off('layoutstop', captureBaseSizes);
    };
  }, [selectedId]);

  return (
    <div className="graph-canvas-container">
      <CytoscapeComponent
        elements={CytoscapeComponent.normalizeElements(elements)}
        stylesheet={graphStylesheet}
        layout={LAYOUT_OPTIONS}
        style={{ width: '100%', height: '100%' }}
        cy={(cy) => {
          cyRef.current = cy;
          attachHandlers(cy);
        }}
      />
      <div ref={tooltipRef} style={{
        display: 'none', position: 'fixed', pointerEvents: 'none',
        background: 'var(--bg, #fff)', border: '1px solid var(--border, #e2e8f0)',
        borderRadius: 4, padding: '2px 6px', fontSize: '0.8rem', zIndex: 30,
        boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
      }} />
    </div>
  );
}
