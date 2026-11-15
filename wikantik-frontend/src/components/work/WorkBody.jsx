import { memo, useEffect, useRef } from 'react';
import { renderMath } from '../../utils/math';
import { renderFences } from '../../utils/fences/renderFences';

/**
 * The server-rendered, already-sanitised item text. Memoised on the HTML string alone: React 19 re-applies
 * dangerouslySetInnerHTML when the host re-renders, which would wipe the math and diagrams drawn into it, so
 * a parent re-render (typing in the comment box) must never reach this element.
 */
const WorkBody = memo(function WorkBody({ html }) {
  const ref = useRef(null);
  useEffect(() => {
    const root = ref.current;
    if (!root || !html) return undefined;
    let stale = false;
    renderMath(root, () => stale).catch((e) => console.warn('WorkBody: math rendering failed', e));
    renderFences(root, () => stale).catch((e) => console.warn('WorkBody: diagram rendering failed', e));
    return () => { stale = true; };
  }, [html]);
  if (!html) return <p className="work-muted">No description.</p>;
  return <div ref={ref} className="article-prose work-body" dangerouslySetInnerHTML={{ __html: html }} />;
});

export default WorkBody;
