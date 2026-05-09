import '../../../styles/admin.css';

/**
 * Server-driven pagination footer for {@link AdminTable}.
 *
 * The component is purely presentational: it owns no state. The consumer
 * passes the current page (0-indexed), the page size, the total row count
 * the server has, and a change callback. Boundary states are computed from
 * those props.
 *
 * Hidden when {@code totalCount === 0} — there's nothing to paginate.
 *
 * @param {{
 *   currentPage: number,
 *   pageSize: number,
 *   totalCount: number,
 *   onPageChange: (newPage: number) => void,
 *   className?: string,
 * }} props
 */
export default function Pagination({ currentPage, pageSize, totalCount, onPageChange, className }) {
  if (!totalCount || totalCount <= 0) return null;

  const safePageSize = Math.max(1, pageSize | 0);
  const totalPages = Math.max(1, Math.ceil(totalCount / safePageSize));
  const clampedPage = Math.min(Math.max(0, currentPage | 0), totalPages - 1);

  const firstShown = clampedPage * safePageSize + 1;
  const lastShown = Math.min((clampedPage + 1) * safePageSize, totalCount);

  const onPrev = () => clampedPage > 0 && onPageChange(clampedPage - 1);
  const onNext = () => clampedPage < totalPages - 1 && onPageChange(clampedPage + 1);

  return (
    <div className={`admin-pagination ${className ?? ''}`} role="navigation" aria-label="Pagination">
      <button
        type="button"
        className="btn btn-ghost btn-sm"
        onClick={onPrev}
        disabled={clampedPage === 0}
        aria-label="Previous page"
      >
        ‹ Prev
      </button>
      <span className="admin-pagination__indicator" aria-live="polite">
        Page {clampedPage + 1} of {totalPages}
      </span>
      <button
        type="button"
        className="btn btn-ghost btn-sm"
        onClick={onNext}
        disabled={clampedPage >= totalPages - 1}
        aria-label="Next page"
      >
        Next ›
      </button>
      <span className="admin-pagination__count">
        {firstShown.toLocaleString()}–{lastShown.toLocaleString()} of {totalCount.toLocaleString()}
      </span>
    </div>
  );
}
