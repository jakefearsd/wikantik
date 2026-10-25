import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { CHUNK_MODULES_FILE, MERMAID_FAMILY, NOT_UNDER } from './scripts/bundleFamilies.js'

function buildVersionPlugin() {
  const version = Date.now().toString();
  return {
    name: 'build-version',
    config() {
      return {
        define: {
          __BUILD_VERSION__: JSON.stringify(version),
          // Semantic app version, injected by Maven (WIKANTIK_VERSION=${project.version})
          // during the WAR build. Falls back to 'dev' for a plain `npm run build`.
          __APP_VERSION__: JSON.stringify(process.env.WIKANTIK_VERSION || 'dev'),
        },
      };
    },
    transformIndexHtml( html ) {
      return html.replace(
        '<meta charset="UTF-8" />',
        '<meta charset="UTF-8" />\n    <meta name="build-version" content="' + version + '" />'
      );
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'build-version.txt',
        source: version,
      });
    },
  };
}

// Records which modules each emitted chunk holds, for scripts/check-dist.js (`npm run build`'s post-build guard),
// which fails if dist/index.html modulepreloads a chunk with Mermaid-family code in it.
function chunkModulesPlugin() {
  return {
    name: 'chunk-modules',
    apply: 'build',
    generateBundle(_options, bundle) {
      const map = {};
      for (const chunk of Object.values(bundle)) {
        if (chunk.type === 'chunk') map[chunk.fileName] = chunk.moduleIds;
      }
      mkdirSync(dirname(CHUNK_MODULES_FILE), { recursive: true });
      writeFileSync(CHUNK_MODULES_FILE, JSON.stringify(map));
    },
  };
}

export default defineConfig({
  plugins: [react(), buildVersionPlugin(), chunkModulesPlugin()],
  test: {
    environment: 'happy-dom',
    setupFiles: ['./src/setupTests.js'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/**/*.test.{js,jsx}', 'src/test/**', 'src/bench/**', 'src/setupTests.js', 'src/main.jsx'],
      reporter: ['text-summary', 'json-summary', 'html'],
      // Coverage ratchet (enforced by `npm run test:coverage`, which CI runs):
      // each floor is the measured level rounded DOWN to a whole percent at the
      // time it was last raised. Floors only ever go UP (cap 95). Raise them
      // when coverage improves; never lower one to make a red run pass.
      thresholds: {
        lines: 87,
        statements: 85,
        functions: 85,
        branches: 76,
      },
    },
  },
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // The only chunks above the default 500 kB are `codemirror`, `cytoscape` and
    // `elk` (elkjs, Mermaid's optional ELK layout engine, 1.46 MB) — monolithic
    // third-party libraries that can't be meaningfully split and are already
    // isolated into their own lazy chunks (editor / graph routes / ELK-layout
    // diagrams only, never eagerly). Raise the budget to match that reality. The
    // old 700 kB budget still holds for every other chunk: scripts/check-dist.js
    // (run by `npm run build`) fails the build on any other chunk above it, so
    // unexpected bloat is still caught.
    chunkSizeWarningLimit: 1500,
    // Split heavy vendor libraries into separate, long-term-cacheable chunks so
    // no single chunk exceeds the ~500 kB budget and app-code edits don't bust
    // the vendor cache. `codeSplitting.groups` is Rolldown's chunk-grouping
    // mechanism (replaces Rollup's deprecated manualChunks; `advancedChunks` is
    // the older alias). CodeMirror and Cytoscape MUST get their own groups (not
    // the catch-all `vendor`) so they stay lazy — loaded only with the editor /
    // graph routes, never eagerly with the entry chunk. `test` matches the
    // module's path and is anchored with a trailing slash so the `react` group
    // matches node_modules/react/ but NOT react-markdown/.
    //
    // A group also captures its modules' dependencies (includeDependenciesRecursively,
    // default true), highest priority first. So `react` must outrank `codemirror`:
    // otherwise @uiw/react-codemirror drags React into the codemirror chunk, the entry
    // imports React from there, and the whole 630 kB editor chunk loads on every page
    // (it did until 2026-10). `lowlight` must not capture its shared dependencies
    // either: the eager `vendor` chunk would import them from — and so eagerly load —
    // the lowlight chunk. Check `dist/index.html`: its modulepreloads must not list
    // codemirror, cytoscape, lowlight or anything of Mermaid's (`grep -c mermaid dist/index.html` → 0).
    //
    // Mermaid deliberately has NO group. Its dist is already split into a core plus one lazy chunk per diagram
    // type (flowchart, sequence, ...), loaded through dynamic imports when a diagram of that type is drawn; a
    // group would weld them all into one ~4.3 MB chunk (1.2 MB gzip) that every diagram page downloads. Instead
    // the whole Mermaid-only package family (MERMAID_FAMILY in scripts/bundleFamilies.js, nested node_modules
    // included) is excluded from the catch-all `vendor` — otherwise e.g. dompurify/marked/lodash-es land in the
    // eager vendor chunk, and cytoscape-fcose there would make vendor import (and so eagerly load) the cytoscape
    // chunk. Left ungrouped, Rolldown emits them as ordinary lazy chunks behind utils/fences/registry.js's dynamic
    // import. The `cytoscape` group skips cytoscape-fcose's private cose-base/layout-base copies for the same
    // reason: they would grow the graph route's chunk with code only Mermaid uses. When upgrading Mermaid,
    // re-derive the family from the lockfile diff. `npm run build` enforces the result: scripts/check-dist.js
    // fails if dist/index.html modulepreloads any chunk holding a MERMAID_FAMILY module.
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'codemirror', test: /[\\/]node_modules[\\/](@codemirror[\\/](?!language-data|legacy-modes|lang-(?!markdown))|@uiw[\\/]react-codemirror|@lezer[\\/](common|highlight|lr|markdown)[\\/]|crelt|style-mod|w3c-keyname)/, priority: 30 },
            { name: 'cytoscape', test: new RegExp(NOT_UNDER('cytoscape-fcose') + String.raw`.*[\\/]node_modules[\\/](cytoscape|cytoscape-cose-bilkent|cose-base|layout-base|react-cytoscapejs)[\\/]`), priority: 30 },
            { name: 'lowlight', test: /[\\/]node_modules[\\/](lowlight|highlight\.js)[\\/]/, priority: 30, includeDependenciesRecursively: false },
            { name: 'katex', test: /[\\/]node_modules[\\/](katex|rehype-katex)[\\/]/, priority: 20, includeDependenciesRecursively: false },
            { name: 'react', test: /[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/, priority: 40 },
            // Per-language grammars (@codemirror/language-data's dynamic imports, legacy-modes, lezer grammars) are
            // excluded so Rolldown emits them as separate lazy chunks rather than folding them into this eager catch-all.
            // Mermaid's family is excluded too (see above).
            { name: 'vendor', test: new RegExp(NOT_UNDER(MERMAID_FAMILY) + String.raw`.*[\\/]node_modules[\\/](?!@codemirror[\\/](language-data|legacy-modes|lang-)|@lezer[\\/])`), priority: 1 },
          ],
        },
      },
    },
  },
  experimental: {
    renderBuiltUrl( filename, { hostType } ) {
      if ( hostType === 'js' ) {
        return { runtime: `new URL((window.__WIKANTIK_BASE__||"")+"/"+${JSON.stringify( filename )},location.origin).href` };
      }
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/attach': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
