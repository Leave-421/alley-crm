import RequireApprovedStore from '@/auth/RequireApprovedStore.jsx';
import RequireRole from '@/auth/RequireRole.jsx';
import { ROLES } from '@/auth/roles.js';
import AppLayout from '@/layouts/AppLayout.jsx';
import { OWNER_MENU } from '@/layouts/menus.js';
import ReservationSettingsPage from '@/pages/owner/settings/ReservationSettingsPage.jsx';
import StoreInfoPage from '@/pages/owner/store/StoreInfoPage.jsx';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';
import CouponSettingsPage from '@/pages/owner/coupon/CouponSettingsPage.jsx';
import OwnerReservationsPage from '@/pages/owner/reservations/OwnerReservationsPage.jsx';

// 사장님 영역(/owner). 담당 B가 children에 화면 경로를 더하고, 자리표시를 실제 화면으로 바꾼다.
// A가 만드는 화면(알림함 O-10, 손님 흐름 O-07)은 A가 그 줄의 element만 바꾼다.
// 하단 탭이 가리키는 주소는 layouts/menus.js와 같게 둔다.
export const ownerRoutes = [
  {
    path: '/owner',
    element: <RequireRole allow={[ROLES.OWNER]} />,
    children: [
      {
        // 승인 전 · 거절 가게의 사장님은 가게 가입 상태(O-11)로 보낸다(B5, FR-AUTH-04)
        element: (
          <RequireApprovedStore>
            <AppLayout menu={OWNER_MENU} />
          </RequireApprovedStore>
        ),
        children: [
          { index: true, handle: { title: '예약 관리' }, element: <OwnerReservationsPage /> }, // O-01 B6
          { path: 'customers', element: <PlaceholderPage screenId="O-04" title="고객 목록" /> },
          { path: 'flow', element: <PlaceholderPage screenId="O-07" title="손님 흐름" /> },
          { path: 'settlement', element: <PlaceholderPage screenId="O-08" title="소개 · 수수료" /> },
          { path: 'coupons', handle: { title: '쿠폰 설정' }, element: <CouponSettingsPage /> }, // O-09 B15
          { path: 'settings', handle: { title: '가게 · 예약 설정' }, element: <ReservationSettingsPage /> }, // O-03 B10, 진입은 O-01 '예약 운영 설정'
          { path: 'store', handle: { title: '가게 정보' }, element: <StoreInfoPage /> }, // O-12 B24, 진입은 O-01 · O-03 '가게 정보'
          { path: 'notifications', handle: { title: '알림함' }, element: <PlaceholderPage screenId="O-10" title="알림함" /> },
        ],
      },
    ],
  },
];
