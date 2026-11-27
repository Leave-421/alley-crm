import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { ApiError } from '@/api/client.js';
import { couponBenefitText, downloadCoupon, getCouponOffer } from '@/api/coupons.js';
import { MOCK_STORES } from '@/map/storeCatalog.js';
import styles from './CouponOfferPage.module.css';

const DATE_TIME = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const DAY_MS = 86_400_000;

/** 목업의 상태 상자(문구 · 경고 톤). 내 쿠폰(다운로드됨)이 있으면 그 쿠폰 기준으로 보여준다 */
function statusBox(offer, storeName, nowMs) {
  const usable = offer.myCoupons.find((coupon) => coupon.eventId === offer.event?.eventId && coupon.status === 'downloaded');
  if (usable) {
    const days = Math.max(0, Math.ceil((Date.parse(usable.expiresAt) - nowMs) / DAY_MS));
    return { key: 'usable', title: `예약 시 사용 가능 · D-${days}`, sub: `${DATE_TIME.format(Date.parse(usable.expiresAt))}까지 예약을 신청하면 적용돼요` };
  }
  switch (offer.downloadStatus) {
    case 'login_required':
    case 'available':
      return { key: offer.downloadStatus, title: '쿠폰 다운로드 전', sub: `다운로드하면 ${offer.event.validDays}일 동안 예약에 쓸 수 있어요` };
    case 'applied_unused':
      return { key: 'applied', title: '예약에 적용됨', sub: '이 행사 쿠폰이 예약에 적용돼 있어요. 사용 전에는 다시 받을 수 없어요' };
    case 'waiting':
      return { key: 'waiting', warn: true, title: '재발급 대기', sub: `${DATE_TIME.format(Date.parse(offer.nextDownloadableAt))}부터 다시 받을 수 있어요` };
    default:
      return { key: 'unavailable', warn: true, title: '지금은 받을 수 있는 쿠폰이 없어요', sub: `${storeName}의 쿠폰 행사가 끝났거나 준비된 수량이 다 나갔어요` };
  }
}

/**
 * 추천 예약 혜택(G-01 쿠폰 상세, A6 · FR-CPN-02 · 03). 골목 가게 시트의 '예약 혜택' 추천 카드에서 연다(확정 목업 GuestCoupon).
 * 쿠폰 상세(3.4 GET /coupon-offers)로 혜택 · 받을 수 있는지를 그리고, 회원은 '쿠폰 다운로드'(그 카드의 추천 노출 ID로 POST /coupons),
 * 비회원은 '로그인하고 쿠폰 받기' → 로그인 뒤 이 화면으로 돌아온다.
 */
export default function CouponOfferPage() {
  const { alley } = useParams();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const storeId = searchParams.get('store');
  const baseStoreId = searchParams.get('base');
  const exposureId = searchParams.get('exposure');
  const store = MOCK_STORES.find((item) => item.id === storeId);
  const baseStore = MOCK_STORES.find((item) => item.id === baseStoreId);
  const [loaded, setLoaded] = useState(null); // { offer, nowMs } | { error }
  const [reloadCount, setReloadCount] = useState(0);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState(null);


  useEffect(() => {
    if (!storeId) return undefined;
    let active = true;
    getCouponOffer(storeId)
      .then((offer) => active && setLoaded({ offer, nowMs: Date.now() }))
      .catch((error) => active && setLoaded({ error }));
    return () => {
      active = false;
    };
  }, [storeId, reloadCount]);

  const backToAlley = `/alleys/${alley}${baseStoreId ? `?focus=${encodeURIComponent(baseStoreId)}` : ''}`;
  const loginPath = `/login?next=${encodeURIComponent(location.pathname + location.search)}`;

  async function download() {
    if (downloading || !exposureId) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await downloadCoupon(exposureId);
    } catch (error) {
      const code = error instanceof ApiError ? error.code : null;
      if (code === 'unauthorized') {
        navigate(loginPath);
        return;
      }
      if (code === 'not_found') setDownloadError('추천 기록을 찾지 못했어요. 골목에서 추천 카드를 다시 열어 주세요.');
      else if (code !== 'conflict') setDownloadError('쿠폰을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      // conflict는 받을 수 없게 된 것 — 아래에서 쿠폰 상세를 다시 불러와 지금 상태(대기 · 마감 등)를 보여준다
    } finally {
      setDownloading(false);
    }
    setReloadCount((count) => count + 1);
  }

  if (!store) {
    return (
      <main className={styles.page}>
        <p className={styles.cap}>가게를 찾을 수 없어요.</p>
        <Link to={backToAlley} className={`${buttonStyles.button} ${buttonStyles.secondary} ${buttonStyles.block}`}>
          골목으로 돌아가기
        </Link>
      </main>
    );
  }

  const offer = loaded?.offer;
  const box = offer ? statusBox(offer, store.name, loaded.nowMs) : null;

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>{store.name} 혜택</h1>
      <p className={styles.lead}>
        {baseStore ? `${baseStore.name}에서 추천됨 · ` : ''}대상 가게는 {store.name}
      </p>

      {!loaded && <p className={styles.cap}>쿠폰 정보를 불러오는 중이에요…</p>}
      {loaded?.error && (
        <p className={styles.cap} role="alert">
          {loaded.error instanceof ApiError && loaded.error.status === 403 ? '손님만 쿠폰을 받을 수 있어요.' : '쿠폰 정보를 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.'}
        </p>
      )}

      {offer && (
        <>
          {offer.event && (
            <div className={styles.benefit}>
              <div className={styles.benefitLabel}>LOCORI BENEFIT</div>
              <div className={styles.benefitValue}>{couponBenefitText(offer.event)}</div>
              <div className={styles.benefitSub}>{store.name}에서만 사용할 수 있어요</div>
            </div>
          )}

          <div className={box.warn ? `${styles.status} ${styles.statusWarn}` : styles.status} role="status">
            <div className={styles.statusTitle}>{box.title}</div>
            <div className={styles.statusSub}>{box.sub}</div>
          </div>

          {downloadError && (
            <p className={styles.error} role="alert">
              {downloadError}
            </p>
          )}

          <div className={styles.actions}>
            {box.key === 'login_required' && (
              <>
                <Link to={loginPath} className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block}`}>
                  로그인하고 쿠폰 받기
                </Link>
                <p className={`${styles.cap} ${styles.center}`}>쿠폰은 회원만 받을 수 있어요. 로그인하면 이 화면으로 돌아와요.</p>
              </>
            )}
            {box.key === 'available' && (
              <>
                <button type="button" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block}`} disabled={downloading || !exposureId} onClick={download}>
                  {downloading ? '받는 중…' : '쿠폰 다운로드'}
                </button>
                {!exposureId && <p className={`${styles.cap} ${styles.center}`}>추천 카드에서 연 화면에서만 받을 수 있어요. 골목에서 다시 열어 주세요.</p>}
              </>
            )}
            {box.key === 'usable' && (
              <>
                <Link to={`/alleys/${store.alley}/reserve?store=${encodeURIComponent(store.id)}`} className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block}`}>
                  {store.name} 예약하러 가기
                </Link>
                <Link to="/my" className={`${buttonStyles.button} ${buttonStyles.secondary} ${buttonStyles.block}`}>
                  내 쿠폰 보기
                </Link>
              </>
            )}
            {box.key === 'applied' && (
              <Link to="/reservations" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block}`}>
                적용된 예약 보기
              </Link>
            )}
            {box.key === 'waiting' && (
              <button type="button" className={`${buttonStyles.button} ${buttonStyles.block} ${styles.waitButton}`} disabled>
                아직 받을 수 없어요
              </button>
            )}
            <Link to={backToAlley} className={`${buttonStyles.button} ${buttonStyles.secondary} ${buttonStyles.block}`}>
              골목으로 돌아가기
            </Link>
          </div>

          {offer.event && (
            <>
              <h2 className={styles.sectionTitle}>사용 전 확인해주세요</h2>
              <ul className={styles.notes}>
                <li>
                  다운로드 후 <b className={styles.num}>{offer.event.validDays}</b>일 안에 예약을 신청해야 적용돼요
                </li>
                <li>
                  같은 행사 쿠폰은 <b className={styles.num}>30</b>일에 <b>1</b>번 받을 수 있어요
                </li>
                {offer.event.conditions && <li>{offer.event.conditions}</li>}
                <li>예약 한 건에는 쿠폰 하나만 적용돼요</li>
                <li>회원만 받을 수 있어요</li>
              </ul>
            </>
          )}
        </>
      )}
    </main>
  );
}
