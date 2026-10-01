import { EditorView, ViewPlugin, Decoration } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { wikiLinkTarget } from './wikiLinkTargets';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

/** The Markdown link (or autolink) containing {@code pos}: its URL text and full range, or null. */
export function linkAt(state, pos) {
  for (let node = syntaxTree(state).resolveInner(pos, 1); node; node = node.parent) {
    if (node.name === 'Link' || node.name === 'Autolink') {
      const url = node.getChild('URL');
      if (!url) return null;
      return { url: state.sliceDoc(url.from, url.to), from: node.from, to: node.to };
    }
  }
  return null;
}

/** Absolute in-app or external URL for a link target; null for anything unsafe or same-page. */
export function hrefFor(url) {
  if (!url) return null;
  if (/^https?:\/\//i.test(url)) return url;
  const name = wikiLinkTarget(url);
  if (!name) return null;
  const hash = url.includes('#') ? url.slice(url.indexOf('#')) : '';
  return `${BASE}/wiki/${encodeURIComponent(name)}${hash}`;
}

const linkMark = Decoration.mark({ class: 'cm-link-range' });

/** Ctrl/Cmd-hover reports the link under the pointer; Ctrl/Cmd-click opens it in a new tab. */
export function linkInteraction({ onHover }) {
  // Marks every link range so CSS can show a pointer/underline while Ctrl/Cmd is held
  // (the editor theme's highlight classes are generated, so there is no stable token class).
  const linkRanges = ViewPlugin.fromClass(class {
    constructor(view) { this.decorations = this.build(view); }
    update(u) { if (u.docChanged || u.viewportChanged || syntaxTree(u.startState) !== syntaxTree(u.state)) this.decorations = this.build(u.view); }
    build(view) {
      const ranges = [];
      for (const { from, to } of view.visibleRanges) {
        syntaxTree(view.state).iterate({
          from, to,
          enter: (n) => { if ((n.name === 'Link' || n.name === 'Autolink') && n.to > n.from) ranges.push(linkMark.range(n.from, n.to)); },
        });
      }
      return Decoration.set(ranges, true);
    }
  }, { decorations: (v) => v.decorations });

  const modHeld = ViewPlugin.fromClass(class {
    constructor(view) {
      this.sync = (e) => view.dom.classList.toggle('cm-mod-held', e.ctrlKey || e.metaKey);
      window.addEventListener('keydown', this.sync);
      window.addEventListener('keyup', this.sync);
      this.clear = () => view.dom.classList.remove('cm-mod-held');
      window.addEventListener('blur', this.clear);
    }
    destroy() {
      window.removeEventListener('keydown', this.sync);
      window.removeEventListener('keyup', this.sync);
      window.removeEventListener('blur', this.clear);
    }
  });

  const linkUnderPointer = (e, view) => {
    const pos = view.posAtCoords({ x: e.clientX, y: e.clientY });
    return pos == null ? null : linkAt(view.state, pos);
  };

  const handlers = EditorView.domEventHandlers({
    mousedown(e, view) {
      if (!(e.ctrlKey || e.metaKey) || e.button !== 0) return false;
      const link = linkUnderPointer(e, view);
      const href = link && hrefFor(link.url);
      if (!href) return false;
      e.preventDefault();
      window.open(href, '_blank', 'noopener');
      return true;
    },
    mousemove(e, view) {
      if (!(e.ctrlKey || e.metaKey)) { onHover(null, null); return false; }
      const link = linkUnderPointer(e, view);
      if (!link) { onHover(null, null); return false; }
      const start = view.coordsAtPos(link.from);
      const end = view.coordsAtPos(link.to);
      onHover(link.url, start && end
        ? { left: start.left, top: start.top, bottom: Math.max(start.bottom, end.bottom), right: end.right }
        : null);
      return false;
    },
    mouseleave() { onHover(null, null); return false; },
  });
  return [linkRanges, modHeld, handlers];
}
