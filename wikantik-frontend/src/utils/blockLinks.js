import { blockFingerprint } from './blockIds';

// Reader-side "copy block link": a hover-reveal gutter button on each of the page's OWN blocks (`[data-block]`
// outside included pages and embeds, which carry ordinals and ids of their own). A block with an id copies
// `[[Page#^id]]`; one without asks the server to assign an id first. The second button copies the URL instead.

const FOREIGN = '.inserted-page, .wiki-embed';
const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
// Our own buttons, the KaTeX MathML twin of a formula, the copy button, and a drawn fence's picture and toolbar.
const NOT_BUTTON_TEXT = '.block-anchor, .katex-mathml, .code-copy-btn, .wk-fence-drawing, .wk-fence-toolbar';

const REASON_MESSAGES = {
  stale: 'This page changed — reload to copy a block link',
  'block-not-located': "Couldn't locate this block — add an id in the editor",
  derived: "This page is generated; block ids can't be added here",
};
const GENERIC_ERROR = "Couldn't copy link";

/** Handlers read the latest options, so a re-run after a refetch updates buttons that already exist. */
const contexts = new WeakMap();

/** The text of a block as the server fingerprints it: without our buttons or the KaTeX MathML twin of a formula. */
export function blockText(el) {
  const clone = el.cloneNode(true);
  clone.querySelectorAll(NOT_BUTTON_TEXT).forEach((n) => n.remove());
  return clone.textContent;
}

const isOwn = (el) => !el.closest(FOREIGN);

/**
 * The element a `#^id` fragment addresses: the page's own block, else an embed wrapper that carries the id itself.
 * Never an element inside an included page (it may share the id and come earlier in the document).
 */
export function findBlockTarget(root, id) {
  const matches = [...root.querySelectorAll('[id]')].filter((el) => el.id === id);
  return matches.find(isOwn)
    || matches.find((el) => el.classList.contains('wiki-embed') && !el.parentElement?.closest(FOREIGN))
    || null;
}

// The glyphs are CSS `::before` content, so the buttons add no text nodes to the article (comment anchoring reads them).
function makeButton(className, label, title) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = className;
  b.setAttribute('aria-label', label);
  b.title = title;
  return b;
}

function addButtons(el) {
  const holder = document.createElement('span');
  holder.className = 'block-anchor';
  holder.appendChild(makeButton('block-link-btn', 'Copy link to this block', 'Copy block link'));
  // One tab stop per block: the second button is reached with the arrow keys.
  const url = makeButton('block-link-url-btn', 'Copy URL of this block', 'Copy URL');
  url.setAttribute('tabindex', '-1');
  holder.appendChild(url);
  el.appendChild(holder);
}

function removeButtons(el) {
  el.querySelectorAll(':scope > .block-anchor').forEach((n) => n.remove());
  delete el.dataset.blocklinkInjected;
}

const pending = new WeakMap();   // block element → the assignment in flight

const NO_LINK = new Error('no block link');

/** One assignment at a time per root, each using the version the previous one returned. */
async function assignNow(el, ctx) {
  const result = await ctx.assign({
    version: ctx.version,
    index: Number(el.dataset.block),
    fingerprint: blockFingerprint(blockText(el)),
  });
  ctx.version = Math.max(ctx.version ?? 0, result.version ?? 0);
  el.id = `^${result.id}`;
  return result.id;
}

/** The block's id: its own, one in flight (shared by repeat clicks), or a freshly assigned one. */
function ensureId(el, ctx) {
  if (el.id.startsWith('^')) return Promise.resolve(el.id.slice(1));
  if (!ctx.canAssign) return Promise.reject(NO_LINK);
  let p = pending.get(el);
  if (p) return p;
  ctx.chain = (ctx.chain || Promise.resolve()).then(() => assignNow(el, ctx));
  p = ctx.chain;
  ctx.chain = p.catch(() => {});   // a failed assign must not block the next one
  pending.set(el, p);
  el.setAttribute('aria-busy', 'true');
  const done = () => { pending.delete(el); el.removeAttribute('aria-busy'); };
  p.then(done, done);
  return p;
}

function messageFor(err) {
  if (err?.status === 409 && REASON_MESSAGES[err.body?.reason]) return REASON_MESSAGES[err.body.reason];
  console.warn('[blockLinks] could not create a block link', err?.message || err);
  return GENERIC_ERROR;
}

/** Writes `textPromise`'s text. ClipboardItem takes the promise, so the write starts inside the user gesture. */
async function writeClipboard(textPromise) {
  if (typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
    const blob = textPromise.then((t) => new Blob([t], { type: 'text/plain' }));
    await navigator.clipboard.write([new ClipboardItem({ 'text/plain': blob })]);
    return;
  }
  await navigator.clipboard.writeText(await textPromise);
}

async function copyLink(el, url, ctx) {
  const text = ensureId(el, ctx).then((id) => (url
    ? `${window.location.origin}${BASE}/wiki/${encodeURIComponent(ctx.pageName)}#^${id}`
    : `[[${ctx.pageName}#^${id}]]`));
  try {
    await writeClipboard(text);
    ctx.onCopy?.();
  } catch (err) {
    if (err === NO_LINK) return;
    ctx.onError?.(messageFor(err));
  }
}

/**
 * Adds the copy-link buttons to `root`'s own blocks. Idempotent (`data-blocklink-injected`); one delegated click
 * listener serves every button.
 *
 * @param {Element} root
 * @param {{ pageName: string, canAssign?: boolean, version?: number, assign?: Function,
 *           onCopy?: () => void, onError?: (message: string) => void }} options
 *        `assign({version, index, fingerprint})` resolves `{id, version}` or rejects with the failed request's error.
 * @returns {() => void} removes the listener (the buttons stay; a later call re-attaches)
 */
export function addBlockLinkButtons(root, options = {}) {
  if (!root) return () => {};
  let ctx = contexts.get(root);
  // The article element outlives a page change: another page starts with its own version and queue.
  if (!ctx || ctx.pageName !== options.pageName) ctx = {};
  // The known version only moves forward: a refetch can hand back the version from before an assign we made.
  const version = Math.max(ctx.version ?? 0, options.version ?? 0);
  Object.assign(ctx, options, { version });
  contexts.set(root, ctx);

  root.querySelectorAll('[data-block]').forEach((el) => {
    if (!isOwn(el)) return;
    const eligible = el.id.startsWith('^') || ctx.canAssign;
    if (el.dataset.blocklinkInjected) {
      if (!eligible) removeButtons(el);
      return;
    }
    if (!eligible) return;
    el.dataset.blocklinkInjected = '1';
    addButtons(el);
  });

  const onClick = (e) => {
    const button = e.target.closest?.('.block-link-btn, .block-link-url-btn');
    const el = button?.closest('[data-block]');
    if (!el || !root.contains(el)) return;
    e.preventDefault();
    copyLink(el, button.classList.contains('block-link-url-btn'), ctx);
  };
  const onKeyDown = (e) => {
    const from = e.target.closest?.('.block-link-btn, .block-link-url-btn');
    if (!from || (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft')) return;
    const holder = from.closest('.block-anchor');
    const to = holder?.querySelector(e.key === 'ArrowRight' ? '.block-link-url-btn' : '.block-link-btn');
    if (to) { e.preventDefault(); to.focus(); }
  };
  root.addEventListener('click', onClick);
  root.addEventListener('keydown', onKeyDown);
  return () => {
    root.removeEventListener('click', onClick);
    root.removeEventListener('keydown', onKeyDown);
  };
}
