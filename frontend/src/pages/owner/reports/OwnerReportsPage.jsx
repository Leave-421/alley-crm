import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import {
  correctVisitAmount,
  getOwnerVisitReports,
  processLateVisit,
  REPLY_MAX_LENGTH,
  replyToVisitReport,
  VISIT_REPORT_RESULT_LABELS,
  VISIT_REPORT_STATUS_LABELS,
  VISIT_REPORT_TYPE_LABELS,
} from '@/api/visitReports.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import { formatDateTime, formatMonth, formatSignedWon, formatWon } from '@/pages/owner/settlement/settlementFormat.js';
import styles from '@/pages/association/coupons/AssociationCoupons.module.css';

const PAGE_SIZE = 20;

/**
 * 신고 확인(B29, O-10 · O-13 요소 — 위치는 임시로 B가 정함, 11-25 (5)): 상인회의 확인 요청을 받은 신고와 결과,
 * 사장님 답변, '맞음'이면 금액 정정(D22) · 늦은 방문 처리(D23). 기준: 07-screens O-13, FR-STL-08 · 09, CONTRACTS_API.md C23 · D22 · D23.
 * '예약' 탭 안 화면으로 두고 O-01에서 진입한다. 알림함(O-10) · 예약 상세(O-13)가 생기면 이 요소를 붙이기만 하면 된다.
 */
export default function OwnerReportsPage() {
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', items: [], total: 0, error: null });
  const [extra, setExtra] = useState([]);
  const [notice, setNotice] = useState(null);
  const state = result.key === reloadCount ? result : { status: 'loading', items: [], total: 0, error: null };

  useEffect(() => {
    let active = true;
    getOwnerVisitReports({ page: 0, size: PAGE_SIZE })
      .then((page) => {
        if (active) {
          setExtra([]);
          setResult({ key: reloadCount, status: 'ready', items: page.items, total: page.totalElements, error: null });
        }
      })
      .catch((error) => {
        if (active) setResult({ key: reloadCount, status: 'error', items: [], total: 0, error });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  const changed = (message) => {
    setNotice(message);
    setReloadCount((count) => count + 1);
  };

  async function loadMore() {
    const shown = state.items.length + extra.length;
    const page = await getOwnerVisitReports({ page: Math.floor(shown / PAGE_SIZE), size: PAGE_SIZE });
    setExtra((previous) => [...previous, ...page.items]);
  }

  const reports = [...state.items, ...extra];
  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>신고 확인</h1>
        <p className={styles.lead}>손님이 낸 신고를 상인회가 확인해 달라고 보낸 것이에요. 답변을 남기면 상인회가 보고 결과를 정해요.</p>
      </header>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      {state.status === 'loading' && <p role="status">신고를 불러오고 있어요.</p>}
      {state.status === 'error' && (
        <section className={styles.card} role="alert">
          <p>신고를 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={() => setReloadCount((count) => count + 1)}>
            다시 불러오기
          </Button>
        </section>
      )}
      {state.status === 'ready' &&
        (reports.length === 0 ? (
          <p className={styles.hint}>확인 요청을 받은 신고가 없어요.</p>
        ) : (
          <ul className={styles.cards} aria-label="확인 요청을 받은 신고(최근 순)">
            {reports.map((report) => (
              <li key={report.id}>
                <ReportCard report={report} onChanged={changed} />
              </li>
            ))}
          </ul>
        ))}
      {state.status === 'ready' && reports.length < state.total && (
        <Button variant="secondary" onClick={loadMore}>
          더 보기
        </Button>
      )}

      <nav className={styles.toolbar} aria-label="예약 관리 바로가기">
        <Link to="/owner">오늘 예약 보기 ›</Link>
      </nav>
    </main>
  );
}

const STATUS_TONE = { owner_check_requested: 'requested', done: 'visited' };

function ReportCard({ report, onChanged }) {
  const valid = report.status === 'done' && report.result === 'valid';
  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <h3 className={styles.cardTitle}>{VISIT_REPORT_TYPE_LABELS[report.type] ?? report.type}</h3>
        <Badge tone={STATUS_TONE[report.status] ?? 'neutral'}>
          {report.status === 'done' ? `결과 · ${VISIT_REPORT_RESULT_LABELS[report.result] ?? ''}` : VISIT_REPORT_STATUS_LABELS[report.status]}
        </Badge>
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>예약 번호</dt>
          <dd>{report.reservationNo}</dd>
        </div>
        <div>
          <dt>방문 일시</dt>
          <dd>{formatDateTime(report.startAt)}</dd>
        </div>
        <div>
          <dt>정산 달</dt>
          <dd>{formatMonth(report.month)}</dd>
        </div>
        <div>
          <dt>내가 넣은 금액</dt>
          <dd>{report.visitAmount == null ? '방문 처리 안 됨(자동 취소)' : formatWon(report.visitAmount)}</dd>
        </div>
        {report.type === 'amount' && (
          <div>
            <dt>손님 신고 금액</dt>
            <dd>{report.claimedAmount == null ? '적지 않음' : formatWon(report.claimedAmount)}</dd>
          </div>
        )}
        <div>
          <dt>내 답변</dt>
          <dd>{report.ownerReply ?? '아직 없음'}</dd>
        </div>
      </dl>

      {report.status === 'owner_check_requested' && <ReplyForm report={report} onChanged={onChanged} />}
      {report.status === 'done' && report.result === 'invalid' && <p className={styles.hint}>상인회가 '아님'으로 기록했어요. 금액 · 예약은 그대로예요.</p>}
      {valid && report.type === 'amount' && <CorrectionForm report={report} onChanged={onChanged} />}
      {valid && report.type === 'not_processed' && <LateVisitForm report={report} onChanged={onChanged} />}
    </article>
  );
}

/** 사장님 답변(C23): 확인 요청인 동안 1 ~ 200자, 다시 보내면 고쳐 쓴다. 상인회가 본다 */
function ReplyForm({ report, onChanged }) {
  const id = useId();
  const [reply, setReply] = useState(report.ownerReply ?? '');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    const trimmed = reply.trim();
    if (trimmed.length < 1 || trimmed.length > REPLY_MAX_LENGTH) {
      setError(`답변을 1 ~ ${REPLY_MAX_LENGTH}자로 넣어 주세요.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await replyToVisitReport(report.id, trimmed);
      onChanged(`${report.reservationNo} 신고에 답변을 남겼어요. 상인회가 보고 결과를 정해요.`);
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onChanged(`${report.reservationNo} 신고는 이미 처리됐어요. 목록을 다시 불러왔어요.`);
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '답변을 남기지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <form className={styles.confirmBox} onSubmit={submit} noValidate>
      <TextField
        id={`${id}-reply`}
        label={report.ownerReply ? '답변 고치기' : '답변'}
        value={reply}
        maxLength={REPLY_MAX_LENGTH}
        onChange={(event) => setReply(event.target.value)}
        hint={`상인회가 확인할 때 봐요. ${REPLY_MAX_LENGTH}자까지, 결과가 나기 전까지 고칠 수 있어요.`}
        error={error}
      />
      <Button type="submit" disabled={busy}>
        {busy ? '보내는 중' : report.ownerReply ? '답변 고치기' : '답변 보내기'}
      </Button>
    </form>
  );
}

const parseAmount = (text) => {
  const trimmed = text.replaceAll(',', '').trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const amount = Number(trimmed);
  return amount <= 10_000_000 ? amount : null;
};

/**
 * 금액 정정(D22, FR-STL-09 #1 · #3): 원래 금액 · 수수료는 그대로 두고 정정 금액과 수수료 차액이 추가로 남는다.
 * 결과는 원래 방문 달 정산에 들어간다. 신고 한 건에 정정 한 번.
 */
function CorrectionForm({ report, onChanged }) {
  const id = useId();
  const [text, setText] = useState(report.claimedAmount != null ? String(report.claimedAmount) : '');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    const amount = parseAmount(text);
    if (amount === null) {
      setError('0 ~ 10,000,000원 사이로 넣어 주세요.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await correctVisitAmount(report.reservationId, amount);
      const fee = result.feeDifference === 0 ? '수수료 차액 없음' : `수수료 차액 ${formatSignedWon(result.feeDifference)}`;
      onChanged(
        `${report.reservationNo} 금액을 ${formatWon(result.originalAmount)} → ${formatWon(result.correctedAmount)}으로 정정했어요 · ${fee} · ${formatMonth(result.settlementMonth)} 정산에 들어가요.`,
      );
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'conflict') setError('이 신고로는 이미 정정했어요. 신고 한 건에 정정은 한 번이에요.');
      else if (caught instanceof ApiError && caught.code === 'insufficient_balance') setError('공동 기금이 모자라 줄어든 수수료를 돌려받을 수 없어요. 상인회에 알려 주세요.');
      else setError(caught instanceof ApiError ? caught.message : '정정하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <form className={styles.confirmBox} onSubmit={submit} noValidate>
      <p className={styles.warning}>
        상인회가 '맞음'으로 기록했어요. 금액을 정정해 주세요. 원래 금액 · 수수료는 그대로 두고, 정정 금액과 수수료 차액이 추가로 남아 {formatMonth(report.month)} 정산에 들어가요.
      </p>
      <TextField id={`${id}-amount`} label="정정 금액(원, 쿠폰 할인 전)" inputMode="numeric" value={text} onChange={(event) => setText(event.target.value)} error={error} />
      <Button type="submit" disabled={busy}>
        {busy ? '정정 중' : '금액 정정'}
      </Button>
    </form>
  );
}

/**
 * 늦은 방문 처리(D23, FR-STL-09 #2 · #5): 자동 취소된 예약을 방문으로 바꾼다. 원래 예약 달 정산에 들어가고,
 * 적용 취소됐던 쿠폰은 다시 예약에 적용된다(사용 확인은 따로). 예약금은 움직이지 않는다.
 */
function LateVisitForm({ report, onChanged }) {
  const id = useId();
  const [text, setText] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    const amount = parseAmount(text);
    if (amount === null) {
      setError('0 ~ 10,000,000원 사이로 넣어 주세요.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await processLateVisit(report.reservationId, amount);
      const fee = result.referralConfirmed ? `소개 확정 · 수수료 ${formatWon(result.referralFee ?? 0)}` : '소개 수수료 없음';
      onChanged(`${report.reservationNo} 예약을 늦게 방문 처리했어요 · 방문 금액 ${formatWon(amount)} · ${fee} · ${formatMonth(report.month)} 정산에 들어가요.`);
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'conflict') setError('이미 방문 처리했거나 늦은 방문 처리를 할 수 없는 예약이에요.');
      else setError(caught instanceof ApiError ? caught.message : '처리하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <form className={styles.confirmBox} onSubmit={submit} noValidate>
      <p className={styles.warning}>
        상인회가 '맞음'으로 기록했어요. 손님이 낸 결제 금액을 넣어 늦게 방문 처리해 주세요. {formatMonth(report.month)} 정산에 들어가고, 적용 취소됐던 쿠폰은 다시 예약에 적용돼요.
      </p>
      <TextField id={`${id}-amount`} label="방문 금액(원, 쿠폰 할인 전)" inputMode="numeric" value={text} onChange={(event) => setText(event.target.value)} error={error} />
      <Button type="submit" disabled={busy}>
        {busy ? '처리 중' : '늦은 방문 처리'}
      </Button>
    </form>
  );
}
