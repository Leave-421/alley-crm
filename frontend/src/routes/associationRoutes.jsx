import RequireRole from '@/auth/RequireRole.jsx';
import { ROLES } from '@/auth/roles.js';
import AppLayout from '@/layouts/AppLayout.jsx';
import { ASSOCIATION_MENU } from '@/layouts/menus.js';
import { Link } from 'react-router';
import PlaceholderPage from '@/pages/PlaceholderPage.jsx';
import AssociationCouponsPage from '@/pages/association/coupons/AssociationCouponsPage.jsx';
import FundSettlementPage from '@/pages/association/settlement/FundSettlementPage.jsx';
import VisitReportsPage from '@/pages/association/reports/VisitReportsPage.jsx';
import StoreManagementPage from '@/pages/association/stores/StoreManagementPage.jsx';

// 상인회 영역(/association). 담당 B가 children에 화면 경로를 더하고, 자리표시를 실제 화면으로 바꾼다.
// A가 만드는 화면(방문 흐름 A-03)은 A가 그 줄의 element만 바꾼다.
// 하단 탭이 가리키는 주소는 layouts/menus.js와 같게 둔다.
export const associationRoutes = [
  {
    path: '/association',
    element: <RequireRole allow={[ROLES.ASSOCIATION]} />,
    children: [
      {
        element: <AppLayout menu={ASSOCIATION_MENU} />,
        children: [
          { index: true, element: <PlaceholderPage screenId="A-01" title="대시보드" /> },
          {
            path: 'stores',
            element: (
              <PlaceholderPage screenId="A-02" title="가게 성과 비교">
                {/* A-02 '가게 관리' 진입(07-screens A-02 이동). A-02 화면(B17)을 만들 때 그 화면으로 옮긴다 */}
                <Link to="/association/stores/manage">가게 관리 ›</Link>
              </PlaceholderPage>
            ),
          },
          { path: 'stores/manage', handle: { title: '가게 관리' }, element: <StoreManagementPage /> }, // A-05 B19
          { path: 'flow', element: <PlaceholderPage screenId="A-03" title="방문 흐름" /> },
          { path: 'settlement', handle: { title: '공동 기금 · 정산' }, element: <FundSettlementPage /> }, // A-04 B18
          // A-07 공동 기금 적립 내역(A28). A-04 '공동 기금 적립 내역'의 진입 주소. A가 element만 바꾼다
          // A-10 상인회 쿠폰 관리(B27, 번호 임시). 기금이 부담해서 '정산' 탭 안, A-04에서 진입(배치는 B가 정함, 11-25 (5))
          { path: 'settlement/coupons', handle: { title: '상인회 쿠폰' }, element: <AssociationCouponsPage /> },
          // A-11 신고 처리(B28, 번호 임시). 처리 안 된 신고가 있으면 정산을 확정할 수 없어 '정산' 탭 안, A-04에서 진입(배치는 B가 정함)
          { path: 'settlement/reports', handle: { title: '신고 처리' }, element: <VisitReportsPage /> },
          { path: 'settlement/fund', handle: { title: '공동 기금 적립 내역' }, element: <PlaceholderPage screenId="A-07" title="공동 기금 적립 내역" /> },
        ],
      },
    ],
  },
];
