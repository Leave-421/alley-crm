import { useEffect, useRef, useState } from 'react';

// 사장님 실시간 알림(C15, SSE) — CONTRACTS_API.md 「GET /api/v1/owner/notifications/stream」.
// 세션 쿠키로 인증하고(EventSource가 쿠키를 같이 보낸다) GET이라 CSRF 토큰이 필요 없다.
// 서버가 30분 뒤 닫으면 브라우저 EventSource가 다시 연결한다. 연결이 없을 때 생긴 알림은 다시 오지 않는다.

export const OWNER_NOTIFICATION_STREAM_PATH = '/api/v1/owner/notifications/stream';

/** O-01이 받으면 예약 목록을 다시 불러오는 알림 종류(계약 C15, FR-NTF-01 #1) */
export const RESERVATION_RELOAD_TYPES = Object.freeze(['reservation_requested', 'change_requested', 'reservation_canceled', 'delay_reported']);

/** 연결 상태: connecting 연결 중 · open 받는 중 · reconnecting 다시 연결하는 중 · closed 끊김(서버가 거절해 다시 연결하지 않음) */
export const STREAM_STATUS = Object.freeze({ CONNECTING: 'connecting', OPEN: 'open', RECONNECTING: 'reconnecting', CLOSED: 'closed' });

/**
 * 사장님 알림 스트림을 구독한다. 알림(`event: notification`)이 오면 onNotification(Notification)을 부른다.
 * 화면을 떠나면 연결을 닫는다. 지금 연결 상태를 돌려준다.
 */
export function useOwnerNotificationStream(onNotification) {
  const [status, setStatus] = useState(STREAM_STATUS.CONNECTING);
  // 화면이 다시 그려져도 연결을 다시 열지 않게 최신 콜백만 바꿔 끼운다
  const handlerRef = useRef(onNotification);
  useEffect(() => {
    handlerRef.current = onNotification;
  }, [onNotification]);

  useEffect(() => {
    if (typeof EventSource === 'undefined') return undefined;
    const source = new EventSource(OWNER_NOTIFICATION_STREAM_PATH);
    source.onopen = () => setStatus(STREAM_STATUS.OPEN);
    // 끊기면 EventSource가 스스로 다시 연결한다. 서버가 401 · 403처럼 거절하면 닫히고 다시 연결하지 않는다
    source.onerror = () => setStatus(source.readyState === EventSource.CLOSED ? STREAM_STATUS.CLOSED : STREAM_STATUS.RECONNECTING);
    source.addEventListener('notification', (event) => {
      try {
        handlerRef.current?.(JSON.parse(event.data));
      } catch {
        // 모양이 다른 메시지는 무시한다
      }
    });
    return () => source.close();
  }, []);

  return status;
}
