// Pure log-scale mapping between the zoom slider position (0..1) and the
// Cytoscape zoom level. The slider operates over a usable sub-range
// [SLIDER_MIN_ZOOM, SLIDER_MAX_ZOOM] even when the underlying canvas allows
// a wider zoom range — small slider moves should translate to small visual
// changes, so we stay inside the human-usable band.

export const SLIDER_MIN_ZOOM = 0.3;
export const SLIDER_MAX_ZOOM = 3;

export function sliderRange(bounds) {
  const min = Math.max(bounds?.min ?? SLIDER_MIN_ZOOM, SLIDER_MIN_ZOOM);
  const max = Math.min(bounds?.max ?? SLIDER_MAX_ZOOM, SLIDER_MAX_ZOOM);
  return { logMin: Math.log(min), logMax: Math.log(max) };
}

export function zoomToSliderPosition(zoom, bounds) {
  const { logMin, logMax } = sliderRange(bounds);
  if (!(logMax > logMin) || !Number.isFinite(zoom) || zoom <= 0) return 0;
  const t = (Math.log(zoom) - logMin) / (logMax - logMin);
  return Math.min(1, Math.max(0, t));
}

export function sliderPositionToZoom(t, bounds) {
  const { logMin, logMax } = sliderRange(bounds);
  if (!(logMax > logMin)) return Math.exp(logMin);
  const clamped = Math.min(1, Math.max(0, t));
  return Math.exp(logMin + clamped * (logMax - logMin));
}

export function clampZoom(zoom, bounds) {
  const min = bounds?.min ?? SLIDER_MIN_ZOOM;
  const max = bounds?.max ?? SLIDER_MAX_ZOOM;
  return Math.min(max, Math.max(min, zoom));
}
