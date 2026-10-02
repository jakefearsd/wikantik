import { useEffect, useMemo, useState } from 'react';
import { loadEmbed, clearEmbedCache } from '../utils/embedCache';
import { slugify } from '../utils/headings';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

/** Drop every cached embed so the next render re-fetches (called when the editor preview is reopened). */
export function clearWikiEmbedCache() {
  clearEmbedCache();
}

/**
 * Live preview of an {@code ![[Page#Section]]} embed. The body html is produced by the server (sanitized
 * when allowHtml is on, built from escaped markdown otherwise) — the same trust posture as PageView
 * rendering page.contentHtml. The title is rendered here, not by the server.
 */
export default function WikiEmbed({ page, section = null }) {
  const [result, setResult] = useState({ key: null, state: 'loading', html: '' });
  const key = `${page}#${section || ''}`;

  useEffect(() => {
    let live = true;
    loadEmbed(page, section).then(
      (r) => { if (live) setResult({ key, state: 'ok', html: r?.html || '' }); },
      (err) => {
        if (!live) return;
        if (err?.status === 403) {
          setResult({ key, state: 'restricted', html: '' });
        } else {
          console.warn(`[wiki-embed] could not load embed of "${key}":`, err?.message);
          setResult({ key, state: 'error', html: '' });
        }
      },
    );
    return () => { live = false; };
  }, [page, section, key]);

  const state = result.key === key ? result.state : 'loading';
  const html = result.key === key ? result.html : '';

  // React 19 re-applies dangerouslySetInnerHTML on every re-render, wiping DOM mutated since; memoize the host.
  const host = useMemo(() => <div dangerouslySetInnerHTML={{ __html: html }} />, [html]);

  const label = section ? `${page} › ${section}` : page;
  const href = `${BASE}/wiki/${encodeURIComponent(page)}${section ? '#' + slugify(section) : ''}`;

  let body;
  if (state === 'ok') body = host;
  else if (state === 'restricted') body = <p className="wiki-embed-restricted">You don't have access to this page.</p>;
  else if (state === 'error') body = <p className="wiki-embed-error">Embed could not be loaded</p>;
  else body = <p className="wiki-embed-loading">Loading…</p>;

  return (
    <div className="wiki-embed" data-embed={page} data-section={section || undefined}>
      <div className="wiki-embed-title">
        {state === 'restricted' ? label : <a className="wikipage" href={href}>{label}</a>}
      </div>
      <div className="wiki-embed-body">{body}</div>
    </div>
  );
}

/** react-markdown components adapter for the {@code wiki-embed} element emitted by remarkWikiLinks. */
export function WikiEmbedElement(props) {
  return <WikiEmbed page={props['data-page']} section={props['data-section'] || null} />;
}
