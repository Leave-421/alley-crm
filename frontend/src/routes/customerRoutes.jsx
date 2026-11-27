import { Link } from 'react-router';
import AppLayout from '@/layouts/AppLayout.jsx';
import { CUSTOMER_MENU } from '@/layouts/menus.js';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';
import AlleyScreen from '@/pages/customer/alley/AlleyScreen.jsx';
import AlleyPickPage from '@/pages/customer/alleys/AlleyPickPage.jsx';
import HomePage from '@/pages/customer/home/HomePage.jsx';
import PolicyPage from '@/pages/customer/policy/PolicyPage.jsx';
import ReservePage from '@/pages/customer/reserve/ReservePage.jsx';

// 손님 영역(/). 담당 A가 children에 화면 경로를 더하고, 자리표시를 실제 화면으로 바꾼다.
// 비회원도 둘러볼 수 있어서 영역 전체에는 가드를 걸지 않는다.
// 회원만 여는 화면(마이 등)은 그 화면 이슈에서 <RequireRole allow={[ROLES.CUSTOMER]} />로 감싼다.
// 하단 탭이 가리키는 주소는 layouts/menus.js와 같게 둔다. 머리말 제목은 handle.title(없으면 LOCORI).
export const customerRoutes = [
  {
    path: '/',
    element: <AppLayout menu={CUSTOMER_MENU} />,
    children: [
      { index: true, element: <HomePage /> }, // G-14 A24
      { path: 'alleys', handle: { title: '골목 고르기' }, element: <AlleyPickPage /> }, // G-15 A25
      { path: 'alleys/:alley/reserve', handle: { title: '예약하기' }, element: <ReservePage /> }, // G-02 A8 (가게 시트 '예약하기' → ?store=가게ID)
      // 다음 코스 예약(G-03)은 G-02 완료 화면의 '다음 코스로 예약하기'가 가리키는 자리표시. 실제 화면은 그 화면 이슈에서 만든다
      { path: 'reservations/:reservationId/next', handle: { title: '다음 코스 예약' }, element: <PlaceholderPage screenId="G-03" title="다음 코스 예약" /> },
      { path: 'reservations', handle: { title: '내 예약' }, element: <PlaceholderPage screenId="G-04" title="내 예약" /> },
      // 마이(G-10)는 아직 자리표시. 정책 · 개인정보(G-12)로 가는 진입만 둔다
      {
        path: 'my',
        handle: { title: '마이' },
        element: (
          <PlaceholderPage screenId="G-10" title="마이">
            <Link to="/policy">정책 · 개인정보</Link>
          </PlaceholderPage>
        ),
      },
      { path: 'policy', handle: { title: '정책 · 개인정보' }, element: <PolicyPage /> }, // G-12 A16
      { path: 'notifications', handle: { title: '알림함' }, element: <PlaceholderPage screenId="G-11" title="알림함" /> },
      // 로그인 화면(G-08)의 '비회원 예약 조회' 진입 주소. 화면은 A13에서 만든다
      { path: 'guest-reservation', handle: { title: '비회원 예약 조회' }, element: <PlaceholderPage screenId="G-13" title="비회원 예약 조회" /> },
    ],
  },
  // 골목(G-01, A1 · A4)은 확정 목업처럼 화면 전체를 3D 골목으로 쓰고 자체 머리말을 둔다(공통 머리말 · 하단 탭 없음)
  { path: '/alleys/:alley', element: <AlleyScreen /> },
];
