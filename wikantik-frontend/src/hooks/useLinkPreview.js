import { createElement, useCallback, useEffect, useRef, useState } from 'react';
import LinkPreviewCard from '../components/LinkPreviewCard';
import { loadPreview } from './usePagePreview';
import { wikiLinkTarget } from '../utils/wikiLinkTargets';
import { safeDecode } from '../utils/safeDecode';

const OPEN_MS = 400;
const GRACE_MS = 200;
const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

export function previewTargetOf(anchor) {
  const href = anchor?.getAttribute?.('href');
  if (!href) return null;
  const missing = anchor.classList.contains('createpage');
  const local = href.startsWith(`${BASE}/wiki/`) ? href.slice(BASE.length) : href;
  const wiki = /^\/wiki\/([^?#]+)(?:\?[^#]*)?(?:#(.*))?$/.exec(local);
  if (wiki) return { name: safeDecode(wiki[1]), section: wiki[2] || null, missing };
  // The server renders a missing page as <a class="createpage" href="/edit/Name">; a bare /edit/ link is an
  // edit action, not a page reference.
  const localEdit = href.startsWith(`${BASE}/edit/`) ? href.slice(BASE.length) : href;
  const edit = /^\/edit\/([^?#/]+)(?:\?[^#]*)?(?:#(.*))?$/.exec(localEdit);
  if (edit) return missing ? { name: safeDecode(edit[1]), section: edit[2] || null, missing: true } : null;
  const name = wikiLinkTarget(href);
  if (!name) return null;
  const hash = href.includes('#') ? href.slice(href.indexOf('#') + 1) : '';
  return { name, section: hash || null, missing };
}

/**
 * Delegated hover/focus previews for every wiki link inside {@code containerRef}. {@code rebindKey}
 * re-attaches the listeners when the container element mounts later than the hook (e.g. after loading).
 */
export function useLinkPreview(containerRef, rebindKey = null) {
  const [state, setState] = useState(null); // { rect, result }
  const openTimer = useRef(null);
  const closeTimer = useRef(null);
  const ctl = useRef(null);
  const shown = useRef(null); // the anchor the card currently belongs to

  const cancelTimers = useCallback(() => { clearTimeout(openTimer.current); clearTimeout(closeTimer.current); }, []);
  const close = useCallback(() => {
    cancelTimers();
    ctl.current?.abort();
    shown.current = null;
    setState(null);
  }, [cancelTimers]);
  const scheduleClose = useCallback(() => {
    clearTimeout(openTimer.current); // a pointer that left before the open delay must not open later
    clearTimeout(closeTimer.current);
    closeTimer.current = setTimeout(close, GRACE_MS);
  }, [close]);

  const keepOpen = useCallback(() => clearTimeout(closeTimer.current), []);

  const open = useCallback((anchor) => {
    const target = previewTargetOf(anchor);
    if (!target) return;
    if (shown.current === anchor) { clearTimeout(closeTimer.current); return; }
    cancelTimers();
    openTimer.current = setTimeout(() => {
      const rect = anchor.getBoundingClientRect();
      shown.current = anchor;
      if (target.missing) { setState({ rect, result: { status: 'missing' } }); return; }
      ctl.current?.abort();
      ctl.current = new AbortController();
      setState({ rect, result: null });
      loadPreview(target.name, target.section, ctl.current.signal)
        .then((result) => setState((s) => (s ? { ...s, result } : s)))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          console.warn('[link-preview] preview failed', target.name, err?.message || err);
          setState(null);
        });
    }, OPEN_MS);
  }, [cancelTimers]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return undefined;
    const over = (e) => { const a = e.target.closest?.('a[href]'); if (a && el.contains(a)) open(a); };
    const out = (e) => { if (e.target.closest?.('a[href]')) scheduleClose(); };
    const key = (e) => { if (e.key === 'Escape') close(); };
    el.addEventListener('mouseover', over);
    el.addEventListener('mouseout', out);
    el.addEventListener('focusin', over);
    el.addEventListener('focusout', out);
    window.addEventListener('keydown', key);
    window.addEventListener('scroll', close, true);
    return () => {
      el.removeEventListener('mouseover', over);
      el.removeEventListener('mouseout', out);
      el.removeEventListener('focusin', over);
      el.removeEventListener('focusout', out);
      window.removeEventListener('keydown', key);
      window.removeEventListener('scroll', close, true);
      cancelTimers();
      ctl.current?.abort();
    };
  }, [containerRef, rebindKey, cancelTimers, open, close, scheduleClose]);

  // The handlers only touch the timer refs when invoked (never during render): a false positive.
  /* eslint-disable react-hooks/refs */
  const card = state ? createElement(LinkPreviewCard, {
    rect: state.rect, result: state.result,
    onMouseEnter: keepOpen,
    onMouseLeave: scheduleClose,
  }) : null;
  /* eslint-enable react-hooks/refs */
  return { card };
}
