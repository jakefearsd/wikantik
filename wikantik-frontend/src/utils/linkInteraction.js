import { EditorView, ViewPlugin, Decoration } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { wikiLinkTarget } from './wikiLinkTargets';
import { findWikiLinks, wikiLinkHref } from './wikiLinkSyntax';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

const CODE_NODES = new Set(['InlineCode', 'FencedCode', 'CodeBlock', 'CodeText']);

function inCode(state, pos) {
  for (let n = syntaxTree(state).resolveInner(pos, 1); n; n = n.parent) {
    if (CODE_NODES.has(n.name)) return true;
  }
  return false;
}

/** Native [[wikilinks]] on one document line, as absolute ranges, skipping any inside code. */
function wikiLinksOnLine(state, line, resolve) {
  return findWikiLinks(line.text)
    .map((m) => ({ url: wikiLinkHref(m, resolve?.(m.target.toLowerCase()) || undefined), from: line.from + m.from, to: line.from + m.to }))
    .filter((l) => !inCode(state, l.from));
}

/**
 * The link (Markdown, autolink or native wikilink) containing {@code pos}: its URL text and full range, or null.
 * {@code resolve} maps a lowercased wikilink target to its canonical page name (string) or a falsy value;
 * unresolved wikilinks keep their raw target.
 */
export function linkAt(state, pos, resolve) {
  // Lezer reads `[Page]` inside `[[Page]]` as a URL-less Link, so wikilinks are checked first.
  const wiki = wikiLinksOnLine(state, state.doc.lineAt(pos), resolve).find((l) => pos >= l.from && pos <= l.to);
  if (wiki) return wiki;
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
export function linkInteraction({ onHover, resolve }) {
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
      const doc = view.state.doc;
      for (const { from, to } of view.visibleRanges) {
        for (let ln = doc.lineAt(from); ln.from <= to; ln = doc.line(ln.number + 1)) {
          for (const l of wikiLinksOnLine(view.state, ln)) ranges.push(linkMark.range(l.from, l.to));
          if (ln.number >= doc.lines) break;
        }
      }
      ranges.sort((a, b) => a.from - b.from);
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
    return pos == null ? null : linkAt(view.state, pos, resolve);
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
