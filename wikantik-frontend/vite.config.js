import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

function buildVersionPlugin() {
  const version = Date.now().toString();
  return {
    name: 'build-version',
    config() {
      return { define: { __BUILD_VERSION__: JSON.stringify(version) } };
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
    environment: 'node',
  },
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
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
