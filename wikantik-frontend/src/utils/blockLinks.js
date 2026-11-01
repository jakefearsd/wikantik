import { blockFingerprint } from './blockIds';

// Reader-side "copy block link": a hover-reveal gutter button on each of the page's OWN blocks (`[data-block]`
// outside included pages and embeds, which carry ordinals and ids of their own). A block with an id copies
// `[[Page#^id]]`; one without asks the server to assign an id first. The second button copies the URL instead.

const FOREIGN = '.inserted-page, .wiki-embed';
const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';
const NOT_BUTTON_TEXT = '.block-anchor, .katex-mathml, .code-copy-btn';

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

function makeButton(className, label, title, glyph) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = className;
  b.setAttribute('aria-label', label);
  b.title = title;
  b.textContent = glyph;
  return b;
}

function addButtons(el) {
  const holder = document.createElement('span');
  holder.className = 'block-anchor';
  holder.appendChild(makeButton('block-link-btn', 'Copy link to this block', 'Copy block link', '¶'));
  holder.appendChild(makeButton('block-link-url-btn', 'Copy URL of this block', 'Copy URL', '↗'));
  el.appendChild(holder);
}

/** Assigns an id to `el` via the server, updating the shared version; throws a message-bearing Error on refusal. */
async function ensureId(el, ctx) {
  if (el.id.startsWith('^')) return el.id.slice(1);
  if (!ctx.canAssign) return null;
  const result = await ctx.assign({
    version: ctx.version,
    index: Number(el.dataset.block),
    fingerprint: blockFingerprint(blockText(el)),
  });
  ctx.version = result.version;
  el.id = `^${result.id}`;
  return result.id;
}

function messageFor(err) {
  if (err?.status === 409 && REASON_MESSAGES[err.body?.reason]) return REASON_MESSAGES[err.body.reason];
  console.warn('[blockLinks] could not create a block link', err?.message || err);
  return GENERIC_ERROR;
}

async function copyLink(el, url, ctx) {
  try {
    const id = await ensureId(el, ctx);
    if (id == null) return;
    const text = url
      ? `${window.location.origin}${BASE}/wiki/${encodeURIComponent(ctx.pageName)}#^${id}`
      : `[[${ctx.pageName}#^${id}]]`;
    await navigator.clipboard.writeText(text);
    ctx.onCopy?.();
  } catch (err) {
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
  const ctx = contexts.get(root) || {};
  Object.assign(ctx, options);
  contexts.set(root, ctx);

  root.querySelectorAll('[data-block]').forEach((el) => {
    if (!isOwn(el) || el.dataset.blocklinkInjected) return;
    if (!el.id.startsWith('^') && !ctx.canAssign) return;
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
  root.addEventListener('click', onClick);
  return () => root.removeEventListener('click', onClick);
}
