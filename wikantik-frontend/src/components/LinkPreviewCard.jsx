/** Text-only preview of a wiki page, positioned under the hovered link. Never renders Markdown or HTML. */
export default function LinkPreviewCard({ rect, result, onMouseEnter, onMouseLeave }) {
  if (!rect) return null;
  const style = { position: 'fixed', top: Math.round(rect.bottom + 6), left: Math.round(Math.max(8, rect.left)) };
  let body;
  if (!result) body = <p className="link-preview-loading">Loading…</p>;
  else if (result.status === 'missing') body = <p className="link-preview-missing">Not created yet</p>;
  else {
    const d = result.data;
    body = (
      <>
        <div className="link-preview-head">
          <strong className="link-preview-title">{d.title || d.name}</strong>
          {d.type && <span className="link-preview-type">{d.type}</span>}
        </div>
        {d.cluster && <div className="link-preview-cluster">{d.cluster}</div>}
        {(d.summary || d.excerpt) && <p className="link-preview-text">{d.summary || d.excerpt}</p>}
      </>
    );
  }
  return (
    <div className="link-preview-card" role="tooltip" data-testid="link-preview-card" style={style}
         onMouseEnter={onMouseEnter} onMouseLeave={onMouseLeave}>
      {body}
    </div>
  );
}
