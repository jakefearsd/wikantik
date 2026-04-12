import { useState, useEffect, useCallback, useRef } from 'react';

export default function GraphZoomSlider({ layoutDone }) {
  const [zoom, setZoom] = useState(1);
  const [bounds, setBounds] = useState({ min: 0.1, max: 4 });
  const rafRef = useRef(null);

  useEffect(() => {
    const cy = window.cy;
    if (!cy || !layoutDone) return;

    setBounds({ min: cy.minZoom(), max: cy.maxZoom() });
    setZoom(cy.zoom());

    const onZoom = () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = requestAnimationFrame(() => setZoom(cy.zoom()));
    };

    cy.on('zoom', onZoom);
    return () => {
      cy.off('zoom', onZoom);
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, [layoutDone]);

  const applyZoom = useCallback((level) => {
    const cy = window.cy;
    if (!cy) return;
    const clamped = Math.min(bounds.max, Math.max(bounds.min, level));
    cy.zoom({
      level: clamped,
      renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 },
    });
  }, [bounds]);

  const handleChange = useCallback((e) => {
    applyZoom(parseFloat(e.target.value));
  }, [applyZoom]);

  const nudge = useCallback((delta) => {
    const cy = window.cy;
    if (!cy) return;
    applyZoom(cy.zoom() + delta);
  }, [applyZoom]);

  return (
    <div className="graph-zoom-slider">
      <button className="zoom-btn" onClick={() => nudge(0.2)} title="Zoom in">+</button>
      <input
        type="range"
        min={bounds.min}
        max={bounds.max}
        step={0.01}
        value={zoom}
        onChange={handleChange}
        className="zoom-range"
      />
      <button className="zoom-btn" onClick={() => nudge(-0.2)} title="Zoom out">&minus;</button>
    </div>
  );
}
