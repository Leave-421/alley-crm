import { createSearchParams, Navigate, Outlet, useLocation } from 'react-router';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import ForbiddenPage from '@/pages/public/ForbiddenPage.jsx';

/**
 * 역할 가드(FR-AUTH-01, 07-screens 「모든 화면 공통」).
 * - 확인 중: 대기 표시
 * - 로그인 안 함: /login?next=<원래 주소>
 * - 다른 역할: 403 화면
 * 화면에서 막는 것은 편의일 뿐이고, 권한은 서버가 응답마다 검사한다(security.md).
 */
export default function RequireRole({ allow, children }) {
  const { status, role } = useSession();
  const location = useLocation();

  if (status === SESSION_STATUS.LOADING) {
    return <p role="status">로그인 상태를 확인하고 있어요.</p>;
  }

  if (status !== SESSION_STATUS.AUTHENTICATED) {
    const next = location.pathname + location.search;
    return <Navigate to={{ pathname: '/login', search: `?${createSearchParams({ next })}` }} replace />;
  }

  if (!allow.includes(role)) {
    return <ForbiddenPage />;
  }

  return children ?? <Outlet />;
}
