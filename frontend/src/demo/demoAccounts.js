import { ROLES } from '@/auth/roles.js';

/**
 * 시연 도구(B20)의 역할 전환이 로그인할 시연 계정.
 *
 * - 아이디는 시드(E1 시드 실행기 ③ 시연 계정, #183 `DemoAccountSeeder`)와 같다.
 * - 비밀번호는 코드에 두지 않는다. 시드가 역할마다 따로 받으므로(루트 `.env`의 `DEMO_*_PASSWORD`),
 *   프론트도 로컬 `frontend/.env.development.local`(커밋되지 않음)에 같은 값을 역할별로 넣는다.
 *     VITE_DEMO_CUSTOMER_PASSWORD · VITE_DEMO_OWNER_PASSWORD · VITE_DEMO_ASSOCIATION_PASSWORD
 *   목 모드(`npm run dev:mock`)에서는 없어도 된다.
 * - 사장님 계정의 가게는 시드 쪽 잠정값이라 화면 글자에 가게 이름을 넣지 않는다.
 * - 비회원은 계정이 없어서 로그아웃 상태로 둘러보기만 한다(비회원 예약 조회는 C13).
 */
const env = import.meta.env;

export const DEMO_ROLES = Object.freeze([
  {
    key: 'customer',
    role: ROLES.CUSTOMER,
    label: '손님 (회원)',
    description: '골목 지도 · 코스 예약 · 쿠폰',
    loginId: 'democustomer',
    password: env.VITE_DEMO_CUSTOMER_PASSWORD ?? '',
    passwordEnv: 'VITE_DEMO_CUSTOMER_PASSWORD',
  },
  {
    key: 'guest',
    role: null,
    label: '손님 (비회원)',
    description: '로그인 없이 둘러보기 · 비회원 예약',
    loginId: null,
    password: null,
    passwordEnv: null,
  },
  {
    key: 'owner',
    role: ROLES.OWNER,
    label: '사장님',
    description: '오늘 예약 · 고객 · 손님 흐름 · 쿠폰',
    loginId: 'demoowner',
    password: env.VITE_DEMO_OWNER_PASSWORD ?? '',
    passwordEnv: 'VITE_DEMO_OWNER_PASSWORD',
  },
  {
    key: 'association',
    role: ROLES.ASSOCIATION,
    label: '상인회',
    description: '지역 성과 · 방문 흐름 · 공동 기금',
    loginId: 'demoassoc',
    password: env.VITE_DEMO_ASSOCIATION_PASSWORD ?? '',
    passwordEnv: 'VITE_DEMO_ASSOCIATION_PASSWORD',
  },
]);
