import { Link, Outlet, useMatches } from 'react-router';
import BottomTabs from '@/layouts/BottomTabs.jsx';
import TabIcon from '@/layouts/TabIcon.jsx';
import { useUnreadCount } from '@/layouts/useUnreadCount.js';
import styles from './AppLayout.module.css';

/**
 * 역할 영역 공통 레이아웃: 머리말(제목 · 역할 표시 · 알림) + 화면 + 하단 탭. 확정 목업의 머리말 · 하단 탭을 따른다.
 * menu는 layouts/menus.js의 CUSTOMER_MENU · OWNER_MENU · ASSOCIATION_MENU 중 하나.
 * 제목은 라우트의 handle.title(가장 안쪽 것), 없으면 menu.defaultTitle.
 * 알림 아이콘에는 안 읽은 알림 수 배지(#292, C14 unreadCount)를 붙인다.
 * 오른쪽 끝의 '프로필 · 역할 전환' 아이콘은 시연 도구(/demo, B20)로 간다(확정 목업과 같음).
 */
export default function AppLayout({ menu }) {
  const title = useRouteTitle() ?? menu.defaultTitle;
  const unread = useUnreadCount(Boolean(menu.notificationsTo));
  const hasUnread = unread !== null && unread > 0;
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={styles.title}>
          {title}
          {menu.areaChip && <span className={styles.chip}>{menu.areaChip}</span>}
        </div>
        <div className={styles.headerActions}>
          {menu.notificationsTo && (
            <Link
              to={menu.notificationsTo}
              className={styles.iconButton}
              aria-label={hasUnread ? `알림함, 안 읽은 알림 ${unread}개` : '알림함'}
            >
              <TabIcon name="bell" size={22} />
              {/* 안 읽은 알림 수(#292). 숫자는 화면용, 개수는 aria-label로도 읽어 준다 */}
              {hasUnread && (
                <span className={styles.badge} aria-hidden="true">
                  {unread > 99 ? '99+' : unread}
                </span>
              )}
            </Link>
          )}
          <Link to="/demo" className={styles.iconButton} aria-label="프로필 · 역할 전환(시연 도구)">
            <TabIcon name="person" size={22} />
          </Link>
        </div>
      </header>
      <div className={styles.content}>
        <Outlet />
      </div>
      <BottomTabs tabs={menu.tabs} />
    </div>
  );
}

function useRouteTitle() {
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const title = matches[i].handle?.title;
    if (title) return title;
  }
  return null;
}
