import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { getNotifications } from '@/api/notifications.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';

/**
 * 종 아이콘 배지용 안 읽은 알림 수(C14 `GET /api/v1/notifications`의 unreadCount, #292).
 * 로그인했고 알림함이 있는 영역(enabled)에서만 묻는다. 화면을 옮길 때마다 다시 물어서,
 * 알림함에서 읽고 돌아오면 숫자가 맞춰진다. 실패하면 배지를 보이지 않는다(null).
 */
export function useUnreadCount(enabled) {
  const { status } = useSession();
  const { pathname } = useLocation();
  const active = enabled && status === SESSION_STATUS.AUTHENTICATED;
  const [result, setResult] = useState({ key: null, count: null });

  useEffect(() => {
    if (!active) return undefined;
    let alive = true;
    getNotifications({ page: 0, size: 1 })
      .then((page) => {
        if (alive) setResult({ key: pathname, count: Number.isInteger(page.unreadCount) ? page.unreadCount : null });
      })
      .catch(() => {
        if (alive) setResult({ key: pathname, count: null });
      });
    return () => {
      alive = false;
    };
  }, [active, pathname]);

  // 새 화면의 응답이 오기 전에는 앞 화면의 숫자를 그대로 보여 준다(깜빡이지 않게)
  return active ? result.count : null;
}
