import { useId, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { login } from '@/api/auth.js';
import { ApiError } from '@/api/client.js';
import { afterLoginPath } from '@/auth/redirect.js';
import { SESSION_STATUS, sessionFromAccount, useSession } from '@/auth/session.js';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import styles from './Auth.module.css';

/**
 * G-08 로그인(B4). 손님 · 사장님 · 상인회가 같은 로그인을 쓰고, 역할에 따라 각자 첫 화면으로 간다(FR-AUTH-01 · 05).
 * `?next=`가 있으면 그 화면으로 돌아간다(앱 안 경로만, auth/redirect.js).
 */
export default function LoginPage() {
  const id = useId();
  const session = useSession();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const next = searchParams.get('next');
  const [form, setForm] = useState({ loginId: '', password: '' });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState(null);

  if (session.status === SESSION_STATUS.AUTHENTICATED && !submitting) {
    return <Navigate to={afterLoginPath(next, session.role)} replace />;
  }

  function update(field, value) {
    setForm((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  }

  async function submit(event) {
    event.preventDefault();
    const found = {};
    if (!form.loginId.trim()) found.loginId = '아이디를 넣어 주세요.';
    if (!form.password) found.password = '비밀번호를 넣어 주세요.';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;

    setSubmitting(true);
    try {
      const account = await login(form.loginId.trim(), form.password);
      session.setSession(sessionFromAccount(account));
      navigate(afterLoginPath(next, account.role), { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setFailure('아이디 또는 비밀번호가 맞지 않아요.');
      } else {
        setFailure(error instanceof ApiError ? error.message : '로그인하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      }
      setSubmitting(false);
    }
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>로그인</h1>
        <p className={styles.lead}>손님 · 사장님 · 상인회 모두 여기서 로그인해요. 예약은 비회원도 할 수 있지만, 추천 쿠폰은 회원만 받을 수 있어요.</p>
      </header>

      <form className={styles.card} onSubmit={submit} noValidate aria-labelledby={`${id}-title`}>
        <h2 id={`${id}-title`} className={styles.visuallyHidden}>
          아이디로 로그인
        </h2>
        {failure && (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        )}
        <TextField
          id={`${id}-loginId`}
          label="아이디"
          autoComplete="username"
          autoCapitalize="none"
          value={form.loginId}
          error={errors.loginId}
          onChange={(event) => update('loginId', event.target.value)}
        />
        <TextField
          id={`${id}-password`}
          label="비밀번호"
          type="password"
          autoComplete="current-password"
          value={form.password}
          error={errors.password}
          onChange={(event) => update('password', event.target.value)}
        />
        <Button type="submit" block disabled={submitting}>
          {submitting ? '로그인하는 중…' : '로그인'}
        </Button>
      </form>

      <nav className={styles.links} aria-label="다른 방법">
        <Link to="/signup">회원가입</Link>
        <Link to="/guest-reservation">비회원 예약 조회</Link>
        <Link to="/">비회원으로 둘러보기</Link>
        <Link to="/demo">시연 계정으로 로그인 (시연 도구)</Link>
      </nav>
    </main>
  );
}
