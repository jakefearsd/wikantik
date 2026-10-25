import { memo, useEffect, useRef } from 'react';
import { drawFence } from '../utils/fences/drawFence';
import { fillFenceEnvelope } from '../utils/fences/envelope';
import { addCopyButtons } from '../utils/codeCopy';

/**
 * Editor-preview rendering of a rendered fence (the `wiki-fence` element from rehypeRenderedFences). React owns
 * only the empty `figure`; the envelope children and the drawing are built imperatively so React never reconciles
 * against DOM that drawFence rewrote. Memoized on (language, source): a keystroke elsewhere in the page does not
 * redraw the diagram.
 */
function WikiFence({ language, source }) {
  const ref = useRef(null);
  useEffect(() => {
    const figure = ref.current;
    if (!figure) return undefined;
    let stale = false;
    fillFenceEnvelope(figure, language, source);
    // The same copy button the reader view gives every <pre> (PageView's addCopyButtons pass).
    addCopyButtons(figure);
    drawFence(figure, () => stale).catch((e) => console.warn('[fences] preview draw failed', e));
    return () => { stale = true; };
  }, [language, source]);
  return <figure ref={ref} className="wk-fence" />;
}

export default memo(WikiFence);
