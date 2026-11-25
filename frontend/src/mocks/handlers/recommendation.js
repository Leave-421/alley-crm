// CONTRACTS_API.md 「3. 추천 · 쿠폰 · 소개 판정 · 시드 (담당 E)」의 목.
// 계약이 main에 머지된 엔드포인트만 더한다. 계약이 없는 API의 목은 지어내지 않는다.
// 상태 변경 API는 mocks/responses.js의 rejectInvalidCsrf로 CSRF를 먼저 검사한다.
export const recommendationHandlers = [];
