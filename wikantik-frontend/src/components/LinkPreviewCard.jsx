import { useLayoutEffect, useRef } from 'react';
import { placeCard } from '../utils/placeCard';
import { clusterLabel } from '../utils/clusterLabel';

// Size used before the card has been measured (and where there is no layout, e.g. tests): the CSS max-width.
const ESTIMATE = { width: 352, height: 140 }; // 22rem
const viewport = () => ({ width: window.innerWidth, height: window.innerHeight });
const clean = (v) => (typeof v === 'string' ? v.trim() : '');

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
  if (!result) body = <div className="link-preview-loading">Loading…</div>;
  else if (result.status === 'missing') body = <div className="link-preview-missing">Not created yet</div>;
  else {
    // Only non-empty parts render, so the card's flex gap never spaces around an empty element.
    const d = result.data;
    const type = clean(d.type);
    const cluster = clusterLabel(d.cluster);
    const text = clean(d.summary) || clean(d.excerpt);
    body = (
      <>
        <div className="link-preview-head">
          <strong className="link-preview-title">{clean(d.title) || d.name}</strong>
          {type && <span className="badge badge-default link-preview-type">{type}</span>}
        </div>
        {cluster && <div className="link-preview-cluster" data-testid="link-preview-cluster">{cluster}</div>}
        {text && <div className="link-preview-text">{text}</div>}
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
