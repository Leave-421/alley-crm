import { useEffect, useState } from 'react';
import { Navigate, Outlet } from 'react-router';
import { getMyStoreJoin } from '@/api/stores.js';
import Button from '@/components/Button/Button.jsx';

const APPROVED = ['approved', 'withdraw_requested'];

/**
 * 승인된 가게의 사장님만 사장님 영역을 연다(FR-AUTH-04). 승인 전 · 거절이면 가게 가입 상태(O-11)로 보낸다
 * (07-screens G-08 '승인 전 → O-11', O-11 '승인 전에는 다른 사장님 메뉴가 잠김').
 * RequireRole(owner) 안에서 쓴다. children이 있으면 그것을, 없으면 Outlet을 그린다. 화면에서 막는 것은 편의이고, 서버가 사장님 API마다 403으로 다시 막는다(C5).
 */
export default function RequireApprovedStore({ children }) {
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', joinStatus: null });
  const state = result.key === reloadCount ? result : { status: 'loading', joinStatus: null };

  useEffect(() => {
    let active = true;
    getMyStoreJoin()
      .then((join) => {
        if (active) setResult({ key: reloadCount, status: 'ready', joinStatus: join.joinStatus });
      })
      .catch(() => {
        if (active) setResult({ key: reloadCount, status: 'error', joinStatus: null });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  if (state.status === 'loading') return <p role="status">가게 가입 상태를 확인하고 있어요.</p>;
  if (state.status === 'error') {
    return (
      <section role="alert" style={{ padding: 'var(--space-6) var(--layout-gutter)' }}>
        <p>가게 가입 상태를 확인하지 못했어요.</p>
        <Button variant="secondary" onClick={() => setReloadCount((count) => count + 1)}>
          다시 확인하기
        </Button>
      </section>
    );
  }
  if (!APPROVED.includes(state.joinStatus)) return <Navigate to="/store-join" replace />;
  return children ?? <Outlet />;
}
