import TurndownService from 'turndown';
import { safeDecode } from '../safeDecode';

/**
 * Clipboard HTML → wiki Markdown, for paste. Three stages:
 *
 *   1. normalise — the clipboard DOM is cleaned up in place so that it says what the author meant: Google Docs
 *      styled spans become strong/em/del/code, Word `mso-list` paragraphs become real lists, site chrome (copy
 *      buttons, permalinks, citation markers, edit links) and anything non-content (scripts, styles, forms) goes.
 *   2. gate — HTML that carries no structure (VS Code, terminals, plain web paragraphs) returns null so the caller
 *      pastes the clipboard's plain text instead; converting it would only add escapes.
 *   3. convert — Turndown with this wiki's dialect: atx headings, `-` bullets, fenced code, GFM pipe tables /
 *      strikethrough / task lists, `$…$` / `$$…$$` math (from KaTeX's TeX annotation), and links to this wiki's own
 *      `/wiki/Page` URLs rewritten to `[text](Page)`.
 *
 * Pure apart from DOMParser; `origin` (normally window.location.origin) decides which links are wiki-internal.
 */

const BLOCK_TAGS = new Set([
  'ADDRESS', 'ARTICLE', 'ASIDE', 'BLOCKQUOTE', 'BODY', 'DD', 'DETAILS', 'DIV', 'DL', 'DT', 'FIELDSET', 'FIGCAPTION',
  'FIGURE', 'FOOTER', 'FORM', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'HEADER', 'HR', 'LI', 'MAIN', 'NAV', 'OL', 'P', 'PRE',
  'SECTION', 'SUMMARY', 'TABLE', 'TBODY', 'TD', 'TFOOT', 'TH', 'THEAD', 'TR', 'UL',
]);

// Non-content elements, and the site chrome that rides along when a rendered page is copied.
const REMOVE_SELECTOR = [
  'script', 'style', 'meta', 'link', 'title', 'template', 'noscript', 'iframe', 'object', 'embed', 'video', 'audio',
  'canvas', 'svg', 'button', 'select', 'textarea', 'clipboard-copy', '.zeroclipboard-container', '.mw-editsection',
  'sup.reference', '.heading-anchor', 'a.anchor',
].join(',');

// Present after normalisation ⇒ the HTML says something plain text cannot.
const STRUCTURE_SELECTOR = 'h1,h2,h3,h4,h5,h6,ul,ol,table,blockquote,pre,hr,img,a[href],strong,b,em,i,del,s,strike,code,'
  + 'input[type=checkbox],.katex';

const BOLD = /font-weight\s*:\s*(bold|[6-9]00)\b/i;
const ITALIC = /font-style\s*:\s*italic\b/i;
const STRIKE = /text-decoration[^;]*line-through/i;
const NOT_BOLD = /font-weight\s*:\s*(normal|[1-4]00)\b/i;
const MONO = /font-family\s*:[^;]*(courier|consolas|monaco|menlo|monospace|source code|roboto mono)/i;

const unwrap = (el) => el.replaceWith(...el.childNodes);
const all = (root, selector) => Array.from(root.querySelectorAll(selector));
const isBlock = (node) => node?.nodeType === 1 && BLOCK_TAGS.has(node.nodeName);
const isBlankText = (node) => node?.nodeType === 3 && !node.textContent.trim();

function siblingSkippingBlank(node, dir) {
  let n = dir < 0 ? node.previousSibling : node.nextSibling;
  while (n && (isBlankText(n) || n.nodeType === 8)) n = dir < 0 ? n.previousSibling : n.nextSibling;
  return n;
}

/** Same-origin `/wiki/Page[#frag]` → `Page[#frag]`; anything else (other origin, query, other route, odd name) → null. */
export function wikiPageTarget(href, origin) {
  if (!origin) return null;
  let url;
  try {
    url = new URL(href, origin);
  } catch {
    return null; // not a URL at all — the caller keeps the href as written
  }
  if (url.origin !== origin || url.search) return null;
  const m = /^\/wiki\/([^/]+)$/.exec(url.pathname);
  if (!m) return null;
  const name = safeDecode(m[1]);
  if (/[\s()<>[\]]/.test(name)) return null;
  return name + url.hash;
}

/** A Markdown link destination: parentheses escaped, spaces and angle brackets percent-encoded. */
export function linkDestination(href) {
  return href.replace(/[()]/g, '\\$&').replace(/ /g, '%20').replace(/</g, '%3C').replace(/>/g, '%3E');
}

// ---- stage 1: normalise -------------------------------------------------------------------------------------------

function removeNoise(body) {
  const walker = body.ownerDocument.createTreeWalker(body, 128 /* NodeFilter.SHOW_COMMENT */);
  const comments = [];
  while (walker.nextNode()) comments.push(walker.currentNode);
  comments.forEach((c) => c.remove()); // includes Word's <![if !supportLists]> conditionals
  all(body, REMOVE_SELECTOR).forEach((el) => el.remove());
  all(body, 'input').filter((el) => (el.getAttribute('type') || '').toLowerCase() !== 'checkbox').forEach((el) => el.remove());
  all(body, 'br.Apple-interchange-newline').forEach((el) => el.remove());
  // Word's Office namespaces: <o:p> is an empty-paragraph filler; the rest (<w:…>, <v:…>) are wrappers.
  all(body, '*').filter((el) => el.localName.includes(':')).forEach((el) => {
    if (el.localName === 'o:p') el.remove();
    else unwrap(el);
  });
}

/** Word list paragraphs (`mso-list:lN levelK`) → nested ul/ol. Ordered iff the ignored marker looks like "1." / "a)". */
function wordLists(body) {
  const doc = body.ownerDocument;
  const isListPara = (el) => el?.nodeName === 'P' && /mso-list\s*:\s*l\d+\s+level\d+/i.test(el.getAttribute('style') || '');
  for (const first of all(body, 'p').filter(isListPara)) {
    if (!first.isConnected || isListPara(siblingSkippingBlank(first, -1))) continue; // handled as part of an earlier run
    const run = [];
    for (let p = first; isListPara(p); p = siblingSkippingBlank(p, 1)) run.push(p);
    const stack = []; // [{ level, list }]
    const anchor = doc.createElement('div');
    first.before(anchor);
    for (const p of run) {
      const level = Number(/level(\d+)/i.exec(p.getAttribute('style'))[1]);
      const markerEl = p.querySelector('[style*="mso-list:Ignore"], [style*="mso-list: Ignore"]');
      const marker = markerEl ? markerEl.textContent.replace(/\u00a0/g, ' ').trim() : '';
      markerEl?.remove();
      const ordered = /^\(?[0-9a-z]{1,4}[.)]$/i.test(marker);
      while (stack.length && stack[stack.length - 1].level > level) stack.pop();
      let top = stack[stack.length - 1];
      if (!top || top.level < level) {
        const list = doc.createElement(ordered ? 'ol' : 'ul');
        if (top) (top.list.lastElementChild || top.list).append(list);
        else anchor.append(list);
        top = { level, list };
        stack.push(top);
      }
      const li = doc.createElement('li');
      li.append(...p.childNodes);
      top.list.append(li);
      p.remove();
    }
    unwrap(anchor);
  }
}

/** Google Docs (and similar) express formatting as span styles; turn the ones Markdown can say into elements. */
function styledSpans(body) {
  const doc = body.ownerDocument;
  // Docs wraps the whole clipboard in <b style="font-weight:normal" id="docs-internal-guid-…">.
  all(body, 'b, strong').filter((el) => NOT_BOLD.test(el.getAttribute('style') || '')).forEach(unwrap);
  for (const span of all(body, 'span[style]')) {
    if (span.closest('.katex, pre, code')) continue;
    const style = span.getAttribute('style');
    const wrappers = [];
    if (MONO.test(style)) wrappers.push('code');
    else {
      if (BOLD.test(style)) wrappers.push('strong');
      if (ITALIC.test(style)) wrappers.push('em');
      if (STRIKE.test(style)) wrappers.push('del');
    }
    let inner = null;
    let outer = null;
    for (const tag of wrappers) {
      const el = doc.createElement(tag);
      if (inner) inner.append(el);
      else outer = el;
      inner = el;
    }
    if (!outer) {
      unwrap(span);
      continue;
    }
    inner.append(...span.childNodes);
    span.replaceWith(outer);
  }
}

function tidyFormatting(body) {
  // Some highlighters (Confluence, older sites) break code lines with <br>; the fence rule reads textContent.
  all(body, 'pre br').forEach((br) => br.replaceWith('\n'));
  // Emphasis Markdown cannot (or need not) express: inside headings / header cells, inside code, or nested in itself.
  all(body, 'h1 b, h1 strong, h2 b, h2 strong, h3 b, h3 strong, h4 b, h4 strong, h5 b, h5 strong, h6 b, h6 strong, th b, th strong')
    .forEach(unwrap);
  all(body, 'code *, pre *').filter((el) => el.nodeName !== 'CODE' || el.parentNode?.nodeName !== 'PRE').forEach(unwrap);
  const nested = { 'B,STRONG': 'b, strong', 'I,EM': 'i, em', 'DEL,S,STRIKE': 'del, s, strike' };
  for (const [tags, selector] of Object.entries(nested)) {
    const names = tags.split(',');
    all(body, selector).filter((el) => names.includes(el.parentNode?.closest(selector)?.nodeName)).forEach(unwrap);
  }
  // Adjacent runs of the same formatting (Docs splits a bold word wherever the colour changes): one element.
  for (const tag of ['strong', 'b', 'em', 'i', 'del', 'code']) {
    for (const el of all(body, tag)) {
      if (!el.isConnected || el.closest('pre')) continue;
      let next = el.nextSibling;
      while (next && next.nodeType === 1 && next.nodeName === el.nodeName) {
        el.append(...next.childNodes);
        next.remove();
        next = el.nextSibling;
      }
    }
  }
}

function tidyStructure(body) {
  // A list nested as a direct child of a list (Docs, some editors) belongs inside the preceding item.
  for (const list of all(body, 'ul > ul, ul > ol, ol > ul, ol > ol')) {
    const prev = list.previousElementSibling;
    if (prev?.nodeName === 'LI') prev.append(list);
  }
  // A list item's lone paragraph is just its text — keeps the list tight.
  for (const li of all(body, 'li')) {
    const paras = Array.from(li.children).filter((c) => c.nodeName === 'P');
    if (paras.length === 1) unwrap(paras[0]);
  }
  // Line breaks at a block boundary are layout, not content.
  for (const br of all(body, 'br')) {
    const prev = siblingSkippingBlank(br, -1);
    const next = siblingSkippingBlank(br, 1);
    if (!prev || !next || isBlock(prev) || isBlock(next)) br.remove();
  }
  for (const a of all(body, 'a')) {
    const href = (a.getAttribute('href') || '').trim();
    if (/^javascript:/i.test(href)) {
      unwrap(a);
      continue;
    }
    if (!a.textContent.trim() && !a.querySelector('img')) a.remove(); // icon-only permalinks etc.
  }
}

/** Removes embedded images a Markdown link cannot carry; returns how many. */
function dropEmbeddedImages(body) {
  const dropped = all(body, 'img').filter((img) => DROPPED_IMAGE.test((img.getAttribute('src') || '').trim()));
  dropped.forEach((img) => img.remove());
  return dropped.length;
}

/** Cleans the clipboard DOM in place; returns the number of embedded images dropped. */
function normalise(body) {
  removeNoise(body);
  const droppedImages = dropEmbeddedImages(body);
  wordLists(body);
  styledSpans(body);
  tidyFormatting(body);
  tidyStructure(body);
  const walker = body.ownerDocument.createTreeWalker(body, 4 /* NodeFilter.SHOW_TEXT */);
  while (walker.nextNode()) {
    const t = walker.currentNode;
    if (t.textContent.includes('\u00a0')) t.textContent = t.textContent.replace(/\u00a0/g, ' ');
  }
  return droppedImages;
}

// ---- stage 3: convert ---------------------------------------------------------------------------------------------

const DROPPED_IMAGE = /^(data|blob|file|cid):/i;
const LANGUAGE_PATTERNS = [/(?:^|\s)(?:language|lang)-([\w+#-]+)/, /(?:^|\s)highlight-source-([\w+#-]+)/, /brush:\s*([\w+#-]+)/];

function codeLanguage(pre) {
  const candidates = [pre.querySelector('code'), pre, pre.parentElement, pre.parentElement?.parentElement].filter(Boolean);
  for (const el of candidates) {
    const text = `${el.getAttribute('class') || ''} ${el.getAttribute('data-syntaxhighlighter-params') || ''}`;
    for (const re of LANGUAGE_PATTERNS) {
      const m = re.exec(text);
      if (m) return m[1].toLowerCase();
    }
  }
  return '';
}

const texOf = (node) => node.querySelector('annotation[encoding="application/x-tex"]')?.textContent.trim() ?? node.textContent.trim();

function buildService(origin) {
  const service = new TurndownService({
    headingStyle: 'atx',
    hr: '---',
    bulletListMarker: '-',
    codeBlockStyle: 'fenced',
    emDelimiter: '*',
    strongDelimiter: '**',
    br: '\\',
  });
  const baseEscape = service.escape.bind(service);
  // `$` starts inline math in this wiki (Pandoc rule), so prose dollars are escaped; so is a `<` that would open an
  // HTML tag ("the <div> element" copied from rendered text).
  service.escape = (text) => baseEscape(text).replace(/\$/g, '\\$').replace(/<(?=[A-Za-z/!?])/g, '\\<');
  const cellMarkdown = (cell) => service.turndown(cell.innerHTML).replace(/\\\n|\n+/g, ' ').replace(/\|/g, '\\|').trim();

  service.addRule('katexDisplay', {
    filter: (node) => node.classList?.contains('katex-display'),
    replacement: (_content, node) => `\n\n$$\n${texOf(node)}\n$$\n\n`,
  });
  service.addRule('katexInline', {
    filter: (node) => node.classList?.contains('katex'),
    replacement: (_content, node) => `$${texOf(node)}$`,
  });
  service.addRule('fencedPre', {
    filter: 'pre',
    replacement: (_content, node) => {
      const code = node.textContent.replace(/\n$/, '');
      const longestRun = Math.max(0, ...(code.match(/`+/g) || []).map((run) => run.length));
      const fence = '`'.repeat(Math.max(3, longestRun + 1));
      return `\n\n${fence}${codeLanguage(node)}\n${code}\n${fence}\n\n`;
    },
  });
  service.addRule('strikethrough', {
    filter: ['del', 's', 'strike'],
    replacement: (content) => (content.trim() ? `~~${content}~~` : content),
  });
  service.addRule('taskCheckbox', {
    filter: (node) => node.nodeName === 'INPUT' && (node.getAttribute('type') || '').toLowerCase() === 'checkbox',
    replacement: (_content, node) => (node.hasAttribute('checked') ? '[x] ' : '[ ] '),
  });
  service.addRule('listItem', {
    filter: 'li',
    replacement: (content, node, options) => {
      const parent = node.parentNode;
      let prefix = `${options.bulletListMarker} `;
      if (parent.nodeName === 'OL') {
        const start = Number(parent.getAttribute('start')) || 1;
        prefix = `${start + Array.prototype.indexOf.call(parent.children, node)}. `;
      }
      const body = content.replace(/^\n+/, '').replace(/\n+$/, '\n').replace(/^(\[[ x]\]) +/, '$1 ')
        .replace(/\n/gm, `\n${' '.repeat(prefix.length)}`);
      return prefix + body + (node.nextSibling && !/\n$/.test(body) ? '\n' : '');
    },
  });
  service.addRule('link', {
    filter: (node) => node.nodeName === 'A' && node.getAttribute('href'),
    replacement: (content, node) => {
      const href = node.getAttribute('href').trim();
      if (!content.trim()) return content;
      const autolink = (content === href || content === service.escape(href)) && /^[a-z][a-z0-9+.-]*:/i.test(href);
      if (autolink) return `<${href}>`;
      return `[${content}](${linkDestination(wikiPageTarget(href, origin) ?? href)})`;
    },
  });
  service.addRule('image', {
    filter: 'img',
    replacement: (_content, node) => {
      const src = (node.getAttribute('src') || '').trim();
      if (!src) return '';
      const alt = (node.getAttribute('alt') || '').replace(/[[\]]/g, '\\$&');
      return `![${alt}](${linkDestination(src)})`;
    },
  });
  service.addRule('table', {
    filter: 'table',
    replacement: (_content, node) => {
      const rows = all(node, 'tr').filter((tr) => tr.closest('table') === node);
      const cellsOf = (tr) => Array.from(tr.children).filter((c) => c.nodeName === 'TD' || c.nodeName === 'TH');
      const cells = rows.flatMap(cellsOf);
      if (cells.length === 0) return '';
      // Layout tables: a single cell, or cells holding block structure, cannot be a pipe table — lay them out as blocks.
      if (cells.length === 1 || cells.some((c) => c.querySelector('h1,h2,h3,h4,h5,h6,ul,ol,pre,blockquote,table'))) {
        return `\n\n${cells.map((c) => service.turndown(c.innerHTML)).filter(Boolean).join('\n\n')}\n\n`;
      }
      const grid = rows.map((tr) => cellsOf(tr).flatMap((cell) => {
        const span = Math.max(1, Number(cell.getAttribute('colspan')) || 1);
        return [cellMarkdown(cell), ...Array(span - 1).fill('')];
      })).filter((row) => row.length > 0);
      const width = Math.max(...grid.map((row) => row.length));
      const line = (row) => `| ${[...row, ...Array(width - row.length).fill('')].join(' | ')} |`;
      // GFM needs a header row: the first row serves, minus the bold a heading row usually carries.
      const header = grid[0].map((c) => c.replace(/^\*\*(.+)\*\*$/, '$1'));
      return `\n\n${[line(header), line(Array(width).fill('---')), ...grid.slice(1).map(line)].join('\n')}\n\n`;
    },
  });
  return service;
}

/**
 * Trailing whitespace off and blank-line runs collapsed — outside code fences only, whose bodies are kept verbatim.
 */
function tidyOutput(markdown) {
  const out = [];
  let fence = null;
  for (const raw of markdown.split('\n')) {
    if (fence) {
      out.push(raw);
      if (raw.trim() === fence) fence = null;
      continue;
    }
    const line = raw.replace(/[ \t]+$/, '');
    const opening = /^\s*(`{3,}|~{3,})/.exec(line);
    if (opening) fence = opening[1];
    if (line === '' && out[out.length - 1] === '') continue;
    out.push(line);
  }
  return out.join('\n').trim();
}

/**
 * Converts clipboard HTML to Markdown. Returns `{ markdown, droppedImages, inline }`, or null when the HTML carries
 * no structure worth converting (the caller should then paste the plain text). `droppedImages` counts embedded images
 * (data:/blob:/file: sources) that a Markdown link cannot carry; `inline` is true when the HTML held no block
 * element (a fragment of a sentence).
 */
export function htmlToMarkdown(html, { origin = '' } = {}) {
  if (!html || typeof html !== 'string') return null;
  const body = new DOMParser().parseFromString(html, 'text/html').body;
  if (!body) return null;
  const droppedImages = normalise(body);
  if (!body.querySelector(STRUCTURE_SELECTOR)) return null;
  const markdown = tidyOutput(buildService(origin).turndown(body.innerHTML));
  if (!markdown) return null;
  const inline = !Array.from(body.querySelectorAll('*')).some((el) => BLOCK_TAGS.has(el.nodeName));
  return { markdown, droppedImages, inline };
}
