import { useState, useEffect, useCallback, useRef } from 'react';
import {
  SLIDER_MIN_ZOOM,
  SLIDER_MAX_ZOOM,
  zoomToSliderPosition,
  sliderPositionToZoom,
  clampZoom,
} from './zoom-scale.js';

const NUDGE_STEP = 0.2;

export default function GraphZoomSlider({ layoutDone }) {
  const [zoom, setZoom] = useState(1);
  const [bounds, setBounds] = useState({ min: SLIDER_MIN_ZOOM, max: SLIDER_MAX_ZOOM });
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
    cy.zoom({
      level: clampZoom(level, bounds),
      renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 },
    });
  }, [bounds]);

  const sliderPosition = zoomToSliderPosition(zoom, bounds);

  const handleChange = useCallback((e) => {
    applyZoom(sliderPositionToZoom(parseFloat(e.target.value), bounds));
  }, [applyZoom, bounds]);

  const nudge = useCallback((delta) => {
    const cy = window.cy;
    if (!cy) return;
    applyZoom(cy.zoom() + delta);
  }, [applyZoom]);

  return (
    <div className="graph-zoom-slider">
      <button className="zoom-btn" onClick={() => nudge(NUDGE_STEP)} title="Zoom in">+</button>
      <input
        type="range"
        min={0}
        max={1}
        step={0.005}
        value={sliderPosition}
        onChange={handleChange}
        className="zoom-range"
      />
      <button className="zoom-btn" onClick={() => nudge(-NUDGE_STEP)} title="Zoom out">&minus;</button>
    </div>
  );
}
