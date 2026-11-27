import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/api/client.js';
import {
  getAssociationVisitReports,
  recordVisitReportResult,
  requestOwnerCheck,
  VISIT_REPORT_RESULT_LABELS,
  VISIT_REPORT_STATUS_LABELS,
  VISIT_REPORT_TYPE_LABELS,
} from '@/api/visitReports.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import { formatDateTime, formatMonth, formatWon } from '@/pages/owner/settlement/settlementFormat.js';
import styles from '@/pages/association/coupons/AssociationCoupons.module.css';

const PAGE_SIZE = 20;
const FILTERS = [
  { value: null, label: '전체' },
  { value: 'received', label: '접수' },
  { value: 'owner_check_requested', label: '사장님 확인 요청' },
  { value: 'done', label: '처리 완료' },
];

/**
 * A-11 신고 처리(B28, 번호 임시). 기준: 07-screens A-11, FR-STL-05 · 08 · 09, 결정 11-25 (5), CONTRACTS_API.md C23.
 * 배치는 B가 정한다(11-25 (5)): 처리되지 않은 신고가 있으면 월 정산을 확정할 수 없어 '정산' 탭 안, A-04에서 진입.
 * 신고 건마다 예약 정보만 보여 준다. 손님 이름 · 연락처는 응답에도 없다(FR-STL-08 #5).
 */
export default function VisitReportsPage() {
  const [filter, setFilter] = useState(null);
  const [reloadCount, setReloadCount] = useState(0);
  const requestKey = `${filter ?? 'all'}#${reloadCount}`;
  const [result, setResult] = useState({ key: null, status: 'ready', items: [], total: 0, error: null });
  const [extra, setExtra] = useState([]);
  const [notice, setNotice] = useState(null);
  const state = result.key === requestKey ? result : { status: 'loading', items: [], total: 0, error: null };

  useEffect(() => {
    let active = true;
    const status = requestKey.split('#')[0];
    getAssociationVisitReports({ status: status === 'all' ? undefined : status, page: 0, size: PAGE_SIZE })
      .then((page) => {
        if (active) {
          setExtra([]);
          setResult({ key: requestKey, status: 'ready', items: page.items, total: page.totalElements, error: null });
        }
      })
      .catch((error) => {
        if (active) setResult({ key: requestKey, status: 'error', items: [], total: 0, error });
      });
    return () => {
      active = false;
    };
  }, [requestKey]);

  const reload = () => setReloadCount((count) => count + 1);

  async function loadMore() {
    const shown = state.items.length + extra.length;
    const page = await getAssociationVisitReports({ status: filter ?? undefined, page: Math.floor(shown / PAGE_SIZE), size: PAGE_SIZE });
    setExtra((previous) => [...previous, ...page.items]);
  }

  const reports = [...state.items, ...extra];
  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>신고 처리</h1>
        <p className={styles.lead}>회원이 낸 '금액이 달라요' · '방문했어요' 신고를 사장님에게 확인하고 결과를 기록해요. 허위 신고에 대한 불이익은 없어요.</p>
      </header>

      <p className={styles.warning}>
        신고는 그 예약 달의 월 정산을 확정하기 전에 처리해야 해요. 처리되지 않은 신고가 있는 달은 확정할 수 없어요.{' '}
        <Link to="/association/settlement">월 정산 ›</Link>
      </p>

      {notice && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}

      <div className={styles.choices} role="group" aria-label="신고 상태">
        {FILTERS.map((item) => (
          <label key={item.label} className={styles.choice}>
            <input type="radio" name="report-filter" checked={filter === item.value} onChange={() => setFilter(item.value)} />
            {item.label}
          </label>
        ))}
      </div>

      {state.status === 'loading' && <p role="status">신고를 불러오고 있어요.</p>}
      {state.status === 'error' && (
        <section className={styles.card} role="alert">
          <p>신고를 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={reload}>
            다시 불러오기
          </Button>
        </section>
      )}
      {state.status === 'ready' &&
        (reports.length === 0 ? (
          <p className={styles.hint}>이 상태의 신고가 없어요.</p>
        ) : (
          <ul className={styles.cards} aria-label="신고 목록(오래된 순)">
            {reports.map((report) => (
              <li key={report.id}>
                <ReportCard
                  report={report}
                  onChanged={(message) => {
                    setNotice(message);
                    reload();
                  }}
                />
              </li>
            ))}
          </ul>
        ))}
      {state.status === 'ready' && reports.length < state.total && (
        <Button variant="secondary" onClick={loadMore}>
          더 보기
        </Button>
      )}
    </main>
  );
}

const STATUS_TONE = { received: 'requested', owner_check_requested: 'confirmed', done: 'visited' };

/** 신고 한 건: 예약 정보만, 사장님 확인 요청 · 결과(맞음 · 아님) 기록 */
function ReportCard({ report, onChanged }) {
  const [confirming, setConfirming] = useState(null); // 'valid' | 'invalid'
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const label = `${report.storeName} ${report.reservationNo}`;

  async function run(action) {
    setBusy(true);
    setError(null);
    try {
      if (action === 'check') {
        await requestOwnerCheck(report.id);
        onChanged(`${label} 신고를 사장님에게 확인 요청했어요. 사장님에게 알림이 가요.`);
      } else {
        await recordVisitReportResult(report.id, action);
        onChanged(
          action === 'valid'
            ? `${label} 신고를 '맞음'으로 기록했어요. 사장님이 ${report.type === 'amount' ? '금액 정정' : '늦은 방문 처리'}을 하면 ${formatMonth(report.month)} 정산에 들어가요.`
            : `${label} 신고를 '아님'으로 기록했어요. 금액 · 예약은 그대로예요.`,
        );
      }
    } catch (caught) {
      if (caught instanceof ApiError && (caught.code === 'conflict' || caught.code === 'not_found')) {
        onChanged(`${label} 신고는 이미 처리됐거나 바뀌었어요. 목록을 다시 불러왔어요.`);
        return;
      }
      setError(caught instanceof ApiError ? caught.message : '처리하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <article className={styles.card}>
      <div className={styles.cardHead}>
        <h3 className={styles.cardTitle}>{VISIT_REPORT_TYPE_LABELS[report.type] ?? report.type}</h3>
        <Badge tone={STATUS_TONE[report.status] ?? 'neutral'}>
          {report.status === 'done' ? `처리 완료 · ${VISIT_REPORT_RESULT_LABELS[report.result] ?? ''}` : VISIT_REPORT_STATUS_LABELS[report.status]}
        </Badge>
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>가게</dt>
          <dd>{report.storeName}</dd>
        </div>
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
          <dt>사장님 금액</dt>
          <dd>{report.visitAmount == null ? '방문 처리 안 됨' : formatWon(report.visitAmount)}</dd>
        </div>
        {report.type === 'amount' && (
          <div>
            <dt>손님 신고 금액</dt>
            <dd>{report.claimedAmount == null ? '적지 않음' : formatWon(report.claimedAmount)}</dd>
          </div>
        )}
        <div>
          <dt>사장님 답변</dt>
          <dd>{report.ownerReply ?? (report.status === 'received' ? '확인 요청 전' : '아직 없음')}</dd>
        </div>
      </dl>
      {report.status === 'done' && report.result === 'valid' && (
        <p className={styles.hint}>
          사장님이 {report.type === 'amount' ? '금액을 정정' : '늦은 방문 처리를'}하면 결과가 {formatMonth(report.month)} 정산에 들어가요.
          {report.type === 'not_processed' && ' 늦은 방문 처리 때 적용 취소됐던 쿠폰은 다시 예약에 적용돼요.'}
        </p>
      )}

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      {report.status === 'received' && (
        <Button onClick={() => run('check')} disabled={busy}>
          사장님에게 확인 요청
        </Button>
      )}
      {report.status === 'owner_check_requested' &&
        (confirming ? (
          <div className={styles.confirmBox}>
            <p className={styles.warning}>'{VISIT_REPORT_RESULT_LABELS[confirming]}'으로 기록할까요? 기록하면 되돌릴 수 없어요.</p>
            <div className={styles.actions}>
              <Button variant="secondary" onClick={() => setConfirming(null)} disabled={busy}>
                돌아가기
              </Button>
              <Button onClick={() => run(confirming)} disabled={busy}>
                기록하기
              </Button>
            </div>
          </div>
        ) : (
          <div className={styles.actions}>
            <Button variant="secondary" onClick={() => setConfirming('invalid')}>
              아님
            </Button>
            <Button onClick={() => setConfirming('valid')}>맞음</Button>
          </div>
        ))}
    </article>
  );
}
