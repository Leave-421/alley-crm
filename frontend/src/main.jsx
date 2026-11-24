import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from '@/app/App.jsx';
import { ensureCsrfToken } from '@/api/client.js';

// 목 서버는 `npm run dev:mock`(Vite mode 'mock')일 때만 켠다. 다른 모드의 빌드에는 목 코드가 들어가지 않는다.
async function enableMocking() {
  if (import.meta.env.MODE !== 'mock') return;
  const { startMockServer } = await import('@/mocks/browser.js');
  await startMockServer();
}

function render() {
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

enableMocking()
  .catch((error) => console.error('목 서버를 켜지 못했어요.', error))
  .finally(() => {
    render();
    // 계약: 앱 시작 때 CSRF 토큰을 한 번 받는다(CONTRACTS_API.md GET /api/v1/csrf). 실패해도 화면은 띄운다.
    ensureCsrfToken().catch((error) => console.warn('CSRF 토큰을 받지 못했어요.', error));
  });
