import React from 'react';

export default function Sparkline({ values, width = 120, height = 24, stroke = 'currentColor' }) {
  const cleaned = (values || []).filter(v => typeof v === 'number' && Number.isFinite(v));
  if (cleaned.length < 2) {
    return <svg width={width} height={height} aria-hidden="true" />;
  }
  const min = Math.min(...cleaned);
  const max = Math.max(...cleaned);
  const range = max - min || 1;
  const stepX = width / (cleaned.length - 1);
  const points = cleaned
    .map((v, i) => {
      const x = i * stepX;
      const y = height - ((v - min) / range) * height;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  return (
    <svg width={width} height={height} role="img" aria-label="trend sparkline">
      <polyline fill="none" stroke={stroke} strokeWidth="1.5" points={points} />
    </svg>
  );
}
