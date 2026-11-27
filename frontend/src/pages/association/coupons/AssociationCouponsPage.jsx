import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import { getAssociationCouponEvents, stopAssociationCouponEvent } from '@/api/couponEvents.js';
import { getMyWallet } from '@/api/settlement.js';
import { getStores } from '@/api/stores.js';
import { useSession } from '@/auth/session.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import { formatWon } from '@/pages/owner/settlement/settlementFormat.js';
import AssociationCouponForm from './AssociationCouponForm.jsx';
import { formatPeriod } from './couponPeriod.js';
import styles from './AssociationCoupons.module.css';

/**
 * A-10 상인회 쿠폰 관리(B27, 번호 임시). 기준: 07-screens A-10, FR-CPN-12 · 13, 결정 11-25 (5), CONTRACTS_API.md 3.8 · D9.
 * 화면 배치는 B가 정한다(11-25 (5)): 할인을 공동 기금이 부담하므로 '정산' 탭 안, A-04에서 진입.
 */
export default function AssociationCouponsPage() {
  const { account } = useSession();
  const regionId = account?.regionId ?? null;
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', events: [], stores: [], fundBalance: 0, error: null });
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState(null);
  const requestKey = regionId ? `${regionId}#${reloadCount}` : null;
  const state = result.key === requestKey ? result : { status: 'loading', events: [], stores: [], fundBalance: 0, error: null };

  useEffect(() => {
    if (!requestKey) return undefined;
    let active = true;
    Promise.all([getAssociationCouponEvents(), getStores(requestKey.split('#')[0]), getMyWallet()])
      .then(([events, stores, wallet]) => {
        if (active) {
          setResult({
            key: requestKey,
            status: 'ready',
            events: events.items,
            stores: stores.items.filter((store) => store.joinStatus === 'approved'), // 고를 수 있는 가게는 승인된 가게(3.8)
            fundBalance: wallet.balance,
            error: null,
          });
        }
      })
      .catch((error) => {
        if (active) setResult({ key: requestKey, status: 'error', events: [], stores: [], fundBalance: 0, error });
      });
    return () => {
      active = false;
    };
  }, [requestKey]);

  const reload = () => setReloadCount((count) => count + 1);

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>상인회 쿠폰</h1>
        <p className={styles.lead}>골목 전체를 위한 쿠폰 행사를 만들어요. 할인은 공동 기금이 부담하고, 회원이 선착순으로 받아요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {!regionId && <p role="alert">상인회 계정의 권역을 찾지 못했어요.</p>}
      {regionId && state.status === 'loading' && <p role="status">상인회 쿠폰을 불러오고 있어요.</p>}
      {state.status === 'error' && (
        <section className={styles.card} role="alert">
          <p>상인회 쿠폰을 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={reload}>
            다시 불러오기
          </Button>
        </section>
      )}

      {state.status === 'ready' && (
        <>
          {creating ? (
            <AssociationCouponForm
              stores={state.stores}
              fundBalance={state.fundBalance}
              onCancel={() => setCreating(false)}
              onCreated={(event) => {
                setCreating(false);
                setNotice(`'${event.title}' 행사를 확정했어요. 공동 기금에서 ${formatWon(event.reservedBudget)}을 예산으로 잡았어요.`);
                reload();
              }}
            />
          ) : (
            <div className={styles.toolbar}>
              <Button onClick={() => setCreating(true)}>새 행사 만들기</Button>
              <span className={styles.muted}>
                공동 기금 잔액 {formatWon(state.fundBalance)} · <Link to="/association/settlement">공동 기금 · 정산 ›</Link>
              </span>
            </div>
          )}

          <section className={styles.section} aria-label="상인회 쿠폰 행사">
            <h2 className={styles.sectionTitle}>행사 {state.events.length}개</h2>
            {state.events.length === 0 ? (
              <p className={styles.hint}>아직 만든 행사가 없어요.</p>
            ) : (
              <ul className={styles.cards}>
                {state.events.map((event) => (
                  <li key={event.id}>
                    <EventCard
                      event={event}
                      stores={state.stores}
                      onStopped={(stopped) => {
                        setNotice(`'${stopped.title}' 행사를 중단했어요. 새로 받기만 멈추고, 받은 쿠폰은 유효기간까지 쓸 수 있어요.`);
                        reload();
                      }}
                      onOutdated={(message) => {
                        setNotice(message);
                        reload();
                      }}
                    />
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </main>
  );
}

function statusOf(event) {
  if (event.stoppedAt) return { tone: 'canceled', label: '중단' };
  if (event.issuable) return { tone: 'confirmed', label: '받는 중' };
  if (event.remainingQuantity <= 0) return { tone: 'neutral', label: '받기 마감' };
  if (Date.parse(event.issueStartAt) > Date.now()) return { tone: 'requested', label: '받기 전' };
  return { tone: 'neutral', label: '받는 기간 끝' };
}

function describeBenefit(event) {
  return event.benefitType === 'fixed_discount'
    ? `${formatWon(event.discountAmount)} 할인`
    : `${event.discountPercent}% 할인(한 장 최대 ${formatWon(event.maxDiscountAmount)})`;
}

/** 행사 한 개: 혜택 · 가게 · 기간, 발급 수 / 수량, 잡은 · 쓴 · 남은 예산, 중단(FR-CPN-12 · 13) */
function EventCard({ event, stores, onStopped, onOutdated }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const status = statusOf(event);
  const storeNames =
    event.eligibleStoreScope === 'all'
      ? '모든 참여 가게'
      : event.storeIds.map((storeId) => stores.find((store) => store.id === storeId)?.name ?? `가게 ${storeId}`).join(' · ');

  async function stop() {
    setBusy(true);
    setError(null);
    try {
      onStopped(await stopAssociationCouponEvent(event.id));
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onOutdated(`'${event.title}' 행사는 이미 중단됐거나 바뀌었어요. 목록을 다시 불러왔어요.`);
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '중단하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <h3 className={styles.cardTitle}>{event.title}</h3>
        <Badge tone={status.tone}>{status.label}</Badge>
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>혜택</dt>
          <dd>{describeBenefit(event)}</dd>
        </div>
        <div>
          <dt>쓸 수 있는 가게</dt>
          <dd>{storeNames}</dd>
        </div>
        <div>
          <dt>받는 기간</dt>
          <dd>{formatPeriod(event.issueStartAt, event.issueEndAt)}</dd>
        </div>
        <div>
          <dt>쓰는 기간</dt>
          <dd>{formatPeriod(event.validStartAt, event.validEndAt)}</dd>
        </div>
        <div>
          <dt>발급</dt>
          <dd>
            {event.issuedCount} / {event.totalQuantity}장
          </dd>
        </div>
        <div>
          <dt>예산</dt>
          <dd>
            남은 {formatWon(event.remainingBudget)} · 쓴 {formatWon(event.usedBudget)} · 잡은 {formatWon(event.reservedBudget)}
          </dd>
        </div>
      </dl>
      {event.budgetReleasedAt ? (
        <p className={styles.hint}>쓰지 않은 예산은 공동 기금으로 돌아갔어요.</p>
      ) : (
        <p className={styles.hint}>쓰지 않은 예산은 쓰는 기간이 끝나면 공동 기금으로 돌아가요.</p>
      )}

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      {!event.stoppedAt &&
        (confirming ? (
          <div className={styles.confirmBox}>
            <p className={styles.warning}>중단하면 다시 켤 수 없어요. 새로 받기만 멈추고, 이미 받은 쿠폰은 유효기간까지 쓸 수 있어요. 예산은 쓰는 기간이 끝날 때 돌아가요.</p>
            <div className={styles.actions}>
              <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy}>
                돌아가기
              </Button>
              <Button variant="danger" onClick={stop} disabled={busy}>
                중단하기
              </Button>
            </div>
          </div>
        ) : (
          <Button variant="secondary" onClick={() => setConfirming(true)}>
            행사 중단
          </Button>
        ))}
    </article>
  );
}
