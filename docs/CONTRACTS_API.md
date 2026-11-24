# API 계약 — 엔드포인트 · DTO

> 상태: 공통 규칙과 영역별 절은 C1(#5)에서 나눴다. 각 담당은 **자기 영역 절만** 고친다(기획 저장소 `docs/00-backlog.md` 「같이 쓰는 것 규칙」).
> API를 만들거나 바꾸는 PR은 이 문서의 해당 절을 같은 PR에서 고친다. 프론트 목 서버는 이 문서를 그대로 따른다.
> 공통 규칙(에러 · 인증 · CSRF · 시각 · ID)은 [`CONTRACTS.md`](CONTRACTS.md).

## 0. 공통

### 쓰는 법

- 엔드포인트마다 `메서드 경로` · 역할 · 요청 · 응답 · 실패 코드를 적는다.
- 경로는 `/api/v1/...`, 리소스 이름은 복수형 kebab-case(`/api/v1/reservations`, `/api/v1/coupon-events`).
- JSON 필드는 camelCase. 시각은 UTC ISO 8601(`2026-12-01T09:00:00Z`), 날짜만이면 `2026-12-01`. ID는 문자열.
- 금액은 원 단위 정수(`5000`). 비율은 소수(`0.04`).
- 상태 · 사유 같은 enum 값은 소문자 snake. 값 목록은 `CONTRACTS.md` 「3. Entity · 상태」 한 곳에만 둔다(D1).
- 상태 변경 요청은 `X-XSRF-TOKEN` 헤더가 필요하다(`CONTRACTS.md` 「6」).

### 페이지네이션

- 목록은 `?page=0&size=20`(page는 0부터, size 최대 100).
- 응답: `{ "items": [ ... ], "page": 0, "size": 20, "totalElements": 135, "totalPages": 7 }`
- 정렬이 필요하면 `?sort=createdAt,desc`처럼 엔드포인트 절에 허용 값을 적는다.

### 실패 응답

- 모두 `{ "error": { "code": "...", "message": "..." } }`. 엔드포인트 절에는 나올 수 있는 code만 적는다.

### `GET /api/v1/csrf` — CSRF 토큰 받기 (C1)

- 역할: 누구나(로그인 불필요)
- 응답 `200`: `{ "headerName": "X-XSRF-TOKEN", "token": "string" }` + `XSRF-TOKEN` 쿠키
- 프론트는 앱 시작 때 한 번 부르고, 이후 쿠키 값을 헤더로 보낸다.

### `GET /actuator/health` — 헬스체크 (C1)

- 역할: 누구나. 응답 `200`: `{ "status": "UP" }`

## 1. 계정 · 가게 · 손님 · 알림 · 분석 (담당 C)

C2 ~ C21. 로그인 · 회원가입 · 역할 · 비회원 조회, 가게 가입 · 목록, 손님 식별 · 공유 동의 · 고객 카드, 알림 · SSE, 분석 지표.

(아직 없음)

## 2. 예약 · 잔액 · 수수료 · 정산 · 서버 시계 (담당 D)

D2 ~ D18. 예약 신청 · 상태 · 승인 · 취소 · 변경 · 코스 예약 · 방문 처리, 가상 잔액 · 예약금, 수수료 · 정산, 시연용 시각 조작.
'방문 처리됨' 이벤트의 이름 · 내용도 이 절에 적는다(D11).

### 시연용 시각 조작 (D18)

`app.demo-tools.enabled=true`(환경 변수 `DEMO_TOOLS_ENABLED=true`)일 때만 있다. 기본은 꺼져 있고, 꺼져 있으면 `404 not_found`. 실제 운영에서는 켜지 않는다. 서버의 모든 시간 규칙(자동 거절 · 자동 취소 · 쿠폰 만료 · 리마인드 · 파기)이 이 시계를 따른다.

응답 `ClockResponse`: `{ "now": "2026-11-28T09:00:00Z", "offsetSeconds": 86400 }` — 지금 서버 시각과 실제 시각과의 차이(초)

#### `GET /api/v1/demo/clock` — 지금 서버 시각

- 역할: 로그인한 누구나(시연 도구)
- 응답 `200`: `ClockResponse`

#### `POST /api/v1/demo/clock` — 시각 앞당기기 · 맞추기

- 역할: 로그인한 누구나(시연 도구)
- 요청: `{ "advanceMinutes": 1440 }`(음수면 되돌림) 또는 `{ "at": "2026-12-01T00:00:00Z" }` — 둘 중 하나만
- 응답 `200`: `ClockResponse`
- 실패: `invalid`(둘 다 있거나 둘 다 없음) · `unauthorized` · `csrf_invalid`

#### `DELETE /api/v1/demo/clock` — 실제 시각으로 되돌리기

- 역할: 로그인한 누구나(시연 도구)
- 응답 `200`: `ClockResponse`(`offsetSeconds: 0`)
- 실패: `unauthorized` · `csrf_invalid`

예시 형식(D2에서 확정):

### `POST /api/v1/reservations` — 예약 신청

- 역할: 손님
- 요청: `{ "storeId": "string", "slotStart": "2026-12-01T09:00:00Z", "partySize": 2, "shareConsent": true }`
- 응답 `201`: `{ "id": "string", "status": "requested" }`
- 실패: `invalid` · `conflict` · `not_found`

## 3. 추천 · 쿠폰 · 소개 판정 · 시드 (담당 E)

E1 ~ E17. 추천 API(E7은 계산보다 계약을 먼저), 노출 · 코스 제안 기록, 오늘의 코스, 쿠폰 행사 · 발급 · 적용 · 상태.

(아직 없음)
