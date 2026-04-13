import { describe, it, expect } from 'vitest';
import {
  SLIDER_MIN_ZOOM,
  SLIDER_MAX_ZOOM,
  sliderRange,
  zoomToSliderPosition,
  sliderPositionToZoom,
  clampZoom,
} from './zoom-scale.js';

describe('zoom-scale', () => {
  describe('sliderRange', () => {
    it('clamps a wide canvas range down to the usable band', () => {
      const { logMin, logMax } = sliderRange({ min: 0.1, max: 4 });
      expect(logMin).toBeCloseTo(Math.log(SLIDER_MIN_ZOOM));
      expect(logMax).toBeCloseTo(Math.log(SLIDER_MAX_ZOOM));
    });

    it('uses the canvas bounds when they are inside the band', () => {
      const { logMin, logMax } = sliderRange({ min: 0.5, max: 2 });
      expect(logMin).toBeCloseTo(Math.log(0.5));
      expect(logMax).toBeCloseTo(Math.log(2));
    });

    it('falls back to defaults when bounds are missing', () => {
      const { logMin, logMax } = sliderRange(undefined);
      expect(logMin).toBeCloseTo(Math.log(SLIDER_MIN_ZOOM));
      expect(logMax).toBeCloseTo(Math.log(SLIDER_MAX_ZOOM));
    });
  });

  describe('zoomToSliderPosition', () => {
    const bounds = { min: 0.1, max: 4 };

    it('maps the band midpoint (geometric mean) to 0.5', () => {
      const mid = Math.sqrt(SLIDER_MIN_ZOOM * SLIDER_MAX_ZOOM);
      expect(zoomToSliderPosition(mid, bounds)).toBeCloseTo(0.5);
    });

    it('maps the band minimum to 0', () => {
      expect(zoomToSliderPosition(SLIDER_MIN_ZOOM, bounds)).toBeCloseTo(0);
    });

    it('maps the band maximum to 1', () => {
      expect(zoomToSliderPosition(SLIDER_MAX_ZOOM, bounds)).toBeCloseTo(1);
    });

    it('clamps zooms below the band to 0', () => {
      expect(zoomToSliderPosition(0.05, bounds)).toBe(0);
    });

    it('clamps zooms above the band to 1', () => {
      expect(zoomToSliderPosition(10, bounds)).toBe(1);
    });

    it('returns 0 for non-finite or non-positive zoom', () => {
      expect(zoomToSliderPosition(0, bounds)).toBe(0);
      expect(zoomToSliderPosition(-1, bounds)).toBe(0);
      expect(zoomToSliderPosition(NaN, bounds)).toBe(0);
      expect(zoomToSliderPosition(Infinity, bounds)).toBe(0);
    });

    it('returns 0 when the band has collapsed to a single point', () => {
      expect(zoomToSliderPosition(1, { min: 1, max: 1 })).toBe(0);
    });
  });

  describe('sliderPositionToZoom', () => {
    const bounds = { min: 0.1, max: 4 };

    it('maps 0 to the band minimum', () => {
      expect(sliderPositionToZoom(0, bounds)).toBeCloseTo(SLIDER_MIN_ZOOM);
    });

    it('maps 1 to the band maximum', () => {
      expect(sliderPositionToZoom(1, bounds)).toBeCloseTo(SLIDER_MAX_ZOOM);
    });

    it('maps 0.5 to the geometric mean of the band', () => {
      const mid = Math.sqrt(SLIDER_MIN_ZOOM * SLIDER_MAX_ZOOM);
      expect(sliderPositionToZoom(0.5, bounds)).toBeCloseTo(mid);
    });

    it('clamps slider positions outside [0, 1]', () => {
      expect(sliderPositionToZoom(-0.5, bounds)).toBeCloseTo(SLIDER_MIN_ZOOM);
      expect(sliderPositionToZoom(2, bounds)).toBeCloseTo(SLIDER_MAX_ZOOM);
    });
  });

  describe('round-trip', () => {
    it('recovers the original zoom through slider space', () => {
      const bounds = { min: 0.1, max: 4 };
      for (const z of [0.3, 0.5, 1, 1.5, 2, 3]) {
        const t = zoomToSliderPosition(z, bounds);
        expect(sliderPositionToZoom(t, bounds)).toBeCloseTo(z);
      }
    });
  });

  describe('clampZoom', () => {
    it('keeps zooms inside the canvas bounds untouched', () => {
      expect(clampZoom(1.5, { min: 0.1, max: 4 })).toBe(1.5);
    });

    it('clamps below minimum', () => {
      expect(clampZoom(0.05, { min: 0.1, max: 4 })).toBe(0.1);
    });

    it('clamps above maximum', () => {
      expect(clampZoom(10, { min: 0.1, max: 4 })).toBe(4);
    });
  });
});
