// 역할 값. CONTRACTS.md 「2. 역할」이 아직 TODO라 임시 값이다.
// C2(#24) 계약이 확정되면 이 파일의 문자열만 계약 값으로 맞춘다(화면 코드는 ROLES.* 만 쓴다).
// 비회원 제한 세션(FR-AUTH-03)은 C2 · C13 계약이 나오면 더한다.
export const ROLES = Object.freeze({
  CUSTOMER: 'customer',
  OWNER: 'owner',
  ASSOCIATION: 'association',
});

// 역할마다 처음 보는 화면(07-screens 「메뉴 (하단 탭)」의 첫 탭)
export const ROLE_HOME = Object.freeze({
  [ROLES.CUSTOMER]: '/',
  [ROLES.OWNER]: '/owner',
  [ROLES.ASSOCIATION]: '/association',
});
