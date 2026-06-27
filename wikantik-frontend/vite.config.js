import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

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

export default defineConfig({
  plugins: [react(), buildVersionPlugin()],
  test: {
    environment: 'happy-dom',
    setupFiles: ['./src/setupTests.js'],
  },
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // The only chunks above the default 500 kB are `codemirror` and `cytoscape`
    // — monolithic third-party libraries that can't be meaningfully split and
    // are already isolated into their own lazy chunks (loaded only on the
    // editor / graph routes, never eagerly). Raise the budget to match that
    // reality so the warning still fires for genuinely-unexpected bloat.
    chunkSizeWarningLimit: 700,
    // Split heavy vendor libraries into separate, long-term-cacheable chunks so
    // no single chunk exceeds the ~500 kB budget and app-code edits don't bust
    // the vendor cache. `codeSplitting.groups` is Rolldown's chunk-grouping
    // mechanism (replaces Rollup's deprecated manualChunks; `advancedChunks` is
    // the older alias). CodeMirror and Cytoscape MUST get their own groups (not
    // the catch-all `vendor`) so they stay lazy — loaded only with the editor /
    // graph routes, never eagerly with the entry chunk. `test` matches the
    // module's path and is anchored with a trailing slash so the `react` group
    // matches node_modules/react/ but NOT react-markdown/.
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'codemirror', test: /[\\/]node_modules[\\/](@codemirror|@uiw[\\/]react-codemirror|@lezer|crelt|style-mod|w3c-keyname)[\\/]/, priority: 30 },
            { name: 'cytoscape', test: /[\\/]node_modules[\\/](cytoscape|cytoscape-cose-bilkent|cose-base|layout-base|react-cytoscapejs)[\\/]/, priority: 30 },
            { name: 'katex', test: /[\\/]node_modules[\\/]katex[\\/]/, priority: 20 },
            { name: 'react', test: /[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/, priority: 10 },
            { name: 'vendor', test: /[\\/]node_modules[\\/]/, priority: 1 },
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
