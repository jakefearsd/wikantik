import EmptyState from '../ui/EmptyState';

/** Stand-in for a /work view that a later task fills; keeps the router complete. */
export default function WorkPlaceholder({ title }) {
  return <div className="work-placeholder"><EmptyState message={`${title} is coming soon.`} /></div>;
}
