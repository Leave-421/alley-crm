import { ROLES } from '@/auth/roles.js';

/**
 * 시연 도구(B20)의 역할 전환이 로그인할 시연 계정.
 *
 * - 아이디는 자리 값이다. 시연용 고정 계정을 시드에 넣는 쪽(C2 상인회 초기 데이터 · C5 사장님 · E16 손님)이
 *   정해지면 이 파일의 loginId만 실제 값으로 바꾼다.
 * - 비밀번호는 코드에 두지 않는다. 로컬 `frontend/.env.development.local`(커밋되지 않음)에
 *   `VITE_DEMO_PASSWORD=시연용 비밀번호`로 넣는다. 목 모드(`npm run dev:mock`)에서는 없어도 된다.
 * - 비회원은 계정이 없어서 로그아웃 상태로 둘러보기만 한다(비회원 예약 조회는 C13).
 */
export const DEMO_PASSWORD = import.meta.env.VITE_DEMO_PASSWORD ?? '';

export const DEMO_ROLES = Object.freeze([
  {
    key: 'customer',
    role: ROLES.CUSTOMER,
    label: '손님 (회원)',
    description: '골목 지도 · 코스 예약 · 쿠폰',
    loginId: 'demo-customer',
  },
  {
    key: 'guest',
    role: null,
    label: '손님 (비회원)',
    description: '로그인 없이 둘러보기 · 비회원 예약',
    loginId: null,
  },
  {
    key: 'owner',
    role: ROLES.OWNER,
    label: '사장님 · 골목카페',
    description: '오늘 예약 · 고객 · 손님 흐름 · 쿠폰',
    loginId: 'demo-owner',
  },
  {
    key: 'association',
    role: ROLES.ASSOCIATION,
    label: '상인회',
    description: '지역 성과 · 방문 흐름 · 공동 기금',
    loginId: 'demo-association',
  },
]);
