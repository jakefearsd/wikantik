import EmptyState from '../ui/EmptyState';

/** A failed load: the reason and a Retry button, never a blank area. A 404 is the neutral not-found state. */
export default function WorkLoadError({ error, fallback = 'Could not load this.', onRetry }) {
  if (error?.status === 404) return <EmptyState message="Not found" />;
  return (
    <EmptyState
      message={error?.message || fallback}
      action={<button type="button" className="btn btn-sm" onClick={onRetry}>Retry</button>}
    />
  );
}
