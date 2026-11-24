import { Outlet } from 'react-router';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';

// 손님 영역(/). 담당 A가 children에 화면 경로를 더한다.
// 비회원도 둘러볼 수 있어서 영역 전체에는 가드를 걸지 않는다.
// 회원만 여는 화면(마이 등)은 그 화면 이슈에서 <RequireRole allow={[ROLES.CUSTOMER]} />로 감싼다.
// 레이아웃 · 하단 탭은 B2에서 element 자리에 들어간다.
export const customerRoutes = [
  {
    path: '/',
    element: <Outlet />,
    children: [
      { index: true, element: <PlaceholderPage screenId="G-14" title="홈" /> },
    ],
  },
];
