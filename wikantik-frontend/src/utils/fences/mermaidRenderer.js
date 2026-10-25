// The Mermaid drawing core. Imported only through registry.js's dynamic `load()`, so Mermaid is fetched only by a
// page that has a Mermaid fence — and then only its core plus the chunk for each diagram type drawn (Mermaid keeps
// its own lazy split; see the codeSplitting comment in vite.config.js and the chunking rule in registry.js).
import mermaid from 'mermaid';

// Config keys a diagram may never override. Mermaid 12.1.0's defaults (secure … maxEdges) plus the keys the
// server policy also guards (controller Ruling K). The server already refuses every `%%{…}%%` directive and
// all front matter; this is defense in depth should a check ever be missed.
export const SECURE_KEYS = Object.freeze([
  'secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'suppressErrorRendering', 'maxEdges',
  'dompurifyConfig', 'htmlLabels',
]);

let initializedTheme = null;
let sequence = 0;
// Mermaid's configuration is global, so draws run one at a time: a draw for one theme must not render with
// the theme another draw just initialised.
let busy = Promise.resolve();

function initialize(theme) {
  if (initializedTheme === theme) return;
  mermaid.initialize({
    startOnLoad: false,
    // One notch below 'strict': link targets work, script and click callbacks do not (callbacks also need
    // bindFunctions, which this module never calls).
    securityLevel: 'antiscript',
    theme,
    // Throw on a parse error instead of leaving Mermaid's error drawing behind in document.body.
    suppressErrorRendering: true,
    secure: [...SECURE_KEYS],
  });
  initializedTheme = theme;
}

function accessibleLabel(svg) {
  const title = [...svg.children].find((el) => el.tagName.toLowerCase() === 'title');
  const text = title?.textContent?.trim();
  return text || 'Diagram';
}

/**
 * Draws `source` into `host` (replacing its content). Throws when Mermaid cannot parse or draw it. The SVG
 * gets role="img" and an aria-label from the diagram's accTitle, else "Diagram".
 *
 * @param {Element} host
 * @param {string} source
 * @param {{ dark?: boolean }} options
 */
export async function draw(host, source, { dark = false } = {}) {
  const previous = busy;
  let release;
  busy = new Promise((resolve) => { release = resolve; });
  await previous;
  try {
    initialize(dark ? 'dark' : 'default');
    sequence += 1;
    // Never use the returned bindFunctions: it would attach diagram-declared callbacks.
    const { svg } = await mermaid.render(`wk-mermaid-${sequence}`, source);
    host.innerHTML = svg;
    const el = host.querySelector('svg');
    if (el) {
      el.setAttribute('role', 'img');
      el.setAttribute('aria-label', accessibleLabel(el));
    }
  } finally {
    release();
  }
}
