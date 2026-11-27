import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import { getNotifications, markNotificationRead } from '@/api/notifications.js';
import { useSession } from '@/auth/session.js';
import { BADGE_TONES, describeNotification, formatNotificationTime } from './notificationText.js';
import styles from './NotificationsPage.module.css';

const PAGE_SIZE = 20;

/**
 * 알림함 — 손님(G-11) · 사장님(O-10) 공용. 받는 쪽은 서버가 세션으로 정하고, 문구는 역할에 따라 notificationText가 정한다.
 * guest: 비회원 예약 조회(G-13)에서 연 알림함(그 전화번호 비회원 예약들의 알림).
 * 목업처럼 맨 위에 '읽지 않은 알림 N개 · 모두 읽음', 알림마다 배지 · 안 읽음 점 · 문구 · 시각. 누르면 읽음 처리하고 관련 화면으로 간다.
 */
export default function NotificationsPage({ guest = false }) {
  const { role } = useSession();
  const [state, setState] = useState({ status: 'loading', items: [], page: -1, totalPages: 0, unreadCount: 0, error: null });

  useEffect(() => {
    let active = true;
    getNotifications({ page: 0, size: PAGE_SIZE })
      .then((data) => active && setState({ status: 'ready', items: data.items, page: data.page, totalPages: data.totalPages, unreadCount: data.unreadCount, error: null }))
      .catch((error) => active && setState((prev) => ({ ...prev, status: 'error', error })));
    return () => {
      active = false;
    };
  }, []);

  function markReadLocally(ids) {
    const nowIso = new Date().toISOString();
    setState((prev) => {
      const changed = prev.items.filter((item) => ids.includes(item.id) && !item.readAt).length;
      return {
        ...prev,
        items: prev.items.map((item) => (ids.includes(item.id) && !item.readAt ? { ...item, readAt: nowIso } : item)),
        unreadCount: Math.max(0, prev.unreadCount - changed),
      };
    });
  }

  function open(notification) {
    if (notification.readAt) return;
    markReadLocally([notification.id]);
    // 읽음 처리 실패는 다음에 목록을 다시 열면 서버 상태로 돌아오므로 이동을 막지 않는다
    markNotificationRead(notification.id).catch((error) => console.warn('알림 읽음 처리 실패', error));
  }

  async function readAll() {
    const unread = state.items.filter((item) => !item.readAt).map((item) => item.id);
    if (unread.length === 0) return;
    const results = await Promise.allSettled(unread.map((id) => markNotificationRead(id)));
    markReadLocally(unread.filter((_, i) => results[i].status === 'fulfilled'));
  }

  async function loadMore() {
    try {
      const data = await getNotifications({ page: state.page + 1, size: PAGE_SIZE });
      setState((prev) => ({ ...prev, items: [...prev.items, ...data.items], page: data.page, totalPages: data.totalPages, unreadCount: data.unreadCount }));
    } catch (error) {
      setState((prev) => ({ ...prev, error }));
    }
  }

  if (state.status === 'error' && state.error instanceof ApiError && state.error.status === 401) {
    return (
      <main className={styles.page}>
        <section className={styles.card}>
          <p className={styles.title}>로그인하면 알림을 볼 수 있어요.</p>
          <p className={styles.cap}>
            <Link to="/login">로그인</Link> · 비회원 예약은 <Link to="/guest-reservation">비회원 예약 조회</Link>에서 확인해요.
          </p>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.head}>
        <div className={styles.cap}>
          읽지 않은 알림 <b className={styles.unread}>{state.unreadCount}</b>개
        </div>
        <button type="button" className={styles.readAll} onClick={readAll} disabled={state.unreadCount === 0}>
          모두 읽음
        </button>
      </div>

      {state.status === 'loading' && <p className={styles.cap}>알림을 불러오는 중이에요…</p>}
      {state.status === 'error' && <p className={styles.cap}>알림을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.</p>}
      {state.status === 'ready' && state.items.length === 0 && <p className={styles.cap}>아직 알림이 없어요.</p>}

      <ul className={styles.list}>
        {state.items.map((notification) => {
          const described = describeNotification(notification, role);
          // 비회원 제한 세션(G-13)은 회원 화면(내 예약 · 마이)을 못 열어서 관련 화면 대신 비회원 예약 조회로 돌아간다
          const text = guest && described.to ? { ...described, to: '/guest-reservation' } : described;
          const badgeClass = [styles.badge, text.tone === BADGE_TONES.COUPON && styles.badgeCoupon, text.tone === BADGE_TONES.INFO && styles.badgeInfo].filter(Boolean).join(' ');
          const body = (
            <>
              <div className={styles.badgeRow}>
                <span className={badgeClass}>{text.badge}</span>
                {!notification.readAt && <span className={styles.dot} aria-label="읽지 않음" />}
              </div>
              <div className={styles.title}>{text.title}</div>
              {text.caption && <div className={styles.cap}>{text.caption}</div>}
              <div className={`${styles.cap} ${styles.time}`}>{formatNotificationTime(notification.createdAt)}</div>
            </>
          );
          return (
            <li key={notification.id}>
              {text.to ? (
                <Link to={text.to} className={`${styles.card} ${styles.item}`} onClick={() => open(notification)}>
                  {body}
                </Link>
              ) : (
                <button type="button" className={`${styles.card} ${styles.item}`} onClick={() => open(notification)}>
                  {body}
                </button>
              )}
            </li>
          );
        })}
      </ul>

      {state.page + 1 < state.totalPages && (
        <button type="button" className={styles.more} onClick={loadMore}>
          더 보기
        </button>
      )}
    </main>
  );
}
