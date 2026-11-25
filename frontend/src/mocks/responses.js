import { HttpResponse } from 'msw/http';
import { getMockAccount } from './session.js';

// 목 핸들러가 같이 쓰는 응답 도구. 모양은 계약(docs/CONTRACTS.md 「1」 · 「4」 · 「6」)을 그대로 따른다.

/** 실패 응답 `{ error: { code, message } }`. code는 CONTRACTS.md 「4. 에러 코드」에 있는 값만 쓴다. */
export function errorResponse(status, code, message) {
  return HttpResponse.json({ error: { code, message } }, { status });
}

/**
 * 상태 변경 요청의 CSRF 검사. 헤더 `X-XSRF-TOKEN`이 쿠키 `XSRF-TOKEN`과 다르면 `403 csrf_invalid`를 돌려주고,
 * 맞으면 null. 상태 변경 API 목은 처음에 이것을 부른다.
 */
export function rejectInvalidCsrf({ request, cookies }) {
  const header = request.headers.get('X-XSRF-TOKEN');
  if (!header || header !== cookies['XSRF-TOKEN']) {
    return errorResponse(403, 'csrf_invalid', 'CSRF 토큰이 없거나 맞지 않아요.');
  }
  return null;
}

/** '로그인한 누구나' API의 검사. 목에서 로그인하지 않았으면 `401 unauthorized`, 했으면 null. */
export function rejectAnonymous() {
  return getMockAccount() ? null : errorResponse(401, 'unauthorized', '로그인이 필요해요.');
}
