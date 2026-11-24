import styles from './DemoDataBanner.module.css';

/** 모든 화면 맨 위에 시연 데이터임을 표시한다(NFR-10, 07-screens 「모든 화면 공통」). */
export default function DemoDataBanner() {
  return (
    <div className={styles.banner} role="note">
      시연 데이터 · 실제 가게·결제와 무관합니다
    </div>
  );
}
