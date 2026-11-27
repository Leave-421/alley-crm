import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import buttonStyles from '@/components/Button/Button.module.css';
import { ApiError } from '@/api/client.js';
import { AGE_GROUP_LABELS, GENDER_LABELS, GUEST_LIMITS, createReservation, getAvailableSlots } from '@/api/reservations.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import { MOCK_STORES } from '@/map/storeCatalog.js';
import { couponBenefitText, getUsableCoupons } from '@/api/coupons.js';
import ReserveDone from './ReserveDone.jsx';
import { formatDateKey, formatSlotTime, serviceDateKey, upcomingDays } from './reserveTime.js';
import styles from './ReservePage.module.css';

const DATE_CHOICES = 7; // 목업: '날짜 · 7일 안에서 선택'
const PARTY_FALLBACK_MAX = 8; // 가게가 한 팀 최대 인원을 정하지 않았을 때(maxPartySize null) 인원 칸의 상한(목업 값)

// 공유 항목(FR-CUS-01, CONTRACTS.md 「3」 shareItem). 이름 · 연락처 · 방문한 가게 목록은 끌 수 없는 항목
const SHARE_ITEMS = '이름 · 연락처 · 방문한 가게 목록(꼭 필요한 항목), 인원 · 방문 일시 · 성별 · 연령대';

const EMPTY_GUEST = Object.freeze({ name: '', phone: '', gender: null, ageGroup: null });

/** 서버 실패 코드 → 화면 문구(CONTRACTS_API 「POST /reservations」) */
function submitErrorMessage(error) {
  switch (error instanceof ApiError ? error.code : null) {
    case 'slot_unavailable':
      return '그 시간은 방금 다 찼거나 예약을 받을 수 없게 됐어요. 다른 시간을 골라 주세요.';
    case 'insufficient_balance':
      return '가상 잔액이 예약금보다 적어요.';
    case 'forbidden_role':
      return '손님 계정으로만 예약할 수 있어요.';
    case 'not_found':
      return '가게나 기준 예약을 찾을 수 없어요.';
    case 'conflict':
      // 다음 코스: 기준 예약 취소 · 거절, 같은 날 기준 시각과 같거나 이름, 같은 가게 / 쓸 수 없는 쿠폰(D12 · 3.5)
      return '이 예약은 신청할 수 없어요. 기준 가게 예약이 취소됐거나, 같은 날 기준 예약보다 이른 시간이거나, 쿠폰을 쓸 수 없어요.';
    case 'invalid':
      return '입력한 내용을 확인해 주세요.';
    default:
      return error?.message || '예약을 신청하지 못했어요. 잠시 뒤 다시 시도해 주세요.';
  }
}

/**
 * 예약하기(G-02, 기준 가게). 가게 시트의 '예약하기'에서 `?store=가게ID`로 들어온다.
 * 날짜 · 시간 · 인원 → 예약자(회원은 로그인 계정, 비회원은 이름 · 전화번호 필수, 성별 · 연령대 선택) → 가게 간 정보 공유 동의 →
 * 신청. 완료하면 같은 화면에서 신청 결과와 다음 코스 추천 3곳을 보여준다.
 */
export default function ReservePage() {
  const { alley } = useParams();
  const [searchParams] = useSearchParams();
  const store = MOCK_STORES.find((item) => item.id === searchParams.get('store'));
  // 가게 시트에서 날짜 · 시간을 골라 왔으면(`&at=UTC시각`, G-01) 그 날짜 · 시간을 고른 채로 연다
  const at = searchParams.get('at');
  return <ReserveFlow store={store} alley={alley} initialStartAt={at && !Number.isNaN(Date.parse(at)) ? at : null} />;
}

/**
 * 예약 입력 → 신청 → 완료. G-02(기준 가게)와 G-03(다음 코스)이 같이 쓴다.
 * course: 다음 코스일 때 { rootReservationId, rootStoreId, rootStartAt, proposalId | null } — 기준 예약을 보여주고,
 * 예약 가능 시간에 after(기준 시각)를 넣어 같은 날은 그보다 늦은 시간만 받으며, 신청에 courseRootId · courseProposalId를 싣는다.
 */
export function ReserveFlow({ store, alley, course = null, initialStartAt = null }) {
  const { status, role, account } = useSession();
  const loggedIn = status === SESSION_STATUS.AUTHENTICATED;
  const isMember = loggedIn && role === 'customer';

  const [days] = useState(() => upcomingDays(DATE_CHOICES));
  const initialDay = initialStartAt ? serviceDateKey(Date.parse(initialStartAt)) : null;
  const preset = initialDay && days.some((day) => day.key === initialDay);
  const [dateKey, setDateKey] = useState(preset ? initialDay : days[0].key);
  const [slotStartAt, setSlotStartAt] = useState(preset ? initialStartAt : null);
  const [party, setParty] = useState(2);
  const [guest, setGuest] = useState(EMPTY_GUEST);
  const [consent, setConsent] = useState(false);
  const [availability, setAvailability] = useState(null); // 마지막으로 받은 { key, data | error }
  const [reloadCount, setReloadCount] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const [result, setResult] = useState(null);
  const [coupons, setCoupons] = useState(null); // 회원의 이 가게 사용 가능한 쿠폰 { data } | { error }
  const [couponId, setCouponId] = useState(null); // 기본값 '적용 안 함', 자동 선택 없음(FR-CPN-06)
  const rootStartAt = course?.rootStartAt ?? null;

  useEffect(() => {
    if (!store) return undefined;
    let active = true;
    const key = `${dateKey}#${reloadCount}`;
    getAvailableSlots(store.id, dateKey, rootStartAt)
      .then((data) => active && setAvailability({ key, data }))
      .catch((error) => active && setAvailability({ key, error }));
    return () => {
      active = false;
    };
  }, [store, dateKey, reloadCount, rootStartAt]);

  useEffect(() => {
    if (!store || !isMember) return undefined;
    let active = true;
    getUsableCoupons(store.id)
      .then((data) => active && setCoupons({ data }))
      .catch((error) => active && setCoupons({ error }));
    return () => {
      active = false;
    };
  }, [store, isMember]);

  if (!store) {
    return (
      <main className={styles.page}>
        <section className={styles.card}>
          <p className={styles.notice}>예약할 가게를 찾을 수 없어요. 골목에서 가게를 눌러 다시 들어와 주세요.</p>
          <Link to={alley ? `/alleys/${alley}` : '/alleys'} className={`${buttonStyles.button} ${buttonStyles.secondary} ${styles.linkButton}`}>
            골목으로 돌아가기
          </Link>
        </section>
      </main>
    );
  }

  if (result) {
    return <ReserveDone store={store} alley={alley} reservation={result} isMember={isMember} isCourse={Boolean(course)} />;
  }

  const current = availability?.key === `${dateKey}#${reloadCount}` ? availability : null;
  const slotsData = current?.data ?? null;
  const maxParty = slotsData?.maxPartySize ?? PARTY_FALLBACK_MAX;
  const overMax = party > maxParty;
  const deposit = (slotsData?.depositPerPerson ?? 0) * party;
  const guestNameOk = guest.name.trim().length >= 1 && guest.name.trim().length <= GUEST_LIMITS.nameMaxLength;
  const guestPhoneOk = GUEST_LIMITS.phonePattern.test(guest.phone);
  const slotOk = Boolean(slotStartAt && slotsData?.slots.some((slot) => slot.startAt === slotStartAt && slot.available));

  let blockedReason = null;
  if (loggedIn && !isMember) blockedReason = '손님 계정으로만 예약할 수 있어요';
  else if (!slotOk) blockedReason = '시간을 선택해 주세요';
  else if (overMax) blockedReason = `한 팀 최대 ${maxParty}명까지 예약할 수 있어요`;
  else if (!isMember && (!guestNameOk || !guestPhoneOk)) blockedReason = '이름과 전화번호를 입력해 주세요';
  else if (!consent) blockedReason = '가게 간 정보 공유 동의가 필요해요';

  function pickDate(key) {
    setDateKey(key);
    setSlotStartAt(null);
  }

  async function submit() {
    if (blockedReason || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const reservation = await createReservation({
        storeId: store.id,
        startAt: slotStartAt,
        partySize: party,
        guest: isMember ? null : { name: guest.name.trim(), phone: guest.phone, gender: guest.gender, ageGroup: guest.ageGroup },
        couponId: isMember ? couponId : null,
        courseRootId: course?.rootReservationId ?? null,
        courseProposalId: course?.proposalId ?? null,
      });
      setResult(reservation);
    } catch (error) {
      setSubmitError(error);
      if (error instanceof ApiError && error.code === 'slot_unavailable') {
        setSlotStartAt(null);
        setReloadCount((count) => count + 1);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className={styles.page}>
      <span className={styles.badge}>{course ? '다음 코스로 예약하는 가게' : '먼저 예약하는 가게'}</span>
      <h1 className={styles.title}>{store.name}</h1>
      {course && (
        <section className={`${styles.card} ${styles.rootCard}`} aria-label="기준 가게 예약">
          <span className={styles.label}>기준 가게 예약</span>
          <div className={styles.rootLine}>
            <b>{MOCK_STORES.find((item) => item.id === course.rootStoreId)?.name ?? '기준 가게'}</b>
            <span className={styles.num}>
              {formatDateKey(serviceDateKey(Date.parse(course.rootStartAt)))} {formatSlotTime(course.rootStartAt)}
            </span>
          </div>
          <p className={styles.cap}>같은 날은 이 시간보다 늦은 시간만 고를 수 있어요. 다른 날짜는 모든 시간을 고를 수 있어요.</p>
        </section>
      )}

      <div className={styles.layout}>
        <div>
          <section className={styles.card} aria-labelledby="reserve-date">
            <h2 id="reserve-date" className={styles.label}>
              날짜 · {DATE_CHOICES}일 안에서 선택
            </h2>
            <div className={styles.dates}>
              {days.map((day) => (
                <button
                  key={day.key}
                  type="button"
                  className={day.key === dateKey ? `${styles.date} ${styles.dateOn}` : styles.date}
                  aria-pressed={day.key === dateKey}
                  aria-label={`${day.day}일 ${day.weekday}요일`}
                  onClick={() => pickDate(day.key)}
                >
                  <span className={styles.dateWeekday}>{day.weekday}</span>
                  <span className={styles.dateDay}>{day.day}</span>
                </button>
              ))}
            </div>
          </section>

          <section className={styles.card} aria-labelledby="reserve-time">
            <h2 id="reserve-time" className={styles.label}>
              시간
            </h2>
            {!current && <p className={styles.notice}>예약 가능한 시간을 불러오는 중이에요…</p>}
            {current?.error && (
              <p className={styles.notice} role="alert">
                예약 가능한 시간을 불러오지 못했어요.{' '}
                <button type="button" className={styles.inlineButton} onClick={() => setReloadCount((count) => count + 1)}>
                  다시 불러오기
                </button>
              </p>
            )}
            {slotsData && !slotsData.acceptingReservations && <p className={styles.notice}>지금은 이 가게가 예약을 받지 않아요.</p>}
            {slotsData?.acceptingReservations && slotsData.slots.length === 0 && <p className={styles.notice}>이 날은 쉬는 날이에요.</p>}
            {slotsData?.slots.length > 0 && (
              <div className={styles.slots}>
                {slotsData.slots.map((slot) => {
                  const on = slot.startAt === slotStartAt;
                  const classes = [styles.slot, on && styles.slotOn, !slot.available && styles.slotOff].filter(Boolean).join(' ');
                  return (
                    <button
                      key={slot.startAt}
                      type="button"
                      className={classes}
                      aria-pressed={on}
                      disabled={!slot.available}
                      onClick={() => setSlotStartAt(slot.startAt)}
                    >
                      <b className={styles.num}>{formatSlotTime(slot.startAt)}</b>
                      <span className={styles.slotSub}>{!slot.available ? '마감' : on ? '선택' : '예약 가능'}</span>
                    </button>
                  );
                })}
              </div>
            )}
          </section>

          <section className={`${styles.card} ${styles.partyRow}`} aria-labelledby="reserve-party">
            <div>
              <h2 id="reserve-party" className={styles.label}>
                인원{slotsData?.maxPartySize ? ` · 한 팀 최대 ${slotsData.maxPartySize}명` : ''}
              </h2>
              <div className={`${styles.partyCount} ${styles.num}`} aria-live="polite">
                {party}명
              </div>
            </div>
            <div className={styles.partyButtons}>
              <button type="button" className={styles.partyButton} aria-label="인원 줄이기" disabled={party <= 1} onClick={() => setParty((n) => Math.max(1, n - 1))}>
                −
              </button>
              <button type="button" className={styles.partyButton} aria-label="인원 늘리기" disabled={party >= maxParty} onClick={() => setParty((n) => Math.min(maxParty, n + 1))}>
                +
              </button>
            </div>
          </section>
          {overMax && (
            <p className={styles.warn} role="alert">
              이 가게는 한 팀 최대 {maxParty}명까지 예약할 수 있어요. 인원을 줄여 주세요.
            </p>
          )}
        </div>

        <div>
          <section className={styles.card} aria-labelledby="reserve-who">
            <h2 id="reserve-who" className={styles.label}>
              예약자
            </h2>
            {isMember && (
              <>
                <div className={styles.memberName}>{account?.loginId} 회원</div>
                <p className={styles.cap}>회원 정보로 바로 예약해요</p>
              </>
            )}
            {loggedIn && !isMember && <p className={styles.warn}>손님 계정으로만 예약할 수 있어요. 시연 도구에서 손님으로 바꿔 주세요.</p>}
            {!loggedIn && (
              <>
                <label className={styles.field}>
                  <span className={styles.cap}>이름 · 필수</span>
                  <input
                    className={styles.input}
                    value={guest.name}
                    maxLength={GUEST_LIMITS.nameMaxLength}
                    autoComplete="name"
                    onChange={(event) => setGuest((prev) => ({ ...prev, name: event.target.value }))}
                  />
                </label>
                <label className={styles.field}>
                  <span className={styles.cap}>전화번호 · 필수 (숫자만)</span>
                  <input
                    className={styles.input}
                    value={guest.phone}
                    inputMode="numeric"
                    autoComplete="tel"
                    placeholder="01012345678"
                    // 하이픈을 넣어 입력해도 되게 숫자만 남긴 뒤 11자리로 자른다(입력칸 maxLength는 하이픈까지 세서 쓰지 않음)
                    onChange={(event) => setGuest((prev) => ({ ...prev, phone: event.target.value.replace(/\D/g, '').slice(0, 11) }))}
                  />
                </label>
                <ChoiceRow label="성별 · 선택" labels={GENDER_LABELS} value={guest.gender} onChange={(gender) => setGuest((prev) => ({ ...prev, gender }))} />
                <ChoiceRow label="연령대 · 선택" labels={AGE_GROUP_LABELS} value={guest.ageGroup} onChange={(ageGroup) => setGuest((prev) => ({ ...prev, ageGroup }))} />
                <p className={styles.cap}>
                  비회원 예약은 완료 화면의 예약번호와 전화번호로 확인해요. <Link to="/login">로그인</Link>하면 회원으로 예약해요.
                </p>
              </>
            )}
          </section>

          <section className={styles.card} aria-labelledby="reserve-consent">
            <h2 id="reserve-consent" className={styles.label}>
              정보 공유 동의
            </h2>
            <button type="button" role="checkbox" aria-checked={consent} className={styles.check} onClick={() => setConsent((on) => !on)}>
              <span className={consent ? `${styles.checkBox} ${styles.checkBoxOn}` : styles.checkBox} aria-hidden="true">
                {consent ? '✓' : ''}
              </span>
              <span>[필수] 가게 간 정보 공유에 동의해요</span>
            </button>
            <p className={styles.cap}>공유 항목: {SHARE_ITEMS}</p>
            <p className={styles.cap}>
              공유한 정보는 다음 방문 추천과 집계 계산에만 써요. 동의하지 않으면 예약할 수 없어요. <Link to="/policy">정책 · 개인정보</Link>
            </p>
          </section>

          <section className={styles.card} aria-labelledby="reserve-coupon">
            <h2 id="reserve-coupon" className={styles.label}>
              쿠폰
            </h2>
            {isMember ? (
              <div className={styles.couponList} role="radiogroup" aria-label="적용할 쿠폰">
                {[{ couponId: null }, ...(coupons?.data?.items ?? [])].map((coupon) => {
                  const on = couponId === coupon.couponId;
                  return (
                    <button
                      key={coupon.couponId ?? 'none'}
                      type="button"
                      role="radio"
                      aria-checked={on}
                      className={on ? `${styles.couponOption} ${styles.couponOn}` : styles.couponOption}
                      onClick={() => setCouponId(coupon.couponId)}
                    >
                      <span className={styles.radio} aria-hidden="true" />
                      {coupon.couponId === null ? (
                        <span>적용 안 함</span>
                      ) : (
                        <span>
                          <b>{couponBenefitText(coupon)}</b>
                          <span className={styles.couponSub}>
                            {formatDateKey(serviceDateKey(Date.parse(coupon.expiresAt)))} {formatSlotTime(coupon.expiresAt)}까지
                          </span>
                        </span>
                      )}
                    </button>
                  );
                })}
                {coupons?.error && <p className={styles.cap}>쿠폰을 불러오지 못했어요. 쿠폰 없이 예약할 수 있어요.</p>}
                {coupons?.data && coupons.data.items.length === 0 && <p className={styles.cap}>이 가게에서 쓸 수 있는 쿠폰이 없어요.</p>}
              </div>
            ) : (
              <p className={styles.cap}>
                쿠폰은 회원만 쓸 수 있어요. {!loggedIn && <Link to="/login">로그인하고 쿠폰 쓰기</Link>}
              </p>
            )}
          </section>

          {deposit > 0 && (
            <section className={`${styles.card} ${styles.num}`} aria-label="예약금">
              <div className={styles.sumRow}>
                <span className={styles.muted}>
                  예약금 ({slotsData.depositPerPerson.toLocaleString('ko-KR')}원 × {party}명)
                </span>
                <b>{deposit.toLocaleString('ko-KR')}원</b>
              </div>
              <div className={`${styles.sumRow} ${styles.sumTotal}`}>
                <b>시연 합계</b>
                <b className={styles.total}>{deposit.toLocaleString('ko-KR')}원</b>
              </div>
            </section>
          )}
          <p className={styles.cap}>예약 24시간 전까지 취소하면 100% 환불 · 그 뒤 마감 전까지는 가게가 정한 비율만큼 · 실제 결제는 발생하지 않아요.</p>

          {submitError && (
            <div className={styles.error} role="alert">
              {submitErrorMessage(submitError)}
              {submitError instanceof ApiError && submitError.code === 'insufficient_balance' && (
                <Link to="/my" className={styles.errorLink}>
                  마이에서 충전하기
                </Link>
              )}
            </div>
          )}

          {blockedReason ? (
            <div className={styles.blocked} role="status">
              {blockedReason}
            </div>
          ) : (
            <button type="button" className={`${buttonStyles.button} ${buttonStyles.primary} ${buttonStyles.block} ${styles.submit}`} disabled={submitting} onClick={submit}>
              {submitting ? '신청하는 중…' : '이대로 예약하기'}
            </button>
          )}
        </div>
      </div>
    </main>
  );
}

/** 선택 항목 칩 한 줄(성별 · 연령대). '선택 안 함'이 기본값(null) */
function ChoiceRow({ label, labels, value, onChange }) {
  const options = [[null, '선택 안 함'], ...Object.entries(labels)];
  return (
    <div className={styles.field} role="group" aria-label={label}>
      <span className={styles.cap}>{label}</span>
      <div className={styles.choices}>
        {options.map(([key, text]) => (
          <button
            key={text}
            type="button"
            className={value === key ? `${styles.choice} ${styles.choiceOn}` : styles.choice}
            aria-pressed={value === key}
            onClick={() => onChange(key)}
          >
            {text}
          </button>
        ))}
      </div>
    </div>
  );
}
