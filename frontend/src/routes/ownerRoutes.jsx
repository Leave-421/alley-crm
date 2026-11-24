import RequireRole from '@/auth/RequireRole.jsx';
import { ROLES } from '@/auth/roles.js';
import AppLayout from '@/layouts/AppLayout.jsx';
import { OWNER_MENU } from '@/layouts/menus.js';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';

// 사장님 영역(/owner). 담당 B가 children에 화면 경로를 더하고, 자리표시를 실제 화면으로 바꾼다.
// A가 만드는 화면(알림함 O-10, 손님 흐름 O-07)은 A가 그 줄의 element만 바꾼다.
// 하단 탭이 가리키는 주소는 layouts/menus.js와 같게 둔다.
export const ownerRoutes = [
  {
    path: '/owner',
    element: <RequireRole allow={[ROLES.OWNER]} />,
    children: [
      {
        element: <AppLayout menu={OWNER_MENU} />,
        children: [
          { index: true, element: <PlaceholderPage screenId="O-01" title="예약 관리" /> },
          { path: 'customers', element: <PlaceholderPage screenId="O-04" title="고객 목록" /> },
          { path: 'flow', element: <PlaceholderPage screenId="O-07" title="손님 흐름" /> },
          { path: 'settlement', element: <PlaceholderPage screenId="O-08" title="소개 · 수수료" /> },
          { path: 'coupons', element: <PlaceholderPage screenId="O-09" title="쿠폰 설정" /> },
          { path: 'notifications', handle: { title: '알림함' }, element: <PlaceholderPage screenId="O-10" title="알림함" /> },
        ],
      },
    ],
  },
];
