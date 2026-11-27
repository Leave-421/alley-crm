import { api } from '@/api/client.js';

// 알림함 — CONTRACTS_API.md 「1」 알림(C14). 받는 쪽은 서버가 세션으로 정한다(회원 · 사장님 · 상인회 · 비회원 제한 세션).

/**
 * 내 알림 목록(최근 것부터, 공통 페이지) + 전체 안 읽은 수.
 * @returns {Promise<{ items: { id: string, type: string, payload: object, readAt: string | null, createdAt: string }[], page: number, size: number, totalElements: number, totalPages: number, unreadCount: number }>}
 */
export function getNotifications({ page = 0, size = 20 } = {}) {
  return api.get('/api/v1/notifications', { query: { page, size } });
}

/** 읽음 처리(이미 읽었으면 그대로). 응답 204 */
export function markNotificationRead(notificationId) {
  return api.post(`/api/v1/notifications/${encodeURIComponent(notificationId)}/read`);
}
