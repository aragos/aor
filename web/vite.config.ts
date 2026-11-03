import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import {fileURLToPath} from 'node:url';

export default defineConfig({
    root: '.',
    plugins: [react()],
    resolve: {alias: {'aor-ai': fileURLToPath(new URL('../ai/build/dist/js/productionLibrary/aor-ai.mjs', import.meta.url))}},
    build: {
        outDir: 'dist',
        emptyOutDir: true,
    },
    server: {port: 5173, strictPort: true, proxy: {'/api': process.env.AOR_API_TARGET ?? 'http://127.0.0.1:8080'}},
});
