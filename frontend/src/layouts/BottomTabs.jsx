import { NavLink } from 'react-router';
import TabIcon from '@/layouts/TabIcon.jsx';
import styles from './BottomTabs.module.css';

/**
 * 역할별 하단 탭(아이콘 + 글자).
 * 지금 화면의 탭은 아이콘 동그라미 · 굵은 글자 · aria-current로 함께 표시한다(색만으로 나누지 않음, NFR-11).
 */
export default function BottomTabs({ tabs }) {
  return (
    <nav className={styles.tabbar} aria-label="하단 메뉴">
      <ul className={styles.list}>
        {tabs.map((tab) => (
          <li key={tab.to} className={styles.item}>
            <NavLink
              to={tab.to}
              end={tab.end}
              className={({ isActive }) => (isActive ? `${styles.tab} ${styles.active}` : styles.tab)}
            >
              <TabIcon name={tab.icon} size={20} className={styles.icon} />
              <span>{tab.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
