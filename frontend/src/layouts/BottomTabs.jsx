import { Link, matchPath, NavLink, useLocation } from 'react-router';
import TabIcon from '@/layouts/TabIcon.jsx';
import styles from './BottomTabs.module.css';

/**
 * 역할별 하단 탭(아이콘 + 글자).
 * 지금 화면의 탭은 아이콘 동그라미 · 굵은 글자 · aria-current로 함께 표시한다(색만으로 나누지 않음, NFR-11).
 * tab.also: end 탭이어도 같이 켜질 하위 주소(예: 예약 탭 안의 운영 설정 /owner/settings).
 */
export default function BottomTabs({ tabs }) {
  const { pathname } = useLocation();
  return (
    <nav className={styles.tabbar} aria-label="하단 메뉴">
      <ul className={styles.list}>
        {tabs.map((tab) => {
          const alsoActive = (tab.also ?? []).some((path) => matchPath({ path, end: false }, pathname));
          const content = (
            <>
              <TabIcon name={tab.icon} size={20} className={styles.icon} />
              <span>{tab.label}</span>
            </>
          );
          return (
            <li key={tab.to} className={styles.item}>
              {alsoActive ? (
                // NavLink는 aria-current를 자기 판정으로 덮어써서, 하위 화면에서 켤 때는 Link로 그린다
                <Link to={tab.to} aria-current="page" className={`${styles.tab} ${styles.active}`}>
                  {content}
                </Link>
              ) : (
                <NavLink to={tab.to} end={tab.end} className={({ isActive }) => (isActive ? `${styles.tab} ${styles.active}` : styles.tab)}>
                  {content}
                </NavLink>
              )}
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
