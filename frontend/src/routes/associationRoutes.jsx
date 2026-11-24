import RequireRole from '@/auth/RequireRole.jsx';
import { ROLES } from '@/auth/roles.js';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';

// 상인회 영역(/association). 담당 B가 children에 화면 경로를 더한다.
// A가 만드는 화면(방문 흐름 A-03)은 A가 여기 한 줄만 더한다.
// 레이아웃 · 하단 탭은 B2에서 가드 안쪽에 들어간다.
export const associationRoutes = [
  {
    path: '/association',
    element: <RequireRole allow={[ROLES.ASSOCIATION]} />,
    children: [
      { index: true, element: <PlaceholderPage screenId="A-01" title="대시보드" /> },
    ],
  },
];
