/**
 * TableOfContents — sticky right-rail nav for h2/h3 headings.
 *
 * Props:
 *   headings  — array of { id, text, level } from extractHeadings()
 *   activeId  — id of the currently-visible heading (from useScrollSpy)
 */
export default function TableOfContents({ headings = [], activeId }) {
  if (!headings || headings.length < 3) return null;

  return (
    <nav aria-label="Table of contents" className="page-toc">
      <div className="page-toc-title">On this page</div>
      <ul className="page-toc-list">
        {headings.map(({ id, text, level }) => {
          const isActive = id === activeId;
          return (
            <li
              key={id}
              className={`page-toc-item page-toc-level-${level}${isActive ? ' toc-active' : ''}`}
            >
              <a
                href={`#${id}`}
                className="page-toc-link"
                {...(isActive ? { 'aria-current': 'true' } : {})}
              >
                {text}
              </a>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
