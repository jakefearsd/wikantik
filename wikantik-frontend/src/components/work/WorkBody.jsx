import { memo, useEffect, useRef } from 'react';
import { renderMath } from '../../utils/math';
import { probe } from './workBodyRenderProbe';
import { renderFences } from '../../utils/fences/renderFences';
import { highlightMentions } from './mentionText';

/**
 * Server-rendered, already-sanitised work text: an item's description or a comment. Memoised on the HTML (and the
 * mentioned names): React 19 re-applies dangerouslySetInnerHTML when the host re-renders, which would wipe the math,
 * diagrams and mention chips drawn into it, so a parent re-render (typing in the comment box) must never reach it.
 * `mentions` are display names to mark as mention chips; `empty` is what shows when there is no HTML.
 */
const WorkBody = memo(function WorkBody({ html, mentions, className = 'article-prose work-body', empty = 'No description.' }) {
  const ref = useRef(null);
  probe();
  const names = (mentions || []).join('\n');
  useEffect(() => {
    const root = ref.current;
    if (!root || !html) return undefined;
    let stale = false;
    if (names) highlightMentions(root, names.split('\n'));
    renderMath(root, () => stale).catch((e) => console.warn('WorkBody: math rendering failed', e));
    renderFences(root, () => stale).catch((e) => console.warn('WorkBody: diagram rendering failed', e));
    return () => { stale = true; };
  }, [html, names]);
  if (!html) return empty ? <p className="work-muted">{empty}</p> : null;
  return <div ref={ref} className={className} dangerouslySetInnerHTML={{ __html: html }} />;
}, (a, b) => a.html === b.html && (a.mentions || []).join('\n') === (b.mentions || []).join('\n')
  && a.className === b.className && a.empty === b.empty);

export default WorkBody;
