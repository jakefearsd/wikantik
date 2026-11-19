import { useCallback, useState } from 'react';

const KEY = 'wikantik.editor.sidePane';

function initialState() {
  try {
    if (localStorage.getItem(KEY) === 'hidden') return { pane: 'hidden', last: 'preview' };
  } catch (err) {
    console.warn('[editor-side-pane] could not read the saved side pane state', err?.message || err);
  }
  return { pane: 'preview', last: 'preview' };
}

function remember(pane) {
  try {
    localStorage.setItem(KEY, pane === 'hidden' ? 'hidden' : 'shown');
  } catch (err) {
    console.warn('[editor-side-pane] could not save the side pane state', err?.message || err);
  }
}

/**
 * The editor's side pane: hidden, the rendered preview, or the page's Markdown source. Whether it is shown is
 * remembered per browser (shown by default); which content it shows is not, and it opens on the preview.
 * `last` is what `toggle` restores. Returns `[state, choose(pane), toggle()]`.
 */
export function useSidePane() {
  const [state, setState] = useState(initialState);
  const choose = useCallback((pane) => {
    remember(pane);
    setState({ pane, last: pane });
  }, []);
  const toggle = useCallback(() => setState(({ pane, last }) => {
    const next = pane === 'hidden' ? { pane: last, last } : { pane: 'hidden', last: pane };
    remember(next.pane);
    return next;
  }), []);
  return [state, choose, toggle];
}
