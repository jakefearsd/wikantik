import { useSyncExternalStore, useCallback, useMemo, useRef } from 'react';
import {
  SLIDER_MIN_ZOOM,
  SLIDER_MAX_ZOOM,
  zoomToSliderPosition,
  sliderPositionToZoom,
  clampZoom,
} from './zoom-scale.js';

const NUDGE_STEP = 0.2;

// window.cy (set by GraphCanvas once cytoscape mounts) is a genuine external
// store: it mutates outside React and emits its own 'zoom' events. Reading
// its live zoom level is synced via useSyncExternalStore rather than an
// effect that calls setState, per https://react.dev/reference/react/useSyncExternalStore.
export default function GraphZoomSlider({ layoutDone }) {
  const rafRef = useRef(null);

  const subscribe = useCallback((onStoreChange) => {
    const cy = window.cy;
    if (!cy || !layoutDone) return () => {};
    const onZoom = () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = requestAnimationFrame(onStoreChange);
    };
    cy.on('zoom', onZoom);
    return () => {
      cy.off('zoom', onZoom);
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, [layoutDone]);

  const getSnapshot = useCallback(() => {
    const cy = window.cy;
    return (cy && layoutDone) ? cy.zoom() : 1;
  }, [layoutDone]);

  const zoom = useSyncExternalStore(subscribe, getSnapshot);

  const bounds = useMemo(() => {
    const cy = window.cy;
    if (!cy || !layoutDone) return { min: SLIDER_MIN_ZOOM, max: SLIDER_MAX_ZOOM };
    return { min: cy.minZoom(), max: cy.maxZoom() };
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
