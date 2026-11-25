import { useId, useState } from 'react';
import { Link } from 'react-router';
import { AGE_GROUPS, GENDERS, SIGNUP_RULES, signup } from '@/api/auth.js';
import { ApiError } from '@/api/client.js';
import Button from '@/components/Button/Button.jsx';
import TextField from '@/components/TextField/TextField.jsx';
import styles from './Auth.module.css';

const EMPTY = { loginId: '', password: '', passwordConfirm: '', name: '', gender: '', ageGroup: '' };

// 목업의 필수 동의 2가지(07-screens G-09). 회원가입 계약(C2)에는 동의 칸이 없어서 화면에서 체크해야 가입할 수 있게만 한다.
// 가게 간 정보 공유 동의의 기록은 예약할 때마다 남긴다(FR-CUS-01).
const CONSENTS = [
  { key: 'deliver', text: '[필수] 예약에 필요한 이름 · 연락처를 예약한 가게에 전달해요' },
  { key: 'share', text: '[필수] 가게 간 정보 공유에 동의해요 (방문한 가게 목록 포함). 동의하지 않으면 예약할 수 없어요' },
];

/** G-09 회원가입(B4). 손님 회원만 만든다. 사장님은 가게 가입 신청(O-11), 상인회는 가입 화면이 없다(FR-AUTH-05). */
export default function SignupPage() {
  const id = useId();
  const [form, setForm] = useState(EMPTY);
  const [consents, setConsents] = useState({ deliver: false, share: false });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState(null);
  const [done, setDone] = useState(null);

  const allConsented = CONSENTS.every((item) => consents[item.key]);

  function update(field, value) {
    setForm((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  }

  async function submit(event) {
    event.preventDefault();
    const found = validate(form);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0 || !allConsented) return;

    setSubmitting(true);
    try {
      const account = await signup({
        loginId: form.loginId,
        password: form.password,
        name: form.name.trim(),
        gender: form.gender || null,
        ageGroup: form.ageGroup || null,
      });
      setDone(account);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'conflict') {
        setErrors({ loginId: '이미 쓰는 아이디예요. 다른 아이디를 넣어 주세요.' });
      } else {
        setFailure(error instanceof ApiError ? error.message : '가입하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <main className={styles.page}>
        <section className={styles.card} aria-labelledby={`${id}-done`}>
          <h1 id={`${id}-done`} className={styles.title}>
            가입했어요
          </h1>
          <p className={styles.lead}>
            {done.loginId} 아이디로 로그인하면 추천 쿠폰을 받고 예약 내역을 한곳에서 볼 수 있어요.
          </p>
          <div className={styles.actions}>
            <Link to="/login" className={styles.linkButton}>
              로그인하러 가기
            </Link>
            <Link to="/">홈으로 가기</Link>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <header className={styles.intro}>
        <h1 className={styles.title}>회원가입</h1>
        <p className={styles.lead}>회원이 되면 추천 쿠폰을 받고, 예약 내역을 한곳에서 볼 수 있어요.</p>
      </header>

      <form className={styles.card} onSubmit={submit} noValidate aria-labelledby={`${id}-title`}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          손님 회원가입
        </h2>
        {failure && (
          <p className={styles.failure} role="alert">
            {failure}
          </p>
        )}

        <TextField
          id={`${id}-loginId`}
          label="아이디"
          hint="영문 소문자 · 숫자 4 ~ 30자"
          autoComplete="username"
          autoCapitalize="none"
          value={form.loginId}
          error={errors.loginId}
          onChange={(event) => update('loginId', event.target.value)}
        />
        <TextField
          id={`${id}-password`}
          label="비밀번호"
          hint={`${SIGNUP_RULES.passwordMin} ~ ${SIGNUP_RULES.passwordMax}자`}
          type="password"
          autoComplete="new-password"
          value={form.password}
          error={errors.password}
          onChange={(event) => update('password', event.target.value)}
        />
        <TextField
          id={`${id}-passwordConfirm`}
          label="비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={form.passwordConfirm}
          error={errors.passwordConfirm}
          onChange={(event) => update('passwordConfirm', event.target.value)}
        />
        <TextField
          id={`${id}-name`}
          label="이름"
          autoComplete="name"
          maxLength={SIGNUP_RULES.nameMax}
          value={form.name}
          error={errors.name}
          onChange={(event) => update('name', event.target.value)}
        />

        <ChoiceGroup
          name={`${id}-gender`}
          legend="성별 (선택)"
          options={[...GENDERS, { value: '', label: '응답 안 함' }]}
          value={form.gender}
          onChange={(value) => update('gender', value)}
        />
        <ChoiceGroup
          name={`${id}-ageGroup`}
          legend="연령대 (선택)"
          options={[...AGE_GROUPS, { value: '', label: '응답 안 함' }]}
          value={form.ageGroup}
          onChange={(value) => update('ageGroup', value)}
        />
        <p className={styles.notice}>
          성별 · 연령대는 예약할 때 자동으로 쓰여요. 가게별 익명 통계에만 쓰이고, 5명 미만 집단은 숨겨서 개인은 알아볼 수 없어요.
        </p>

        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>필수 동의</legend>
          {CONSENTS.map((item) => (
            <label key={item.key} className={styles.consent}>
              <input
                type="checkbox"
                checked={consents[item.key]}
                onChange={(event) => setConsents((previous) => ({ ...previous, [item.key]: event.target.checked }))}
              />
              <span>{item.text}</span>
            </label>
          ))}
          {!allConsented && <p className={styles.hint}>필수 동의 2가지를 모두 체크해야 가입할 수 있어요.</p>}
        </fieldset>

        <Button type="submit" block disabled={submitting || !allConsented}>
          {submitting ? '가입하는 중…' : '가입하기'}
        </Button>
      </form>

      <section className={styles.ownerBox} aria-label="사장님 가게 가입">
        <strong>사장님이신가요?</strong>
        <span>가게 계정은 손님 회원가입과 따로, 가게 가입 신청으로 만들어요. 상인회가 승인하면 쓸 수 있어요.</span>
        <Link to="/store-join">가게 가입 신청하기</Link>
      </section>

      <nav className={styles.links} aria-label="다른 화면">
        <Link to="/login">로그인으로 돌아가기</Link>
        <Link to="/">홈으로 가기</Link>
      </nav>
    </main>
  );
}

function ChoiceGroup({ name, legend, options, value, onChange }) {
  return (
    <fieldset className={styles.fieldset}>
      <legend className={styles.legend}>{legend}</legend>
      <div className={styles.choices}>
        {options.map((option) => (
          <label key={option.value || 'none'} className={styles.choice}>
            <input type="radio" name={name} value={option.value} checked={value === option.value} onChange={() => onChange(option.value)} />
            <span>{option.label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/** C2 계약 「POST /api/v1/auth/signup」 규칙을 화면에서 미리 검사한다. 최종 판단은 서버다. */
function validate(form) {
  const errors = {};
  if (!SIGNUP_RULES.loginIdPattern.test(form.loginId)) errors.loginId = '아이디는 영문 소문자 · 숫자 4 ~ 30자로 넣어 주세요.';
  if (form.password.length < SIGNUP_RULES.passwordMin || form.password.length > SIGNUP_RULES.passwordMax) {
    errors.password = `비밀번호는 ${SIGNUP_RULES.passwordMin} ~ ${SIGNUP_RULES.passwordMax}자로 넣어 주세요.`;
  }
  if (form.passwordConfirm !== form.password) errors.passwordConfirm = '비밀번호가 서로 달라요.';
  const name = form.name.trim();
  if (name.length < 1 || name.length > SIGNUP_RULES.nameMax) errors.name = `이름을 1 ~ ${SIGNUP_RULES.nameMax}자로 넣어 주세요.`;
  return errors;
}
