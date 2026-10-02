// Native [[wikilinks]] and ![[embeds]] in the editor preview. Mirrors the server's native-links
// extension; __fixtures__/wikilinks.json pins the two together. Hrefs are relative (`Name`,
// `Name#slug`) because the preview's missing-link / link-preview machinery only recognises those.
import { visit, SKIP } from 'unist-util-visit';
import { isImageFileName, wikiLinkDisplayText, wikiLinkHref, wikiLinksInTextNode } from './wikiLinkSyntax';

const attachUrl = (owner, file) => `/attach/${encodeURIComponent(owner)}/${encodeURIComponent(file)}`;
const textNode = (value) => ({ type: 'text', value });

function attachmentOf(ref, pageName, attachments) {
  if (ref.isAttachment) return { owner: ref.pageName, file: ref.fileName };
  if (!ref.isSamePage && attachments.some((a) => a.fileName === ref.target)) {
    return { owner: pageName, file: ref.target };
  }
  return null;
}

function attachmentNode(ref, att) {
  const url = attachUrl(att.owner, att.file);
  if (ref.embed && isImageFileName.test(att.file)) {
    const [w, h] = ref.size || [];
    const hProperties = {};
    if (w) hProperties.width = w;
    if (h > 0) hProperties.height = h;
    return { type: 'image', url, alt: att.file, data: { hProperties } };
  }
  return { type: 'link', url, children: [textNode(wikiLinkDisplayText(ref))] };
}

function pageLinkNode(ref, resolved) {
  const name = ref.isSamePage ? undefined : resolved.get(ref.target.toLowerCase());
  const node = {
    type: 'link',
    url: wikiLinkHref(ref, typeof name === 'string' ? name : undefined),
    children: [textNode(wikiLinkDisplayText(ref))],
  };
  if (name === null) {
    node.data = {
      hProperties: {
        className: ['createpage'],
        'data-missing-page': ref.target,
        title: `${ref.target} does not exist yet`,
      },
    };
  }
  return node;
}

/** Replaces a text node with text/link/image nodes around its wikilinks. */
function splitText(node, refs, make) {
  const out = [];
  let pos = 0;
  for (const ref of refs) {
    if (ref.from > pos) out.push(textNode(node.value.slice(pos, ref.from)));
    out.push(make(ref));
    pos = ref.to;
  }
  if (pos < node.value.length) out.push(textNode(node.value.slice(pos)));
  return out;
}

/** The page-embed refs of a paragraph made only of page embeds, whitespace and breaks; else null. */
function embedOnlyRefs(paragraph, source, attachments, pageName) {
  const embeds = [];
  for (const child of paragraph.children) {
    if (child.type === 'break') continue;
    if (child.type !== 'text') return null;
    const refs = wikiLinksInTextNode(child, source);
    let pos = 0;
    for (const ref of refs) {
      if (child.value.slice(pos, ref.from).trim() !== '') return null;
      if (!ref.embed || ref.isSamePage || attachmentOf(ref, pageName, attachments)) return null;
      embeds.push(ref);
      pos = ref.to;
    }
    if (child.value.slice(pos).trim() !== '') return null;
  }
  return embeds.length ? embeds : null;
}

function embedElement(ref, resolved, paragraph) {
  const name = resolved.get(ref.target.toLowerCase());
  return {
    type: 'wikiEmbed',
    data: {
      hName: 'wiki-embed',
      // data-page (canonical once resolved) is what the embed shows and links to; data-target (as written) is
      // what it fetches — the server resolves it the same way, and the fetch key then stays stable when the
      // resolution arrives (no second request) and matches the live-preview widget's key.
      hProperties: {
        'data-page': typeof name === 'string' ? name : ref.target,
        'data-target': ref.target,
        'data-section': ref.heading || '',
      },
    },
    position: paragraph.position,
  };
}

export function remarkWikiLinks({ resolved = new Map(), attachments = [], pageName = '' } = {}) {
  return (tree, file) => {
    const source = file === undefined ? undefined : String(file);

    visit(tree, 'paragraph', (paragraph, index, parent) => {
      if (!parent || index == null) return;
      const embeds = embedOnlyRefs(paragraph, source, attachments, pageName);
      if (!embeds) return;
      const replacement = embeds.map((ref) => embedElement(ref, resolved, paragraph));
      parent.children.splice(index, 1, ...replacement);
      return [SKIP, index + replacement.length];
    });

    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null || parent.type === 'link' || parent.type === 'linkReference') return;
      const refs = wikiLinksInTextNode(node, source);
      if (!refs.length) return;
      const parts = splitText(node, refs, (ref) => {
        const att = attachmentOf(ref, pageName, attachments);
        return att ? attachmentNode(ref, att) : pageLinkNode(ref, resolved);
      });
      parent.children.splice(index, 1, ...parts);
      return [SKIP, index + parts.length];
    });
  };
}

export default remarkWikiLinks;
