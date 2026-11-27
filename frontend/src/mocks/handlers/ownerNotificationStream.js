import { sse } from 'msw/sse';
import { getMockAccount } from '../session.js';
import { addMockIncomingReservation } from './reservation.js';

// CONTRACTS_API.md 「1」의 사장님 실시간 알림(C15, SSE) 목.
// 목에서 새 예약을 받아 보도록, 사장님이 연결하고 몇 초 뒤 오늘 날짜에 새 신청 예약을 하나 넣고
// `reservation_requested` 알림을 보낸다(탭마다 한 번). 실제 서버는 손님이 예약을 신청하면 보낸다.
// 사장님이 아니면 보내지 않는다(실제 서버는 401 · 403 JSON으로 거절한다).

const FIRST_EVENT_DELAY_MS = 4000;
let sentOnce = false;
let nextNotificationId = 9000;

const todayKey = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());

export const ownerNotificationStreamHandlers = [
  sse('/api/v1/owner/notifications/stream', ({ client }) => {
    if (getMockAccount()?.role !== 'owner') {
      client.close();
      return;
    }
    if (sentOnce) return;
    sentOnce = true;
    setTimeout(() => {
      const reservationId = addMockIncomingReservation(todayKey());
      const notification = {
        id: String(nextNotificationId++),
        type: 'reservation_requested',
        payload: { reservationId },
        readAt: null,
        createdAt: new Date().toISOString(),
      };
      client.send({ event: 'notification', id: notification.id, data: JSON.stringify(notification) });
    }, FIRST_EVENT_DELAY_MS);
  }),
];
