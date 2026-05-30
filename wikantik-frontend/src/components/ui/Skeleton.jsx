// Skeleton.jsx
// Animated skeleton placeholder for loading states.
export default function Skeleton({
  variant = 'line',
  count = 1,
  width,
  className = ''
}) {
  const skeletons = Array.from({ length: count }).map((_, i) => (
    <div
      key={i}
      className={`skeleton skeleton-${variant} ${className}`}
      style={width ? { width } : undefined}
      aria-hidden="true"
    />
  ));

  return count === 1 ? skeletons[0] : <>{skeletons}</>;
}
