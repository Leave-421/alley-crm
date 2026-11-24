import AppLayout from '@/layouts/AppLayout.jsx';
import { CUSTOMER_MENU } from '@/layouts/menus.js';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';

// 손님 영역(/). 담당 A가 children에 화면 경로를 더하고, 자리표시를 실제 화면으로 바꾼다.
// 비회원도 둘러볼 수 있어서 영역 전체에는 가드를 걸지 않는다.
// 회원만 여는 화면(마이 등)은 그 화면 이슈에서 <RequireRole allow={[ROLES.CUSTOMER]} />로 감싼다.
// 하단 탭이 가리키는 주소는 layouts/menus.js와 같게 둔다. 머리말 제목은 handle.title(없으면 LOCORI).
export const customerRoutes = [
  {
    path: '/',
    element: <AppLayout menu={CUSTOMER_MENU} />,
    children: [
      { index: true, element: <PlaceholderPage screenId="G-14" title="홈" /> },
      { path: 'reservations', handle: { title: '내 예약' }, element: <PlaceholderPage screenId="G-04" title="내 예약" /> },
      { path: 'my', handle: { title: '마이' }, element: <PlaceholderPage screenId="G-10" title="마이" /> },
      { path: 'notifications', handle: { title: '알림함' }, element: <PlaceholderPage screenId="G-11" title="알림함" /> },
    ],
  },
];
