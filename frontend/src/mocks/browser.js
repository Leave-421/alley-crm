import { setupWorker } from 'msw/browser';
import { handlers } from './handlers/index.js';

const worker = setupWorker(...handlers);

/**
 * 목 서버를 켠다(`npm run dev:mock`일 때만 main.jsx가 부른다).
 * 목이 없는 /api 요청은 막지 않고 Vite 프록시로 실제 백엔드에 보낸다(콘솔에 경고만 남김).
 */
export function startMockServer() {
  return worker.start({
    onUnhandledRequest(request, print) {
      if (new URL(request.url).pathname.startsWith('/api/')) print.warning();
    },
  });
}
