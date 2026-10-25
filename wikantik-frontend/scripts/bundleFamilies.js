// Package families shared by vite.config.js (chunking) and scripts/check-dist.js (the post-build guard).

// Every package only Mermaid (12.1.0) pulls in — exactly what its npm install added to the lockfile. Re-derive it
// from the lockfile diff when upgrading Mermaid. A regex alternation over package paths.
export const MERMAID_FAMILY = String.raw`mermaid|@mermaid-js[\\/][^\\/]+|d3[^\\/]*|dagre-d3-es|elkjs|chevrotain|@chevrotain[\\/][^\\/]+|khroma|roughjs|stylis|@iconify[\\/][^\\/]+|@antfu[\\/]install-pkg|@braintree[\\/]sanitize-url|@upsetjs[\\/]venn\.js|cytoscape-fcose|dayjs|delaunator|dompurify|es-toolkit|hachure-fill|internmap|lodash-es|marked|package-manager-detector|path-data-parser|points-on-(?:curve|path)|robust-predicates|ts-dedent|uuid|rw|iconv-lite|safer-buffer|import-meta-resolve`;

/** Regex source matching a module path that lies under one of `family`'s packages (any node_modules depth). */
export const UNDER = (family) => String.raw`[\\/]node_modules[\\/](?:${family})[\\/]`;

/** Regex-source prefix that matches only module paths NOT under one of `family`'s packages. */
export const NOT_UNDER = (family) => String.raw`^(?!.*${UNDER(family)})`;

/** Where the build records each emitted chunk's module ids for the guard (outside dist, so it never ships). */
export const CHUNK_MODULES_FILE = 'node_modules/.cache/wikantik/chunk-modules.json';
