import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router';
import { SIGNUP_RULES } from '@/api/auth.js';
import { ApiError } from '@/api/client.js';
import { applyStoreJoin, getMyStoreJoin, getRegions, reapplyStoreJoin } from '@/api/stores.js';
import { ROLES } from '@/auth/roles.js';
import { SESSION_STATUS, useSession } from '@/auth/session.js';
import Badge from '@/components/Badge/Badge.jsx';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import StoreInfoFields from './StoreInfoFields.jsx';
import { EMPTY_STORE_INFO, storeInfoFrom, toStoreInfoRequest, validateStoreInfo } from './storeInfo.js';
import { alleyLabel, categoryLabel, formatHours } from '@/pages/association/stores/storeFormat.js';
import styles from './StoreJoin.module.css';

/**
 * O-11 가게 가입 신청(B5). 기준: 07-screens O-11 · G-08, FR-STO-01 · FR-AUTH-04, CONTRACTS_API.md C5 · GET /regions.
 * - 로그인하지 않았거나 손님이면: 가게 가입 신청(가게 + 사장님 계정을 같이 만든다)
 * - 사장님이면: 내 가입 상태(승인 대기 · 거절 · 승인). 거절이면 고쳐서 다시 신청
 * 승인 전 사장님은 다른 사장님 메뉴가 잠겨 이 화면으로 온다(RequireApprovedStore).
 */
export default function StoreJoinPage() {
  const session = useSession();
  if (session.status === SESSION_STATUS.LOADING) {
    return (
      <main className={styles.page}>
        <p role="status">로그인 상태를 확인하고 있어요.</p>
      </main>
    );
  }
  return session.role === ROLES.OWNER ? <MyStoreJoin /> : <ApplyForm />;
}

const EMPTY_ACCOUNT = Object.freeze({ loginId: '', password: '', passwordConfirm: '' });

function validateAccount(account) {
  const errors = {};
  if (!SIGNUP_RULES.loginIdPattern.test(account.loginId.trim())) errors.loginId = '영문 소문자 · 숫자 4 ~ 30자로 넣어 주세요.';
  if (account.password.length < SIGNUP_RULES.passwordMin || account.password.length > SIGNUP_RULES.passwordMax) {
    errors.password = `비밀번호는 ${SIGNUP_RULES.passwordMin} ~ ${SIGNUP_RULES.passwordMax}자예요.`;
  }
  if (account.passwordConfirm !== account.password) errors.passwordConfirm = '비밀번호가 같지 않아요.';
  return errors;
}

/** 새 가게 가입 신청. 권역은 고르지 않고 GET /regions의 첫 권역을 쓴다(권역 선택 v1 제외). */
function ApplyForm() {
  const id = useId();
  const session = useSession();
  const [account, setAccount] = useState(EMPTY_ACCOUNT);
  const [info, setInfo] = useState(EMPTY_STORE_INFO);
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState(null);
  const [done, setDone] = useState(null);

  const updateAccount = (field) => (event) => setAccount((previous) => ({ ...previous, [field]: event.target.value }));

  async function submit(event) {
    event.preventDefault();
    const found = { ...validateAccount(account), ...validateStoreInfo(info) };
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;

    setSubmitting(true);
    try {
      const regions = await getRegions();
      const regionId = regions.items?.[0]?.id;
      if (!regionId) throw new Error('참여 권역을 찾지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      const join = await applyStoreJoin({ loginId: account.loginId.trim(), password: account.password, regionId, ...toStoreInfoRequest(info) });
      setDone({ loginId: account.loginId.trim(), join });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'conflict') setErrors({ loginId: '이미 쓰는 아이디예요.' });
      else setFailure(caught?.message ?? '신청하지 못했어요.');
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <main className={styles.page}>
        <section className={styles.card} aria-labelledby={`${id}-done`}>
          <h1 id={`${id}-done`} className={styles.title}>
            가입을 신청했어요
          </h1>
          <p className={styles.lead}>
            상인회가 승인하면 사장님 메뉴를 쓸 수 있어요. {done.loginId} 아이디로 로그인하면 승인 상태를 볼 수 있어요.
          </p>
          <JoinSummary join={done.join} />
          <div className={styles.actions}>
            <Link to="/login" className={styles.linkButton}>
              로그인하러 가기
            </Link>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>가게 가입 신청</h1>
        <p className={styles.lead}>신청하면 가게 계정이 만들어지고, 우리 골목 상인회가 확인한 뒤 승인해요.</p>
      </header>

      <form className={styles.card} onSubmit={submit} noValidate aria-labelledby={`${id}-title`}>
        {session.status === SESSION_STATUS.AUTHENTICATED && (
          <p className={styles.notice}>지금 로그인한 계정과 따로, 가게용 새 계정을 만들어요.</p>
        )}
        {failure && (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        )}

        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          가게 계정
        </h2>
        <TextField
          id={`${id}-loginId`}
          label="아이디"
          hint="영문 소문자 · 숫자 4 ~ 30자. 가게당 계정 하나예요."
          autoComplete="username"
          autoCapitalize="none"
          value={account.loginId}
          error={errors.loginId}
          onChange={updateAccount('loginId')}
        />
        <TextField
          id={`${id}-password`}
          label="비밀번호"
          hint={`${SIGNUP_RULES.passwordMin} ~ ${SIGNUP_RULES.passwordMax}자`}
          type="password"
          autoComplete="new-password"
          value={account.password}
          error={errors.password}
          onChange={updateAccount('password')}
        />
        <TextField
          id={`${id}-passwordConfirm`}
          label="비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={account.passwordConfirm}
          error={errors.passwordConfirm}
          onChange={updateAccount('passwordConfirm')}
        />

        <h2 className={styles.sectionTitle}>가게 정보</h2>
        <StoreInfoFields id={id} info={info} errors={errors} onChange={setInfo} />

        <Button type="submit" disabled={submitting} block>
          {submitting ? '신청하는 중' : '가입 신청하기'}
        </Button>
      </form>

      <p className={styles.links}>
        이미 신청했나요? <Link to="/login">가게 계정으로 로그인</Link>
      </p>
    </main>
  );
}

/** 사장님의 내 가입 상태. 거절이면 고쳐서 다시 신청한다(받아들일지는 상인회가 정함). */
function MyStoreJoin() {
  const id = useId();
  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState({ key: null, status: 'ready', join: null, error: null });
  const state = result.key === reloadCount ? result : { status: 'loading', join: null, error: null };

  useEffect(() => {
    let active = true;
    getMyStoreJoin()
      .then((join) => {
        if (active) setResult({ key: reloadCount, status: 'ready', join, error: null });
      })
      .catch((error) => {
        if (active) setResult({ key: reloadCount, status: 'error', join: null, error });
      });
    return () => {
      active = false;
    };
  }, [reloadCount]);

  if (state.status === 'loading') {
    return (
      <main className={styles.page}>
        <p role="status">가입 상태를 불러오고 있어요.</p>
      </main>
    );
  }
  if (state.status === 'error') {
    return (
      <main className={styles.page}>
        <section className={styles.card} role="alert">
          <p>가입 상태를 불러오지 못했어요. {state.error?.message}</p>
          <Button variant="secondary" onClick={() => setReloadCount((count) => count + 1)}>
            다시 불러오기
          </Button>
        </section>
      </main>
    );
  }

  const { join } = state;
  const approved = join.joinStatus === 'approved' || join.joinStatus === 'withdraw_requested';
  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>가게 가입 상태</h1>
      </header>
      <section className={styles.card} aria-labelledby={`${id}-status`}>
        <div className={styles.statusHead}>
          <h2 id={`${id}-status`} className={styles.sectionTitle}>
            {join.name}
          </h2>
          <JoinStatusBadge status={join.joinStatus} />
        </div>
        {join.joinStatus === 'requested' && (
          <p className={styles.lead}>상인회가 확인하고 있어요. 승인되면 알림이 가고, 그 전에는 다른 사장님 메뉴를 쓸 수 없어요.</p>
        )}
        {join.joinStatus === 'rejected' && <p className={styles.lead}>상인회가 가입을 거절했어요. 가게 정보를 고쳐 다시 신청할 수 있어요.</p>}
        {approved && (
          <>
            <p className={styles.lead}>승인됐어요. 이제 사장님 메뉴를 모두 쓸 수 있어요.</p>
            <Link to="/owner" className={styles.linkButton}>
              예약 관리로 가기
            </Link>
          </>
        )}
        <JoinSummary join={join} />
      </section>
      {join.joinStatus === 'rejected' && <ReapplyForm join={join} onReapplied={(next) => setResult({ key: reloadCount, status: 'ready', join: next, error: null })} />}
    </main>
  );
}

function ReapplyForm({ join, onReapplied }) {
  const id = useId();
  const [info, setInfo] = useState(() => storeInfoFrom(join));
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState(null);

  async function submit(event) {
    event.preventDefault();
    const found = validateStoreInfo(info);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;
    setSubmitting(true);
    try {
      onReapplied(await reapplyStoreJoin(toStoreInfoRequest(info)));
    } catch (caught) {
      setFailure(caught instanceof ApiError ? caught.message : '다시 신청하지 못했어요.');
      setSubmitting(false);
    }
  }

  return (
    <form className={styles.card} onSubmit={submit} noValidate aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={styles.sectionTitle}>
        고쳐서 다시 신청
      </h2>
      {failure && (
        <p className={styles.failure} role="alert">
          {failure}
        </p>
      )}
      <StoreInfoFields id={id} info={info} errors={errors} onChange={setInfo} />
      <Button type="submit" disabled={submitting} block>
        {submitting ? '신청하는 중' : '다시 신청하기'}
      </Button>
    </form>
  );
}

const JOIN_STATUS_VIEW = {
  requested: { tone: 'requested', label: '승인 대기' },
  rejected: { tone: 'canceled', label: '거절' },
  approved: { tone: 'confirmed', label: '승인' },
  withdraw_requested: { tone: 'confirmed', label: '승인 · 탈퇴 신청' },
  withdrawn: { tone: 'canceled', label: '탈퇴' },
};

function JoinStatusBadge({ status }) {
  const view = JOIN_STATUS_VIEW[status] ?? { tone: 'neutral', label: status };
  return <Badge tone={view.tone}>{view.label}</Badge>;
}

function JoinSummary({ join }) {
  return (
    <dl className={styles.summary}>
      <div>
        <dt>가게</dt>
        <dd>{join.name}</dd>
      </div>
      <div>
        <dt>업종 · 골목</dt>
        <dd>
          {categoryLabel(join.category)} · {alleyLabel(join.alley)}
        </dd>
      </div>
      <div>
        <dt>영업</dt>
        <dd>{formatHours(join)}</dd>
      </div>
    </dl>
  );
}
