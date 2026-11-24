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

### 예약 응답 `Reservation` (D2)

예약 신청 · 조회 · 상태 변경 응답이 같이 쓴다(D3 · D4 · D6 · D11 · D12도 이 모양을 쓴다).

```json
{
  "id": "string",
  "reservationNo": "K7Q2M9XA",
  "storeId": "string",
  "startAt": "2026-12-01T09:00:00Z",
  "partySize": 2,
  "status": "requested",
  "cancelReason": null,
  "requestedAt": "2026-11-27T07:40:00Z",
  "requestNote": "string | null",
  "depositAmount": 10000,
  "coupon": { "couponId": "string", "discountAmount": 1000 },
  "courseRootId": null,
  "fromRecommendation": false
}
```

- `status` · `cancelReason`: `CONTRACTS.md` 「3」 값.
- `reservationNo`: 8자, 영문 대문자 · 숫자(헷갈리는 `0 O 1 I` 제외). 비회원 예약 조회에 쓴다(FR-AUTH-03).
- `depositAmount`: 원 단위. 예약금 없는 가게는 0. 실제 결제 없음(가상 잔액).
- `coupon`: 적용된 쿠폰이 없으면 `null`(비회원은 항상 `null`).
- `courseRootId`: '다음 코스로 예약하기'로 만든 예약만 기준 가게 예약 id(D12). 아니면 `null`.
- `fromRecommendation`: 사장님 화면의 '추천으로 왔어요' 표시용. **보낸 가게 id · 이름은 어떤 응답에도 넣지 않는다**(FR-CUS-05).
- 응답에 손님 이름 · 전화번호는 넣지 않는다. 사장님용 목록(D4)은 가린 이름만 따로 정한다.

### `POST /api/v1/reservations` — 예약 신청 (D2)

- 역할: 손님. **로그인 불필요** — 로그인한 회원이면 회원 예약, 아니면 비회원 예약. 사장님 · 상인회 계정은 `forbidden_role`.
- 요청:

```json
{
  "storeId": "string",
  "startAt": "2026-12-01T09:00:00Z",
  "partySize": 2,
  "requestNote": "string | null",
  "shareConsent": true,
  "guest": { "name": "string", "phone": "01012345678", "gender": "female | male | null", "ageGroup": "age_30s | null" },
  "couponId": "string | null",
  "courseRootId": "string | null",
  "courseProposalId": "string | null"
}
```

  - `startAt`: 가게 슬롯 시작 시각(UTC). 슬롯 계산은 D5.
  - `shareConsent`: `true`여야 한다. 동의 항목은 그 시점 권역 공유 정책으로 서버가 기록한다(FR-CUS-01, C8).
  - `guest`: 비회원만 필수(`name` 1 ~ 20자, `phone` 숫자 10 ~ 11자). 회원이 보내면 무시한다. `gender` · `ageGroup`은 선택.
  - `couponId`: 회원만. 화면에서 기본 선택된 쿠폰을 그대로 보내고, 해제했으면 `null`(FR-CPN-06, E13).
  - `courseRootId` · `courseProposalId`: '다음 코스로 예약하기'일 때만(D12). 일반 예약은 `null`.
  - 익명 방문자 ID는 쿠키로 서버가 읽어 손님에 잇는다(E3). 요청 본문에 넣지 않는다.
- 응답 `201`: `Reservation`(`status: "requested"`)
- 실패:
  - `invalid`: 필수 값 없음, `shareConsent`가 `true`가 아님, 비회원 `guest` 없음, 인원이 1 미만이거나 가게 한 팀 최대 인원 초과
  - `not_found`: 없는 가게 · 쿠폰 · 기준 예약(남의 것 포함)
  - `slot_unavailable`: 예약을 받을 수 없는 시각 — 지난 시각, 예약 가능 기간 밖, 영업시간 밖 · 휴무일, 슬롯이 다 참, 가게가 휴업 · 중단 · 예약 받기 멈춤
  - `insufficient_balance`: 회원 잔액이 예약금보다 적음(화면은 충전 안내, FR-RSV-08)
  - `conflict`: 다음 코스 예약이 기준 예약과 겹치거나 같은 날 기준 예약보다 이름(D12), 쓸 수 없는 쿠폰
  - `forbidden_role` · `csrf_invalid`

### 예약 신청 처리 단계 (서버 내부, D2 뼈대)

여러 담당이 같은 신청 코드를 고치지 않도록 단계로 나눈다(기획 저장소 00-backlog 「같이 쓰는 것 규칙」). 각 담당은 **자기 단계 클래스만** 만든다. 한 트랜잭션 안에서 순서대로 돌고, 한 단계라도 실패하면 아무것도 저장되지 않는다.

| 순서 | 단계 | 담당 · 이슈 | 하는 일 |
| --- | --- | --- | --- |
| 10 | 입력 검증 | D2 | 필수 값 · 인원 · 공유 동의 |
| 20 | 손님 식별 | C4 | 회원 → 계정의 손님, 비회원 → 전화번호로 손님 찾기 · 만들기, 익명 방문자 연결 |
| 30 | 예약 가능 확인 | D2 · D5 | 가게 상태 · 영업시간 · 휴무일 · 기간 · 슬롯 · 최대 인원 |
| 40 | 코스 연결 | D12 | 기준 예약 · 제안 확인, 시간 규칙, 보낸 가게 |
| 50 | 공유 동의 기록 | C8 | 동의 시각 · 항목 저장 |
| 60 | 쿠폰 적용 | E13 | 쿠폰 확인 · 적용 |
| 70 | 예약금 | D10 | 회원 잔액 차감 · 비회원 납부 기록 |
| 80 | 저장 | D2 | 예약번호 발급 · 저장 |
| 커밋 뒤 | 알림 | C14 · C15 | 사장님 새 예약 알림(SSE) |

- 코드 모양(D2에서 확정): `interface ReservationRequestStep { int order(); void apply(ReservationRequestContext ctx); }` 구현을 스프링 빈으로 두면 D2가 `order` 순서대로 부른다. 아직 없는 단계는 건너뛴다.
- 단계가 실패를 알릴 때는 `ApiException`(위 실패 코드)을 던진다.

## 3. 추천 · 쿠폰 · 소개 판정 · 시드 (담당 E)

E1 ~ E17. 추천 API(E7은 계산보다 계약을 먼저), 노출 · 코스 제안 기록, 오늘의 코스, 쿠폰 행사 · 발급 · 적용 · 상태.

(아직 없음)
