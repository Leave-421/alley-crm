import { useEffect, useState } from 'react';
import { getAvailableSlots } from '@/api/reservations.js';
import { formatSlotTime, serviceDateKey, upcomingDays } from '@/pages/customer/reserve/reserveTime.js';
import styles from './SheetSlotPicker.module.css';

const DATE_CHOICES = 7;
const shortDate = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', weekday: 'short' });

/** 목업 dayLbl: '오늘 11. 28. (토)' · '11. 29. (일)' */
function dayLabel(dateKey, isToday) {
  return `${isToday ? '오늘 ' : ''}${shortDate.format(new Date(`${dateKey}T12:00:00+09:00`))}`;
}

/**
 * 가게 시트의 '예약 시간'(G-01, FR-RSV-05 · 11). 확정 목업 GuestAlley 시트의 날짜 칩 · 시간 칩을 옮겼다.
 * 슬롯은 가게 슬롯 단위(available-slots). 앞 일정(기준 가게 예약)과 같은 날이면 after로 그 시각보다 늦은 슬롯만 받고,
 * 다른 날짜는 모든 시간을 고를 수 있다. '앞 일정 지우기'를 누르면 앞 일정 없이 모든 시간을 고른다.
 * 고른 시각은 onPick으로 알리고(같은 시각을 다시 누르면 해제), 예약하기 바가 그 시각으로 예약 화면을 연다.
 */
export default function SheetSlotPicker({ store, priorPlan, onClearPlan, picked, onPick }) {
  const [days] = useState(() => upcomingDays(DATE_CHOICES));
  const [dateKey, setDateKey] = useState(days[0].key);
  const [loaded, setLoaded] = useState(null); // { key, data } | { key, error }

  const planDay = priorPlan ? serviceDateKey(Date.parse(priorPlan.startAt)) : null;
  const after = planDay === dateKey ? priorPlan.startAt : null;
  const requestKey = `${store.id}|${dateKey}|${after ?? ''}`;

  useEffect(() => {
    let active = true;
    getAvailableSlots(store.id, dateKey, after)
      .then((data) => active && setLoaded({ key: requestKey, data }))
      .catch((error) => active && setLoaded({ key: requestKey, error }));
    return () => {
      active = false;
    };
  }, [store.id, dateKey, after, requestKey]);

  const current = loaded?.key === requestKey ? loaded : null;
  const slots = current?.data?.slots ?? [];
  const openCount = slots.filter((slot) => slot.available).length;
  const label = dayLabel(dateKey, dateKey === days[0].key);

  let hint = '시간을 불러오는 중이에요…';
  if (current?.error) hint = '예약 가능한 시간을 불러오지 못했어요.';
  else if (current?.data && current.data.acceptingReservations === false) hint = '지금은 예약을 받지 않는 가게예요.';
  else if (current?.data) hint = openCount ? `${label} · 줄 그은 시간은 고를 수 없어요` : `${label}에는 고를 수 있는 시간이 없어요. 다른 날짜를 골라보세요.`;

  return (
    <section aria-labelledby="sheet-slot-title">
      <div id="sheet-slot-title" className={styles.title}>
        예약 시간
      </div>
      {priorPlan && (
        <div className={styles.course}>
          <span className={styles.courseText}>
            {planDay === dateKey
              ? `${priorPlan.storeName} ${dayLabel(planDay, planDay === days[0].key)} ${formatSlotTime(priorPlan.startAt)} 일정보다 늦은 시간만 고를 수 있어요. 다른 날짜는 모든 시간을 고를 수 있어요.`
              : `${priorPlan.storeName} 일정(${dayLabel(planDay, planDay === days[0].key)} ${formatSlotTime(priorPlan.startAt)})과 다른 날이라 모든 시간을 고를 수 있어요.`}
          </span>
          <button type="button" className={styles.clear} onClick={onClearPlan}>
            앞 일정 지우기
          </button>
        </div>
      )}

      <div className={styles.dates} role="radiogroup" aria-label="날짜 선택">
        {days.map((day, index) => {
          const on = day.key === dateKey;
          return (
            <button
              key={day.key}
              type="button"
              role="radio"
              aria-checked={on}
              className={on ? `${styles.date} ${styles.dateOn}` : styles.date}
              onClick={() => {
                setDateKey(day.key);
                onPick(null);
              }}
            >
              <span className={day.weekday === '일' && !on ? `${styles.dateSub} ${styles.sunday}` : styles.dateSub}>{index === 0 ? '오늘' : day.weekday}</span>
              <b className={styles.num}>{day.day}</b>
            </button>
          );
        })}
      </div>

      {slots.length > 0 && (
        <div className={styles.times} role="radiogroup" aria-label="시간 선택">
          {slots.map((slot) => {
            const on = slot.startAt === picked;
            const classes = [styles.time, styles.num, on && styles.timeOn, !slot.available && styles.timeOff].filter(Boolean).join(' ');
            return (
              <button
                key={slot.startAt}
                type="button"
                role="radio"
                aria-checked={on}
                aria-disabled={!slot.available}
                aria-label={`${formatSlotTime(slot.startAt)}${slot.available ? '' : ' 고를 수 없음'}`}
                className={classes}
                onClick={() => slot.available && onPick(on ? null : slot.startAt)}
              >
                {formatSlotTime(slot.startAt)}
              </button>
            );
          })}
        </div>
      )}
      <div className={styles.hint}>{hint}</div>
    </section>
  );
}
