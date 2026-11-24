import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발 서버(:5173)가 /api 요청을 Spring(:8080)으로 넘긴다.
// 브라우저 입장에서는 출처가 localhost:5173 하나라서 세션 쿠키 · XSRF-TOKEN 쿠키가 그대로 붙는다.
// changeOrigin을 켜지 않는다: 백엔드가 쓰기 요청의 Origin을 APP_ORIGIN(http://localhost:5173)과 비교한다.
const API_TARGET = 'http://localhost:8080';
const apiProxy = { '/api': { target: API_TARGET, changeOrigin: false } };

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true, // 포트가 바뀌면 APP_ORIGIN과 어긋나므로 다른 포트로 넘어가지 않는다
    proxy: apiProxy,
  },
  preview: {
    port: 5173,
    strictPort: true,
    proxy: apiProxy,
  },
});
