import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import {fileURLToPath} from 'node:url';

export default defineConfig({
    root: '.',
    plugins: [react(), {
        name: 'public-homepage',
        configureServer(server) {
            server.middlewares.use((request, _response, next) => {
                if (request.url?.split('?')[0] === '/') request.url = '/home.html';
                next();
            });
        },
    }],
    resolve: {alias: {'aor-ai': fileURLToPath(new URL('../ai/build/dist/js/productionLibrary/aor-ai.mjs', import.meta.url))}},
    build: {
        outDir: 'dist',
        emptyOutDir: true,
        rollupOptions: {input: {
            app: fileURLToPath(new URL('./index.html', import.meta.url)),
            home: fileURLToPath(new URL('./home.html', import.meta.url)),
        }},
    },
    server: {port: 5173, strictPort: true, proxy: {'/api': process.env.AOR_API_TARGET ?? 'http://127.0.0.1:8080'}},
});
