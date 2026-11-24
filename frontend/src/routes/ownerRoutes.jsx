import RequireRole from '@/auth/RequireRole.jsx';
import { ROLES } from '@/auth/roles.js';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';

// 사장님 영역(/owner). 담당 B가 children에 화면 경로를 더한다.
// A가 만드는 화면(알림함 O-10, 손님 흐름 O-07)은 A가 여기 한 줄만 더한다.
// 레이아웃 · 하단 탭은 B2에서 가드 안쪽에 들어간다.
export const ownerRoutes = [
  {
    path: '/owner',
    element: <RequireRole allow={[ROLES.OWNER]} />,
    children: [
      { index: true, element: <PlaceholderPage screenId="O-01" title="예약 관리" /> },
    ],
  },
];
