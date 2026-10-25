import { memo, useEffect, useRef } from 'react';
import { drawFence } from '../utils/fences/drawFence';
import { fillFenceEnvelope } from '../utils/fences/envelope';
import { rendererForLanguage } from '../utils/fences/registry';
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
    // A server-drawn fence takes a network round trip per source change (after a debounce), so the previous
    // drawing stays up, dimmed (data-fence-pending), until the new draw settles; browser-kind draws are local
    // and fast, so they start from a clean envelope.
    const keep = rendererForLanguage(language)?.kind === 'server'
      ? figure.querySelector('.wk-fence-drawing') : null;
    fillFenceEnvelope(figure, language, source);
    if (keep) {
      keep.setAttribute('data-fence-pending', '');
      figure.insertBefore(keep, figure.querySelector('pre.wk-fence-source'));
    }
    // The same copy button the reader view gives every <pre> (PageView's addCopyButtons pass).
    addCopyButtons(figure);
    drawFence(figure, () => stale)
      .then((state) => { if (state !== 'stale') keep?.remove(); })
      .catch((e) => console.warn('[fences] preview draw failed', e));
    return () => { stale = true; };
  }, [language, source]);
  return <figure ref={ref} className="wk-fence" />;
}

export default memo(WikiFence);
