import { useLayoutEffect, useRef } from 'react';
import { placeCard } from '../utils/placeCard';

// Size used before the card has been measured (and where there is no layout, e.g. tests): the CSS max-width.
const ESTIMATE = { width: 352, height: 140 }; // 22rem
const viewport = () => ({ width: window.innerWidth, height: window.innerHeight });

/** Text-only preview of a wiki page, positioned under the hovered link. Never renders Markdown or HTML. */
export default function LinkPreviewCard({ rect, result, onMouseEnter, onMouseLeave }) {
  const cardRef = useRef(null);

  // Refine the estimate-based position with the card's real size once it is laid out — direct DOM writes, so
  // the card never renders twice.
  useLayoutEffect(() => {
    const card = cardRef.current;
    if (!card || !rect) return;
    const { width, height } = card.getBoundingClientRect();
    if (!width || !height) return;
    const pos = placeCard(rect, { width, height }, viewport());
    card.style.left = `${pos.left}px`;
    card.style.top = `${pos.top}px`;
  }, [rect, result]);

  if (!rect) return null;
  const pos = placeCard(rect, ESTIMATE, viewport());
  const style = { position: 'fixed', top: pos.top, left: pos.left };
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
    <div ref={cardRef} className="link-preview-card" role="tooltip" data-testid="link-preview-card" style={style}
         onMouseEnter={onMouseEnter} onMouseLeave={onMouseLeave}>
      {body}
    </div>
  );
}
