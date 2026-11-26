import { useEffect, useState } from 'react';
import { getStoreJoins, getStores } from '@/api/stores.js';
import { useSession } from '@/auth/session.js';
import Button from '@/components/Button/Button.jsx';
import ParticipatingStores from './ParticipatingStores.jsx';
import StoreJoinRequests from './StoreJoinRequests.jsx';
import styles from './StoreManagement.module.css';

const SHARING_SUMMARY = [
  { item: '이름 · 연락처 · 방문한 가게 목록', rule: '필수 공유(끌 수 없음)' },
  { item: '성별 · 연령대', rule: '익명 집계로만 보여요' },
];

/**
 * A-05 가게 관리(B19): 가입 신청 승인 · 거절, 참여 가게 목록과 위치 · 주소 관리, 공유 정보 정책 요약.
 * 기준: 07-screens A-05, FR-STO-01 · 02, FR-CUS-01 · 06, CONTRACTS_API.md C5 · C6.
 * 권역은 로그인한 상인회 계정의 regionId로 정한다(권역 선택 없음).
 * 탈퇴 처리(A-09) · 공유 항목 설정(A-08) 진입은 여유 이슈(B21)에서 더한다.
 */
export default function StoreManagementPage() {
  const { account } = useSession();
  const regionId = account?.regionId ?? null;
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', stores: [], joins: [], totalJoins: 0, error: null });
  const [notice, setNotice] = useState(null);
  const requestKey = regionId ? `${regionId}#${reloadCount}` : null;
  const state = result.key === requestKey ? result : { status: 'loading', stores: [], joins: [], totalJoins: 0, error: null };

  useEffect(() => {
    if (!requestKey) return undefined;
    let active = true;
    Promise.all([getStores(requestKey.split('#')[0]), getStoreJoins({ status: 'requested' })])
      .then(([storeList, joinPage]) => {
        if (active) {
          setResult({ key: requestKey, status: 'ready', stores: storeList.items, joins: joinPage.items, totalJoins: joinPage.totalElements, error: null });
        }
      })
      .catch((error) => {
        if (active) setResult({ key: requestKey, status: 'error', stores: [], joins: [], totalJoins: 0, error });
      });
    return () => {
      active = false;
    };
  }, [requestKey]);

  const reload = () => setReloadCount((count) => count + 1);

  function handleDecided(join) {
    setNotice(join.joinStatus === 'approved' ? `‘${join.name}’ 가입을 승인했어요. 참여 가게 목록에 더해졌어요.` : `‘${join.name}’ 가입을 거절했어요.`);
    reload(); // 승인하면 참여 가게 목록도 바뀐다
  }

  function handleOutdated(message) {
    setNotice(message);
    reload();
  }

  function handleLocationUpdated(store) {
    setNotice(`‘${store.name}’ 위치 · 주소를 저장했어요.`);
    setResult((previous) => ({ ...previous, stores: previous.stores.map((item) => (item.id === store.id ? store : item)) }));
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>가게 관리</h1>
        <p className={styles.lead}>가입 신청을 처리하고, 참여 가게의 지도 위치 · 주소를 관리해요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {!regionId && <p role="alert">상인회 계정의 권역을 찾지 못했어요.</p>}
      {regionId && state.status === 'loading' && <p role="status">가게 정보를 불러오고 있어요.</p>}
      {state.status === 'error' && (
        <section className={styles.section} role="alert">
          <p>가게 정보를 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={reload}>
            다시 불러오기
          </Button>
        </section>
      )}

      {state.status === 'ready' && (
        <>
          <StoreJoinRequests joins={state.joins} totalElements={state.totalJoins} onDecided={handleDecided} onOutdated={handleOutdated} />
          <ParticipatingStores stores={state.stores} onUpdated={handleLocationUpdated} />
        </>
      )}

      <section className={styles.section} aria-label="공유 정보 정책">
        <h2 className={styles.sectionTitle}>공유 정보 정책</h2>
        <dl className={styles.facts}>
          {SHARING_SUMMARY.map(({ item, rule }) => (
            <div key={item} className={styles.fact}>
              <dt>{item}</dt>
              <dd>{rule}</dd>
            </div>
          ))}
        </dl>
      </section>
    </main>
  );
}
