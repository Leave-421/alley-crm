# 공통 계약 — 규칙 · Entity · ID · 에러 · 보안

> 상태: 공통 규칙 · 에러 · 인증은 C1(#5)에서 정했다. 역할 · Entity · 상태 이름은 C2 · D1에서 채운다.
> 이 파일과 `CONTRACTS_API.md`가 API 계약의 **유일한 기준**이다. 계약을 바꾸는 PR은 이 문서와 코드를 함께 바꾼다.

## 1. 공통 규칙

- 시각은 UTC, `Z`로 끝나는 ISO 8601.
- API의 ID는 문자열(Entity는 `Long`).
- 에러 응답: `{ "error": { "code": "...", "message": "..." } }`, code는 소문자 snake.

## 2. 역할

| 역할 | 설명 |
| --- | --- |
| 손님 (회원 · 비회원) | TODO |
| 사장님 | TODO |
| 상인회 | TODO |

## 3. Entity · 상태

- 예약 상태: `requested` → `confirmed` → `visited`, 그리고 `canceled` (v1 결정 — 노쇼 상태 없음). TODO 확정

## 4. 에러 코드

| code | HTTP | 언제 |
| --- | --- | --- |
| `invalid` | 400 | 입력 검증 실패 |
| `unauthorized` | 401 | 로그인 안 됨 |
| `forbidden_role` | 403 | 역할 위반 |
| `not_found` | 404 | 없음 · 다른 가게 것(존재 숨김) |
| `conflict` | 409 | 상태 충돌(이미 찬 슬롯 등) |
| `csrf_invalid` | 403 | 상태 변경 요청에 CSRF 토큰이 없거나 맞지 않음 |
| `internal` | 500 | 예상하지 못한 서버 오류(내부 메시지는 내보내지 않음) |

코드 기준: `backend/.../common/error/ErrorCode.java`. 새 code를 더하는 것은 계약 변경이다(이 표와 코드를 같은 PR에서). 없는 경로 · 허용되지 않은 메서드는 `not_found`로 응답한다.

## 5. 보안 검사 순서

로그인 → 골목 접근 → 가게 접근 → 역할 (`.claude/rules/security.md`)

## 6. 인증 · 세션 · CSRF

- 서버 세션 쿠키(Spring Security HttpSession). JWT를 쓰지 않는다. 세션 쿠키는 `HttpOnly` · `SameSite=Lax`, HTTPS에서 `Secure`.
- 기본은 모든 `/api/**`가 로그인 필요다. 로그인 없이 여는 경로는 이 절에 적는다: `GET /api/v1/csrf`, `GET /actuator/health`. (C2 · C3에서 로그인 · 회원가입 · 비회원 조회 · 손님 공개 조회 경로를 더한다.)
- **CSRF:** 앱을 처음 열 때 `GET /api/v1/csrf`를 부르면 `XSRF-TOKEN` 쿠키가 내려온다. 상태 변경 요청(`POST` · `PUT` · `PATCH` · `DELETE`)은 그 값을 `X-XSRF-TOKEN` 헤더로 보낸다. 없거나 틀리면 `403 csrf_invalid`.
- 로그인 안 된 요청은 리다이렉트하지 않고 `401 unauthorized` JSON으로 응답한다.
- SSE 연결도 같은 세션 쿠키로 인증한다.
