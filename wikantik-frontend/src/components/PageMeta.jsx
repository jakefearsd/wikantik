export default function PageMeta({ page }) {
  if (!page) return null;

  const date = page.lastModified
    ? new Date(page.lastModified).toLocaleDateString('en-US', {
        year: 'numeric', month: 'long', day: 'numeric'
      })
    : null;

  return (
    <div className="page-meta">
      {page.author && (
        <span className="page-meta-author">{page.author}</span>
      )}
      {date && (
        <>
          <span className="page-meta-dot">·</span>
          <span>{date}</span>
        </>
      )}
      {page.version > 0 && (
        <>
          <span className="page-meta-dot">·</span>
          <span>v{page.version}</span>
        </>
      )}
      {page.metadata?.cluster && (
        <>
          <span className="page-meta-dot">·</span>
          <span className="tag">{page.metadata.cluster}</span>
        </>
      )}
    </div>
  );
}
