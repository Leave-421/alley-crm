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

### 계정 응답 `Account` (C2)

로그인 · 내 계정 응답이 같이 쓴다.

```json
{ "id": "string", "loginId": "string", "role": "customer", "storeId": null, "regionId": null }
```

- `role`: `customer` · `owner` · `association` (`CONTRACTS.md` 「2. 역할」)
- `storeId`: `owner`만 값이 있고 나머지는 `null`. `regionId`: `association`만 값이 있고 나머지는 `null`.

### `POST /api/v1/auth/signup` — 손님 회원가입 (C2)

- 역할: 누구나(로그인 불필요). 손님(`customer`) 계정만 만든다. 사장님 계정은 가게 가입(C5), 상인회 계정은 초기 데이터로만 생긴다.
- 요청: `{ "loginId": "string", "password": "string", "name": "string", "gender": "string | null", "ageGroup": "string | null" }`
  - `loginId` 4 ~ 30자 영문 소문자 · 숫자, `password` 8 ~ 64자, `name` 1 ~ 20자.
  - `gender` · `ageGroup`은 생략 가능. 값 목록은 `CONTRACTS.md` 「3. Entity · 상태」(D1이 확정).
- 응답 `201`: `Account`. 가입만 하고 로그인 세션은 만들지 않는다.
- 실패: `invalid` · `conflict`(이미 쓰는 `loginId`) · `csrf_invalid`

### `POST /api/v1/auth/login` — 로그인 (C2)

- 역할: 누구나(로그인 불필요). 세 역할 모두 이 경로로 로그인한다.
- 요청: `{ "loginId": "string", "password": "string" }`
- 응답 `200`: `Account` + 새 세션 쿠키(세션 ID 재발급)
- 실패: `invalid`(빈 값) · `unauthorized`(아이디가 없거나 비밀번호가 틀림 — 둘을 구분하지 않는다) · `csrf_invalid`
- 가게 가입이 승인되지 않은 사장님 계정의 처리는 C5(FR-AUTH-04)에서 정한다.

### `POST /api/v1/auth/logout` — 로그아웃 (C2)

- 역할: 로그인한 누구나
- 응답 `204`. 세션을 끝낸다.
- 실패: `unauthorized` · `csrf_invalid`

### `GET /api/v1/auth/me` — 내 계정 (C2)

- 역할: 로그인한 누구나
- 응답 `200`: `Account`
- 실패: `unauthorized`

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
- 코드 위치: `com.alleycrm.reservation.request`. 순서 상수는 `ReservationRequestStep.VALIDATE` ~ `SAVE`. 단계끼리 주고받는 값은 `ReservationRequestContext`의 자기 칸에만 쓴다(칸 옆 주석에 담당). 같은 `order`가 둘이면 서버가 뜨지 않는다.
- 단계 20(C4)이 `customerId`를 채워야 저장(80)이 된다. 로그인했는지는 `ctx.loggedIn()`, 회원 · 역할 판단(사장님 · 상인회 → `forbidden_role`)은 단계 20이 한다. C4가 들어오기 전까지 테스트는 대역 단계를 쓴다.
- 커밋 뒤 알림: 저장되면 `ReservationRequestedEvent(reservationId, storeId, customerId)`를 내보낸다. C14 · C15는 `@TransactionalEventListener`로 받는다(커밋 뒤에만 실행).

#### 정책에 따라 정한 것 (D2)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 예약 가능 기간 | 운영 시간대(Asia/Seoul) 기준 **오늘부터 `booking_window_days`일 뒤 날짜까지**(그 날짜 포함). 기본 30, 사장님은 1 ~ 60(DB CHECK) | FR-RSV-01 #4, FR-RSV-05, 기획 11-25 (2) |
| 슬롯이 찼는지 | 같은 가게 · 같은 `start_at`의 `requested` · `confirmed` 예약 수가 `capacity_per_slot` 이상이면 참 | FR-RSV-01 #3, FR-RSV-05 #2 |
| 동시 신청 | 가게 행을 잠가(`SELECT … FOR UPDATE`) 같은 가게 신청을 한 줄로 세운다. 자리를 넘겨 받지 않는다 | NFR-09 |
| 승인 전 · 거절 · 탈퇴 가게 | 손님에게 없는 가게로 보고 `404 not_found` | FR-STO-01 |
| D5가 단계 30에 더할 것 | 영업시간 · 휴무일 · 슬롯 단위 맞춤 · 한 팀 최대 인원(`invalid`) | D5 (#71) |

### 가상 잔액 · 거래 내역 (D9)

실제 결제는 없다(NFR-02). 잔액이 바뀔 때마다 거래 내역이 한 줄 남는다(FR-STL-06). 회원 잔액은 마이너스가 되지 않고, 가게 잔액은 소개 수수료로 마이너스가 될 수 있다(기획 11-25 (4)).

#### `GET /api/v1/wallets/me` — 내 가상 잔액

- 역할: 로그인한 누구나. 회원은 자기 잔액, 사장님은 자기 가게 잔액, 상인회는 권역 공동 기금. 지갑 주인은 로그인 계정으로 정하고 ID를 받지 않는다.
- 응답 `200`: `{ "ownerType": "customer | store | fund", "balance": 30000, "needsCharge": false }`
  - `balance`: 원. 아직 거래가 없으면 0. 가게는 음수일 수 있다.
  - `needsCharge`: 가게 잔액이 0보다 적으면 `true`(사장님 화면 '충전 필요' 안내). 회원 · 기금은 항상 `false`.
- 실패: `unauthorized`

#### `GET /api/v1/wallets/me/transactions` — 내 거래 내역

- 역할: 위와 같음. 페이지네이션(공통 규칙), 최근 것부터.
- 응답 `200`: 페이지 `items[]` = `{ "id": "string", "type": "charge", "amount": -1200, "reservationId": "string | null", "createdAt": "2026-11-27T01:00:00Z" }`
  - `type`: `CONTRACTS.md` 「3」 `walletTx.type`. `amount`: 들어오면 +, 나가면 −.
- 실패: `invalid`(page · size 범위) · `unauthorized`

#### 서버 내부: `WalletService` (D10 · D6 · D15가 씀)

- 잔액은 `WalletService`로만 바꾼다. `wallet` · `wallet_tx`를 직접 고치지 않는다.
- `post(owner, type, amount, reservationId)`: 잔액을 바꾸고 거래 한 줄. 회원 잔액이 모자라면 `ApiException(insufficient_balance)`. 조건부 UPDATE 한 번이라 동시 요청에도 회원 잔액이 음수가 되지 않는다.
- `transfer(from, fromType, to, toType, amount, reservationId)`: 가게 → 기금처럼 양쪽에 한 줄씩.
- `recordGuest(phone, type, amount, reservationId)`: 비회원은 지갑 없이 전화번호에 묶은 기록만.
- 거래 시각은 서버 시계(D18)를 따른다.
- 충전 API(회원 · 가게)는 D10에서 이 절에 더한다.

## 3. 추천 · 쿠폰 · 소개 판정 · 시드 (담당 E)

E1 ~ E17. 추천 API(E7은 계산보다 계약을 먼저), 노출 · 코스 제안 기록, 오늘의 코스, 쿠폰 행사 · 발급 · 적용 · 상태.

### 3.1 다음 방문 추천 (E7 #21 · 계약 #111)

> 상태: **계약.** 계산(E4 · E5 · E6)과 기록 저장(E3)은 아직 없다. 프론트 목(A5 #22 · B3 #62)은 이 절을 따른다.
> 기준: 기획 저장소 `05-policy.md` 「다음 방문 추천」 · 「코스 제안 수락률」 · 「소개 판정」, `03-requirements.md` 용어 · FR-CRS · FR-ANL-06, `06-data.md` 「추천 기록」, 결정 11-23 (3) · 11-24 (2).
> 정책에 없어 이 계약이 정한 값은 「구현 기준」에, 아직 정하지 못한 것은 「미확정」에 모았다. 미확정이 정해지면 이 절만 고친다.

**조회와 기록을 나눈다.** `GET`은 추천을 계산해서 돌려주기만 하고 아무것도 기록하지 않는다(안전 · 멱등). 프론트가 카드를 **실제로 화면에 그린 뒤** `POST`로 "이 카드들을 보여줬다"를 알리면 그때 기록한다. 응답만 받고 그리지 않은 카드(미리 불러오기, 화면 이동, 렌더 실패, 개발 모드 이중 호출)는 기록되지 않는다. 정책의 수락률 분모가 "**실제로 표시한** 정식 코스 제안"이기 때문이다(FR-ANL-06).

| | 지도 추천 (탐색) | 정식 코스 제안 |
| --- | --- | --- |
| 조회 | `GET /api/v1/recommendations` | `GET /api/v1/course-proposals` |
| 표시 기록 | `POST /api/v1/recommendation-exposures` | `POST /api/v1/course-proposals` |
| 누가 | 누구나 (로그인 불필요, FR-CRS-01) | 그 기준 예약의 손님 본인 |
| 화면 | G-01 가게 확대의 추천 카드, G-04 '다음에 많이 찾은 곳' | G-02 완료 화면의 다음 코스 추천, 추천 알림(FR-CRS-02)에서 연 화면. G-01 추천 카드는 미확정 R-18 |
| 기록 | 탐색 노출 `RECOMMENDATION_EXPOSURE` | 정식 코스 제안 `COURSE_PROPOSAL` |
| 소개 · 수락률 | **쓰지 않음** (11-24 (2), FR-CRS-07) | 수락률 분모. 코스 예약은 `courseProposalId`를 달고 와야 소개 후보 |
| 쿠폰 출처 | `exposureId` = 추천 ID (FR-CPN-10) | — |

- '다음 코스로 예약하기'가 **없는** 추천 표시는 탐색이다(G-04 포함). 정식 코스 제안은 '다음 코스' 제안이다(03-requirements 용어).
- 쿠폰 다운로드는 G-01 추천 카드에서만 한다(FR-CPN-03). G-01 카드는 언제나 탐색 노출을 기록하므로 쿠폰 출처는 그 `exposureId`다.

#### 공통 규칙

- **후보 · 순위**: 05-policy 「다음 방문 추천」 1 ~ 6단계. 쿠폰 행사 · 업종 필터는 후보와 순위를 바꾸지 않는다(FR-CRS-01, FR-CPN-02).
- **묶음(리롤)**: 한 번에 최대 3곳(`recommend_count`). `page`는 0부터. 서버는 받은 `page`를 `pageCount`로 나눈 나머지로 맞춰 돌려준다. 그래서 끝까지 보면 처음 묶음으로 돌아가고(FR-CRS-06), 그 사이 후보 수가 줄어도 에러가 나지 않는다. 프론트는 다음 요청에 `(page + 1) % pageCount`를 보낸다. `pageCount ≤ 1`이면 리롤 버튼을 숨긴다(FR-CRS-06 #4).
- **손님 식별에 따른 차이**: 요청 인자가 아니라 세션으로 정한다. 성별 · 연령대는 쿼리로 받지 않는다.

  | 요청자 | 손님층 항 · 집단 점수(5단계) | '같은 날 이미 예약한 가게' 제외 |
  | --- | --- | --- |
  | 로그인 안 함 (익명 방문자) | 빼고 가중치 다시 맞춤 | 하지 않음 (식별 안 됨) |
  | 회원 | 회원정보의 성별 · 연령대 (없으면 뺌) | 계정의 예약으로 |
  | 비회원 제한 세션 | 예약 때 고른 성별 · 연령대 (없으면 뺌) | 그 전화번호의 예약으로 |
  | 사장님 · 상인회 | 빼고 계산 | 하지 않음 |

- **숫자 근거를 응답에 넣지 않는다.** 점수 · 같이 간 비율 · 손님 수는 응답에 없다. `reasonText`에도 숫자가 없다(FR-CRS-05, FR-CUS-05).
- 개인화된 응답이라 `GET` 응답은 `Cache-Control: no-store`.
- **RecommendationCard** (두 `GET`의 `items` 원소):

  ```json
  {
    "rank": 1,
    "store": { "id": "7", "name": "구름 디저트", "category": "dessert", "alley": "center", "mapX": 150, "mapY": 110 },
    "badge": "visit_data",
    "reasonType": "visit_data",
    "reasonText": "이 가게 손님들이 같은 날 자주 찾는 참여 가게예요",
    "couponBenefit": true
  }
  ```

  | 필드 | 뜻 |
  | --- | --- |
  | `rank` | 전체 순위(1부터). 두 번째 묶음은 4 ~ 6 |
  | `store.category` · `store.alley` | 업종 · 소속 골목. 값은 `CONTRACTS.md` 「3」 |
  | `store.mapX` · `store.mapY` | 가게 좌표. **좌표계는 미확정(R-9).** 거리는 응답에 넣지 않는다(FR-MAP-08) |
  | `badge` | `visit_data` · `local_popular`(`CONTRACTS.md` 「3」). '지역 인기 가게' 안내 문구는 프론트 |
  | `reasonType` · `reasonText` | 근거 종류(`CONTRACTS.md` 「3」 추천)와 서버가 만든 문구. `distance`는 `local_popular`와만 나온다 |
  | `couponBenefit` | '예약 혜택' 배지 표시값. 진행 중인(중단되지 않은) 쿠폰 행사가 있고 총수량이 남았으면 `true`. 회원별 발급 가능 여부(30일 제한 등)는 쿠폰 API(E12)가 답한다 |

### `GET /api/v1/recommendations` — 지도 추천 조회

- 역할: 누구나(로그인 불필요). 기록하지 않는다.
- 쿼리: `baseStoreId` (필수), `page` (선택, 기본 0, 0 이상)
- 응답 `200` (`items`의 원소는 RecommendationCard):

  ```json
  {
    "baseStoreId": "3",
    "baseTime": "2026-12-04T10:00:00Z",
    "baseTimeSource": "now",
    "page": 0,
    "pageCount": 2,
    "items": [],
    "publicData": { "source": "string", "period": "string" }
  }
  ```

  - `baseTimeSource`: `now` 또는 `reservation`(식별된 손님이 이 가게에 신청 · 확정 예약이 있을 때, FR-CRS-03 #4). 여러 개일 때는 「구현 기준」.
  - `publicData`: 카드 중 `local_popular`가 하나라도 있으면 공공데이터 출처 · 기준 기간(NFR-10 #2), 없으면 `null`.
  - 후보가 없으면 `items: []`, `pageCount: 0`.
- 실패: `invalid`(baseStoreId 없음 · 숫자 아님, page < 0) · `not_found`(없는 가게, 가입 승인 전 · 탈퇴한 가게)

### `POST /api/v1/recommendation-exposures` — 지도 추천 표시 기록

- 역할: 누구나(로그인 불필요). 상태 변경이라 `X-XSRF-TOKEN` 필요(`CONTRACTS.md` 「6」).
- 익명 방문자 ID 쿠키로 방문자를 정한다. 쿠키가 없으면 이 응답에서 새로 발급하고 그 ID로 기록한다(FR-CRS-07, 쿠키는 「구현 기준」).
- 요청:

  ```json
  { "displayId": "5b0f6c1e-8a1d-4a53-9b7e-2f4b7c9d1e00", "baseStoreId": "3", "page": 0, "storeIds": ["7", "9", "12"] }
  ```

  - `displayId`: 프론트가 카드 묶음을 **새로 그릴 때마다** 새로 만드는 UUID. 같은 표시를 다시 보낼 때(네트워크 재시도 등)는 같은 값을 쓴다.
  - `storeIds`: 그 묶음에서 실제로 그린 가게. 1 ~ 3개, 중복 없음, 화면 순서대로.
- 응답 `200`: `{ "items": [ { "storeId": "7", "exposureId": "123" } ] }`
- **중복과 반복 (R-1)**
  - 같은 (방문자, `displayId`, 가게)는 **같은 표시 이벤트의 중복 전송**이다. 새 행을 만들지 않고 처음의 `exposureId`를 돌려준다.
  - `displayId`가 다르면 **실제로 다시 보여준 것**(가게를 다시 누름, 리롤로 다시 나옴, 새로고침 뒤 다시 그림)이라 새로 기록한다.
  - 반복 노출을 집계에서 어떻게 셀지는 그 값을 쓰는 지표(FR-ANL-08, C21)가 정한다. 기록 단계에서 합치지 않는다(06-data 「계산해서 보여주는 값」).
- 기록할 때 순위 · 배지 · 근거 종류는 서버가 그 시점에 다시 계산한 값으로 남긴다.
- 실패: `invalid`(displayId가 UUID가 아님, storeIds 0개 · 3개 초과 · 중복, page < 0) · `not_found`(기준 가게) · `conflict`(storeIds 중 지금 이 기준 가게의 추천 후보가 아닌 가게 — 하나라도 있으면 아무것도 기록하지 않음) · `csrf_invalid`

### `GET /api/v1/course-proposals` — 정식 코스 제안 조회

- 역할: 손님(회원, 비회원 제한 세션). 사장님 · 상인회는 `403 forbidden_role`. 기록하지 않는다.
- 쿼리: `baseReservationId` (필수), `page` (선택, 기본 0)
- 검사 순서:
  1. 로그인 → `401 unauthorized`
  2. 역할 → `403 forbidden_role`
  3. 예약이 없거나 **요청자의 예약이 아니면 `404 not_found`**(존재를 숨김, IDOR). 회원은 계정, 비회원은 제한 세션의 전화번호로 본다.
  4. 기준 가게는 요청으로 받지 않고 **그 예약의 가게로 정한다.** 그래서 기준 가게와 예약이 어긋날 수 없다.
- 기준 시각은 그 예약 시각이다. 같은 날 제외도 그 날짜로 한다.
- 응답 `200` (`items`의 원소는 RecommendationCard):

  ```json
  {
    "baseReservationId": "88",
    "baseStoreId": "3",
    "baseTime": "2026-12-04T10:00:00Z",
    "applyDeadline": "2026-12-04T15:00:00Z",
    "page": 0,
    "pageCount": 2,
    "items": [],
    "publicData": null
  }
  ```

  - `applyDeadline`: 소개 예약 신청 마감. 기준 예약일 다음 날 00:00(Asia/Seoul) 직전, UTC로 준다(05-policy 「소개 판정」). 표시용이고 판정은 D12 · E15가 신청 시각으로 한다. 마감이 지나도 조회 · 표시 기록은 그대로 된다(아래 「정책에 따라 정한 것」).
- 실패: `invalid` · `unauthorized` · `forbidden_role` · `not_found`

### `POST /api/v1/course-proposals` — 정식 코스 제안 표시 기록

- 역할 · 검사 순서: `GET /api/v1/course-proposals`와 같다. `X-XSRF-TOKEN` 필요.
- 요청: `{ "baseReservationId": "88", "storeIds": ["7", "9", "12"] }` — 그 묶음에서 실제로 그린 가게. 1 ~ 3개, 중복 없음.
- 응답 `200`:

  ```json
  { "items": [ { "storeId": "7", "courseProposalId": "45", "firstShownAt": "2026-12-01T09:00:00Z" } ] }
  ```

  - 프론트는 '다음 코스로 예약하기'에서 이 `courseProposalId`를 코스 예약 요청(D12)에 그대로 넘긴다. ID가 없으면 코스 예약을 소개 후보로 만들 수 없다.
- **조합당 1건**: (기준 예약, 추천 가게) 조합이 이미 있으면 새로 만들지 않고 같은 `courseProposalId`와 처음의 `firstShownAt`을 돌려준다. 없으면 만든다. 새로고침 · 리롤로 다시 나온 가게 · 동시 요청 · 추천 알림에서 다시 열기 모두 같은 ID다(05-policy 「코스 제안 수락률」, FR-CRS-02 #3). 동시 요청도 DB 유일 제약으로 1건이 된다(D19 #120).
- 리롤로 **처음 나온** 가게는 새 조합이라 새로 만든다. 이것도 수락률 분모에 들어간다(05-policy 6단계 "실제 표시된 제안만 분모에 포함").
- 이 엔드포인트는 탐색 노출을 남기지 않는다.
- 실패: `invalid`(storeIds 0개 · 3개 초과 · 중복) · `unauthorized` · `forbidden_role` · `not_found` · `conflict`(storeIds 중 지금 이 기준 예약의 추천 후보가 아닌 가게 — 하나라도 있으면 아무것도 기록하지 않음) · `csrf_invalid`

### 기록과 집계 기준

서로 다른 기록이다. 하나로 다른 것을 대신 세지 않는다(FR-CRS-07, 05-policy 「추천 쿠폰」 마지막 줄).

| 기록 | 생기는 때 | 만드는 이슈 | 쓰는 곳 | 쓰지 않는 곳 |
| --- | --- | --- | --- | --- |
| 가게 클릭 `STORE_CLICK` | 지도에서 가게를 누름 (이 절 밖, E3) | E3 | 탐색 기록, FR-ANL-08 | 소개 · 수락률 |
| 탐색 노출 `RECOMMENDATION_EXPOSURE` | 지도 추천 카드를 그린 뒤 `POST` | E3 · E7 | 탐색 기록, 쿠폰 출처(FR-CPN-10), FR-ANL-08 | 소개 · 수락률 |
| 정식 코스 제안 `COURSE_PROPOSAL` | 정식 코스 제안 카드를 그린 뒤 `POST`, 조합당 1건 | E3 · E7 | **수락률 분모** | — |
| 코스 예약 (소개 후보) | `courseProposalId`를 달고 마감 전에 신청 | D12 | 소개 판정 입력 | — |
| 소개 확정 | 소개 후보 예약이 방문 처리됨, 예약 건당 1번 | E15 (D11 이벤트) | **수락률 분자**(확정된 제안 수, 제안당 1번), 수수료(D15), 소개 실적 | — |
| 가게 간 이동 | 같은 손님 · 같은 날 연달아 방문 처리 | 계산값 (C20 · E6) | 추천 실제 기록 점수, 방문 흐름 | 소개 |

- 클릭 · 지도 추천 · 리롤 · 새로고침은 정식 제안을 만들지 않으므로 수락률과 소개에 영향이 없다.
- 정식 제안은 조합당 1건이라 같은 화면을 여러 번 열어도 분모가 늘지 않는다. 분자는 확정된 제안 수라 한 제안에서 소개가 여러 번 나와도 1이다.

### 정책에 따라 정한 것

| 항목 | 내용 | 근거 |
| --- | --- | --- |
| 신청 마감 뒤 표시 (R-4) | 마감 뒤에 그린 정식 제안도 기록하고 수락률 분모에 들어간다. 분모 정의에 마감 조건이 없고, 마감 뒤에도 '다음 코스로 예약하기'는 열려 있으며 소개만 되지 않는다 | FR-ANL-06, 03-requirements 용어 '정식 코스 제안', 04-features FR-STL-01 #2, 06-data `course_proposal_id` |
| 쿠폰 출처 (R-12) | G-01 추천 카드의 `exposureId`. 정식 제안 화면에는 쿠폰 다운로드가 없다 | FR-CPN-03 · 10, 07-screens G-01 · G-02 |
| 한 제안으로 여러 코스 예약 | 막지 않는다. 소개 · 수수료는 예약 건당, 수락률 분자는 제안당 1 | 05-policy 「소개 판정」 · 「코스 제안 수락률」 · 「수수료 계산」 |
| 순위가 바뀐 뒤 | 이미 만든 제안은 지우거나 바꾸지 않고, 그 제안으로 코스 예약할 수 있다(순위 조건 없음) | 05-policy 「소개 판정」 |
| 기준 예약 취소 · 거절 뒤 | 이미 만든 제안 · 코스 예약은 그대로 둔다 | FR-RSV-12, 05-policy 「소개 판정」 |
| 업종 · 골목 값 | D1이 정한 값을 쓴다 | `CONTRACTS.md` 「3」 |

### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

정책이 정하지 않은 세부를 이 계약이 정했다. 이견이 있으면 #21에 남겨 주세요.

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 한 가게에 내 예약이 여러 개일 때 지도 추천 기준 시각 (R-6) | 지금 이후 가장 가까운 신청 · 확정 예약의 시각, 없으면 현재 시각 | FR-CRS-03 #4: 예약했으면 그 예약 시각 | A5 |
| '실제로 표시'의 시점 | 카드 묶음이 화면에 그려진 뒤 한 번 `POST` (화면 밖으로 스크롤됐는지는 보지 않음) | FR-ANL-06 | A5 |
| 표시 ID (R-1) | 새로 그릴 때마다 새 UUID, 재전송은 같은 값 | FR-CRS-07 | A5 · D19 |
| 동점 순서 | 가게 ID 오름차순 | 05-policy 6단계 "재현되도록 고정된 순서" | — |
| '같은 날' | 기준 시각의 Asia/Seoul 날짜 | 05-policy "서비스 운영 시간대" | D18 |
| '같은 날 이미 예약한 가게' | 신청 · 확정 · 방문 예약. 취소된 예약은 넣지 않음 | FR-CRS-01 | — |
| 후보가 아닌 가게를 `POST` | `409 conflict`, 아무것도 기록 안 함 | — | — |
| 익명 방문자 쿠키 | 3.3 「익명 방문자 쿠키」(`alley_vid`, 90일, `HttpOnly` · `SameSite=Lax`, `visitor.anon_id`) | FR-CRS-07, 11-23 (5) | C |

### 미확정

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| R-3 | 기준 예약이 취소 · 거절된 뒤 **새** 다음 코스 예약(과 새 정식 제안)을 허용하는지. FR-RSV-11 #1 '기준 가게 예약이 없음'에 취소된 예약이 드는지 | 이 경우 두 엔드포인트의 응답을 정하지 않는다. 목에서 다루지 않는다 | 팀 (D12 해석안) |
| R-18 | G-01 추천 카드에 '다음 코스로 예약하기'를 두는지 (07-screens G-01 · FR-RSV-11 ↔ 확정 목업, 02-workflow 「맞지 않는 곳」) | G-01 카드는 탐색 노출만 기록한다고 적고, 정식 제안을 겸하는지는 정하지 않는다 | 팀 (A 화면) |
| R-9 | 가게 좌표계. 06-data · 마이그레이션은 `map_x/map_y`를 "골목 안 표시 좌표"로, 05-policy 거리 항은 다른 골목 가게 사이 거리도 씀 | `mapX` · `mapY`는 숫자로만 내려준다 | A1 · D1 · E |
| R-10 | 이 절의 비로그인 경로(`GET /api/v1/recommendations`, `POST /api/v1/recommendation-exposures`)를 보안 설정에 더하기 | — | C (#24) |
| R-13 | 코스 예약 요청의 필드 이름(`courseProposalId`)과 제안 검사 실패 코드. 검사 조건(제안 소유 · 추천 가게 일치 · 마감)은 정책상 확정 | — | D12 (#31) |

### 3.2 쿠폰 행사 설정 (E11 #23 · 계약 #133)

> 상태: **계약.** 구현은 #23에서 한다. 사장님 로그인 · 역할은 C2(#24) 구현 뒤 연결한다. 프론트 목(B15 #84 · B3 #62)은 이 절을 따른다.
> 기준: 기획 저장소 `05-policy.md` 「추천 쿠폰」, `03-requirements.md` FR-CPN-01 · 02 · 04 · 05 · 07 · 11, `06-data.md` COUPON_POLICY · COUPON, `07-screens.md` O-09, 결정 기록 `reviews/decision-meeting-2026-11-25.md`(정률 할인 추가, 쿠폰 기간, 확정 뒤 변경 불가, 중단 뒤 새 행사).
> 선행: 표 · 칸은 D의 #134(`V202611251000__coupon_policy_owner_period.sql`)를 따른다. `benefitType` 값 목록(`CONTRACTS.md` 「3」)과 3.1 `couponBenefit` 문구도 #134가 고친다. #134가 합쳐진 뒤 이 절을 합친다.

**쿠폰 행사**는 사장님이 한 번 확정하면 **고칠 수 없고 중단만 할 수 있다.** 조건을 바꾸려면 중단한 뒤 새 행사를 만든다. **진행 중인 행사(중단하지 않은 행사)는 가게당 하나**다. 경로의 가게는 **로그인한 사장님 계정의 가게**로 정하고 가게 ID를 받지 않는다(IDOR).

**CouponEvent** (응답):

```json
{
  "id": "12",
  "storeId": "3",
  "benefitType": "percent_discount",
  "discountAmount": null,
  "discountPercent": 10,
  "maxDiscountAmount": 5000,
  "serviceName": null,
  "serviceValue": null,
  "validDays": 7,
  "conditions": "2인 이상 방문 시",
  "totalQuantity": 100,
  "issuedCount": 12,
  "remainingQuantity": 88,
  "confirmedAt": "2026-12-01T03:00:00Z",
  "stoppedAt": null,
  "benefitAvailable": true
}
```

| 필드 | 뜻 |
| --- | --- |
| `id` | 행사 ID. 행사를 새로 만들 때마다 새 ID |
| `benefitType` | `fixed_discount` 정액 할인 · `percent_discount` 정률 할인 · `service` 서비스 제공(`CONTRACTS.md` 「3」, #134) |
| `discountAmount` | 정액 할인 금액(원). `fixed_discount`일 때만 값이 있다 |
| `discountPercent` · `maxDiscountAmount` | 정률 할인율(**퍼센트 정수** 1 ~ 50, `10` = 10%)과 최대 할인액(원). `percent_discount`일 때만 값이 있다. 최대 할인액은 선택이고 `null`이면 상한 없음 |
| `serviceName` · `serviceValue` | 서비스 내용과 환산 금액(원). `service`일 때만 값이 있다. 환산 금액은 쿠폰 비용 기록용이고 수수료 계산에 쓰지 않는다(FR-CPN-01) |
| `validDays` | 쿠폰 예약 기한(일). 받은 쿠폰은 다운로드 + `validDays` × 24시간에 만료된다(FR-CPN-05) |
| `conditions` | 사장님이 정한 조건(자유 문구, 200자 이하). 없으면 `null` |
| `totalQuantity` · `issuedCount` · `remainingQuantity` | 총수량, 지금까지 발급한 수, 남은 수(= 총수량 − 발급 수). 발급 수는 쿠폰 발급(E12 · E9)만 늘린다 |
| `confirmedAt` | 확정(저장)한 시각. 이 계약에서는 저장 = 확정이라 항상 값이 있다(임시 저장 없음) |
| `stoppedAt` | 중단한 시각. `null`이면 진행 중 |
| `benefitAvailable` | **'예약 혜택' 표시값.** 진행 중(`stoppedAt`이 `null`)이고 남은 수가 1 이상이면 `true`. 추천 카드의 `couponBenefit`(3.1)과 같은 규칙이다(FR-CPN-02 · 11) |

### `GET /api/v1/coupon-events/benefit-types` — 혜택 유형 목록

- 역할: 사장님. 손님 · 상인회는 `403 forbidden_role`.
- 응답 `200`: `{ "items": [ { "value": "fixed_discount", "label": "정액 할인" }, { "value": "percent_discount", "label": "정률 할인" }, { "value": "service", "label": "서비스 제공" } ] }` — 3가지(FR-CPN-01 #2).
- 실패: `unauthorized` · `forbidden_role`

### `GET /api/v1/coupon-events/mine` — 내 가게의 진행 중인 쿠폰 행사

- 역할: 사장님. 손님 · 상인회는 `403 forbidden_role`.
- 응답 `200`: `{ "current": CouponEvent | null }`. `current`는 진행 중인 행사이고, 없으면(만든 적 없음 · 중단함) `null`이다.
- 화면은 `current`가 `null`이면 만들기 폼과 "확정 뒤에는 어떤 값도 바꿀 수 없고 중단만 할 수 있다" 안내를 보여준다(FR-CPN-01 #3). 총수량이 다 나간 행사도 중단 전까지는 `current`다(`benefitAvailable: false`). 새로 만들려면 먼저 중단한다.
- 실패: `unauthorized` · `forbidden_role`

### `POST /api/v1/coupon-events` — 쿠폰 행사 만들기(확정)

- 역할: 사장님. `X-XSRF-TOKEN` 필요. 저장하는 순간 확정되고 이후 어떤 값도 바꿀 수 없다.
- 요청:

  ```json
  { "benefitType": "percent_discount", "discountAmount": null, "discountPercent": 10, "maxDiscountAmount": 5000, "serviceName": null, "serviceValue": null, "validDays": 7, "conditions": null, "totalQuantity": 50 }
  ```

- 검사(어긋나면 `400 invalid`):
  - `benefitType`, `totalQuantity`는 필수. `benefitType`은 위 3가지만.
  - `fixed_discount`: `discountAmount` 1 이상 필수.
  - `percent_discount`: `discountPercent` 정수 1 ~ 50 필수. `maxDiscountAmount`는 비우거나 1 이상.
  - `service`: `serviceName`(1 ~ 50자) · `serviceValue`(1 이상) 필수. **환산 금액 없이 만들면 거절**(FR-CPN-01).
  - 고른 유형에 쓰지 않는 칸은 무시하고 저장하지 않는다(응답에서 `null`).
  - `validDays`는 정수 1 ~ 30. 빼면 7(기본값).
  - `conditions`는 200자 이하, `totalQuantity`는 1 이상.
- 상태 검사: 진행 중인 행사가 이미 있으면 `409 conflict`(FR-CPN-01 #6). 동시에 두 번 들어와도 진행 중인 행사는 하나만 생긴다(DB의 `uk_coupon_policy_active`가 막고, 서버는 그 위반을 `409 conflict`로 바꾼다).
- 응답 `201`: 만든 CouponEvent.
- 실패: `invalid` · `unauthorized` · `forbidden_role` · `conflict` · `csrf_invalid`

### `POST /api/v1/coupon-events/{eventId}/stop` — 쿠폰 행사 중단

- 역할: 사장님. `X-XSRF-TOKEN` 필요. 요청 본문 없음.
- 화면은 누르기 전에 "다시 켜거나 고칠 수 없고, 이미 받은 쿠폰은 유지된다" 경고를 보여준다(FR-CPN-11 #3).
- 중단하면 **새 발급만 멈춘다.** 이미 받았거나 예약에 적용된 쿠폰은 자기 기한까지 그대로 쓸 수 있다(FR-CPN-11 #1 · #4). 가게 운영 중단(FR-STO-02)과 다르게 30일 제한 해제 · 적용 취소를 하지 않는다.
- 경로에 행사 ID를 받는 이유: 화면을 두 개 열어 둔 채 한쪽에서 중단 → 새로 만들기를 하면, 다른 쪽의 중단 버튼이 **새 행사**를 멈추지 않게 한다.
- 응답 `200`: 중단된 CouponEvent(`stoppedAt` 채움).
- 실패:
  - `404 not_found` — 없는 행사 · 다른 가게 행사(존재 숨김)
  - `409 conflict` — 이미 중단한 행사(FR-CPN-11 #5)
  - `unauthorized` · `forbidden_role` · `csrf_invalid`

### 고치는 API는 없다

- 확정한 행사를 고치거나 다시 켜는 엔드포인트를 두지 않는다(FR-CPN-01 #4, FR-CPN-11 #5). `PUT` · `PATCH`는 공통 규칙대로 `404 not_found`(`CONTRACTS.md` 「4」 "허용되지 않은 메서드").

### 다른 이슈가 쓰는 규칙 (서버 내부)

| 규칙 | 정의 | 쓰는 곳 |
| --- | --- | --- |
| 진행 중인 행사 | `stopped_at IS NULL`인 그 가게의 행 (가게당 0 ~ 1개) | 전부 |
| '예약 혜택' 표시값 | 진행 중 AND 남은 수 ≥ 1 | 추천 카드 `couponBenefit`(E7), 쿠폰 상세(E12) |
| 새 발급 가능(가게 쪽 조건) | 위와 같음. 회원별 30일 제한 · 같은 행사의 미사용 적용 쿠폰은 E12가 따로 본다 | 쿠폰 다운로드(E12) |
| 발급 수 늘리기 | `UPDATE coupon_policy SET issued_count = issued_count + 1 WHERE id = ? AND stopped_at IS NULL AND issued_count < total_quantity` — 바뀐 행이 0이면 소진 · 중단. 중단과 동시에 들어와도 둘 중 먼저 커밋한 쪽만 반영된다 | E12 · E9 (NFR-09) |
| 쿠폰에 남기는 행사 | 발급한 쿠폰에 `coupon_policy_id`를 넣는다. 혜택 · 기한은 행사 행에서 읽는다(행사는 바뀌지 않으므로 받은 쿠폰의 혜택도 바뀌지 않는다) | E12 · E13 · E14 |
| 만료 시각 | `expires_at = downloaded_at + valid_days × 24시간` | E12 · E14 |
| 30일 제한 단위 | 같은 회원 · 같은 행사(`coupon_policy_id`). 새 행사는 이전 행사와 상관없이 다시 받을 수 있다(FR-CPN-04) | E12 |
| 할인액 | 정액: `min(discount_amount, 방문 금액)`. 정률: `floor(방문 금액 × discount_percent / 100)`을 정수 연산으로 구하고, `max_discount_amount`가 있으면 그 금액까지. 서비스: 0(쿠폰 비용 = 환산 금액) | E14 · D15 (FR-CPN-07 #5 · #6, FR-STL-02 #5) |

### 응답 필드와 테이블 칸 (#134)

| 필드 | 칸 | 비고 |
| --- | --- | --- |
| `id` · `storeId` | `coupon_policy.id` · `store_id` | 행사마다 행 1개 |
| `discountPercent` | `discount_rate` | 정수 퍼센트(DB CHECK 1 ~ 50). API 이름만 다르다 |
| `maxDiscountAmount` | `max_discount_amount` | DB CHECK > 0 |
| `validDays` | `valid_days` | DB 기본 7, CHECK 1 ~ 30 |
| `confirmedAt` · `stoppedAt` | `confirmed_at` · `stopped_at` | 만들 때 `confirmed_at`을 함께 채운다 |
| — | `active_store_id` | 진행 중인 행사 가게당 하나(UNIQUE)를 위한 계산 칸. 응답에 없고, 엔티티는 읽기 전용으로 둔다(넣거나 바꾸지 않음) |
| — | `coupon.coupon_policy_id` | 받은 행사. 30일 제한 인덱스 `ix_coupon_customer_policy` |

DB가 막지 않는 것(유형별 필수 칸, 확정 뒤 변경 금지, 중단한 행사 다시 켜기 금지)은 서비스가 지킨다. 이 계약에는 고치는 API가 없고, 중단은 `stopped_at IS NULL`일 때만 바꾸는 조건부 UPDATE로 한다.

### 정책에 따라 정한 것 (3.2)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 정률 할인 enum 값 | `percent_discount` (#134가 `CONTRACTS.md`에 넣음) | FR-CPN-01(11-25) |
| 할인율 단위 | API는 `discountPercent` 정수(10 = 10%). 공통 규칙 "비율은 소수"와 헷갈리지 않게 이름에 `Percent`를 넣었다 | FR-CPN-01 "정수 1 ~ 50%" |
| 확정 시점 | 저장 = 확정. 임시 저장 없음 | FR-CPN-01 "처음 확정(저장)" |
| 기간 기본값 | `validDays`를 빼면 7 | FR-CPN-01 "기본 7일" |
| 이전 C-1(발급 뒤 혜택 변경) | 정책으로 닫힘 — 확정 뒤 모든 값 변경 불가 | 11-25 추가 결정, Q-26 |

### 미확정 (3.2)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| C-2 | 승인 전 · 탈퇴 신청 사장님 계정의 접근 | C5(FR-AUTH-04)의 승인 전 계정 규칙을 따른다 | C5 |

### 3.3 탐색 기록 · 익명 방문자 (E3 #79 · 계약 #144)

> 상태: **계약.** 구현은 #79에서 한다. 지도 추천 노출 · 정식 코스 제안 기록의 엔드포인트는 3.1에 있고 여기서 다시 정의하지 않는다.
> 기준: 기획 저장소 `03-requirements.md` FR-CRS-07 · 11, `06-data.md` VISITOR · STORE_CLICK · COURSE_PREVIEW_EVENT, 결정 기록 11-23 (5) · 11-23 (8). 표는 D19 `visitor` · `store_click` · `course_preview_event` · `recommendation_exposure` · `course_proposal`.

로그인 전 행동(가게 클릭 · 추천 노출 · 오늘의 코스)은 브라우저의 **익명 방문자 ID**로 기록하고, 같은 브라우저에서 로그인하거나 비회원 예약을 하면 그 손님에 잇는다. 다른 기기에서 한 예약은 잇지 않는다(FR-CRS-07). 모두 탐색 기록이고 소개 판정 · 수락률에 쓰지 않는다(3.1 「기록과 집계 기준」).

#### 익명 방문자 쿠키

| 항목 | 값 |
| --- | --- |
| 이름 | `alley_vid` |
| 값 | 서버가 만든 UUID. `visitor.anon_id`에 저장 |
| 수명 | 90일(`Max-Age=7776000`). 기록할 때마다 늘리지 않는다 |
| 속성 | `Path=/` · `HttpOnly` · `SameSite=Lax`, HTTPS에서 `Secure`(세션 쿠키와 같은 설정 `SESSION_COOKIE_SECURE`) |
| 발급 | 탐색 기록 `POST`(3.1 `POST /api/v1/recommendation-exposures`, 아래 두 엔드포인트)에서 쿠키가 없으면 그 응답에서 발급하고 새 ID로 기록한다 |
| 다시 발급 | 쿠키 값이 UUID가 아니거나 서버에 없는 ID면 없는 것으로 보고 새로 발급한다. 클라이언트가 정한 ID로 방문자를 만들지 않는다 |

프론트는 이 쿠키를 읽거나 보내는 코드를 따로 두지 않는다(`HttpOnly`, 같은 출처라 브라우저가 붙인다).

#### 방문자를 손님에 잇기 (서버 내부)

| 때 | 부르는 곳 | 하는 일 |
| --- | --- | --- |
| 로그인 성공 | C2 (#24) | 이 요청의 방문자를 로그인한 회원의 손님에 잇는다 |
| 예약 신청 (회원 · 비회원) | C4 (#38) 신청 단계 20 「손님 식별」 | 손님을 정한 뒤 이 요청의 방문자를 그 손님에 잇는다 |
| 로그아웃 | C2 (#24) | 방문자 쿠키를 지운다(`Max-Age=0`). 이후 탐색은 새 익명 방문자로 기록된다 |

- E3가 `com.alleycrm.recommend.visitor.VisitorService`를 제공한다. 부르는 쪽은 손님 ID만 넘긴다: `linkCurrentBrowser(long customerId)`, `forgetCurrentBrowser()`. 쿠키를 읽고 쓰는 일은 E3가 현재 요청 · 응답에서 한다. 그래서 D2 신청 컨텍스트에 칸을 더하지 않는다.
- 잇는 규칙:
  - 쿠키가 없거나 서버에 없는 ID면 아무것도 하지 않는다(이을 탐색 기록이 없다).
  - 아직 손님이 없는 방문자면 `customer_id` · `linked_at`을 채운다. 이미 같은 손님이면 그대로 둔다.
  - **다른 손님에 이미 이어진 방문자면** 기존 연결은 바꾸지 않고, 이 브라우저에 새 방문자 ID를 발급해 새 손님에 잇는다. 지난 탐색 기록이 다른 손님 것으로 옮겨 가지 않게 한다(같은 브라우저를 여러 사람이 쓰는 경우).
- 한 손님에 방문자가 여럿일 수 있다(브라우저 · 기기마다). 다른 기기의 방문자는 그 기기에서 로그인하거나 예약하기 전까지 잇지 않는다(FR-CRS-07 #3).
- 예약 신청이 실패해 롤백되면 연결도 남지 않는다(같은 트랜잭션). 새로 발급한 쿠키는 응답에 실려도 서버에 없는 ID라 다음 요청에서 다시 발급된다.

### `POST /api/v1/store-clicks` — 가게 클릭 기록

- 역할: 누구나(로그인 불필요). 상태 변경이라 `X-XSRF-TOKEN` 필요(`CONTRACTS.md` 「6」).
- 지도에서 가게를 눌러 확대할 때 한 번 보낸다. 같은 가게를 다시 누르면 다시 보낸다(누른 횟수를 센다).
- 요청: `{ "storeId": "7" }`
- 응답 `204` (본문 없음). 쿠키가 없었으면 `Set-Cookie: alley_vid=…`.
- 실패: `invalid`(storeId 없음 · 숫자 아님) · `not_found`(없는 가게 · 승인 전 · 거절 · 탈퇴 가게) · `csrf_invalid`

### `POST /api/v1/course-previews` — 오늘의 코스 노출 · 클릭 기록

- 역할: 누구나(로그인 불필요). `X-XSRF-TOKEN` 필요.
- 오늘의 코스 카드를 그린 뒤 코스마다 `impression`을 한 번, 코스를 누르면 `click`을 한 번 보낸다(FR-CRS-07 #4, FR-CRS-13). 정식 코스 제안 · 소개 연결은 만들지 않는다.
- 요청:

  ```json
  { "type": "impression", "storeIds": ["3", "7", "12"], "dayType": "weekend", "timeBand": "…" }
  ```

  - `type`: `impression` · `click`(`CONTRACTS.md` 「3」 오늘의 코스 기록)
  - `storeIds`: 코스의 가게 3곳, 순서대로, 중복 없음
  - `dayType` · `timeBand`: 그 코스를 고른 칸. 오늘의 코스 응답(E8 #49)에 있는 값을 그대로 보낸다. `timeBand` 값 목록은 E2(#16)가 정한다 — **미확정 V-1**
- 응답 `204`. 쿠키가 없었으면 `Set-Cookie: alley_vid=…`.
- 실패: `invalid`(type · dayType · timeBand가 정해진 값이 아님, storeIds가 3개가 아니거나 중복) · `not_found`(없는 가게 · 승인 전 · 거절 · 탈퇴 가게) · `csrf_invalid`

#### E3 · E7 분담

| 일 | 이슈 |
| --- | --- |
| 방문자 쿠키 · 손님 잇기, 가게 클릭 · 오늘의 코스 기록 엔드포인트 | E3 (#79) |
| 지도 추천 노출 · 정식 코스 제안의 **저장과 중복 처리** (아래 표) | E3 (#79) |
| 3.1의 두 `POST` 엔드포인트, 추천 후보인지 검사(아니면 `409`), 순위 · 배지 · 근거 종류 재계산 | E7 (#21) — 검사를 마친 값으로 E3 저장 서비스를 부른다 |

| 기록 | 저장 규칙 (E3) |
| --- | --- |
| 지도 추천 노출 | (방문자, `displayId`, 추천 가게)가 이미 있으면 새로 만들지 않고 처음 행의 `exposureId`를 돌려준다(`uk_exposure_display`). 동시에 같은 값이 와도 1건 |
| 정식 코스 제안 | (기준 예약, 추천 가게)가 이미 있으면 새로 만들지 않고 처음 행의 `courseProposalId` · `firstShownAt`을 돌려준다(`uk_course_proposal`). 동시에 같은 값이 와도 1건 |
| 묶음 | 한 요청의 가게들은 한 트랜잭션에 저장한다. E7의 후보 검사에서 하나라도 걸리면 아무것도 저장하지 않는다(3.1) |

#### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 쿠키 이름 · 수명 | `alley_vid`, 90일, 늘리지 않음 | FR-CRS-07 "브라우저의 익명 방문자 ID" | C |
| 다른 손님에 이어진 방문자 | 기존 연결 유지, 새 방문자 ID 발급 | FR-CRS-07 "같은 브라우저에서 … 그 손님에 잇는다" | C |
| 로그아웃 | 방문자 쿠키 삭제 | — | C2 |
| 가게 클릭 중복 | 거르지 않는다(누를 때마다 1건) | FR-ANL-08 "가게 클릭 수" | C21 |

#### 미확정 (3.3)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| V-1 | 오늘의 코스 `timeBand` 값 목록 (`course_preview_event.time_band`) | 엔드포인트는 이 값이 정해진 뒤(E2) 구현한다. 목은 임의 문자열로 둔다 | E2 (#16, 팀 결정 P-1) |
| V-2 | 이 절과 3.1의 비로그인 경로를 보안 설정 · `CONTRACTS.md` 「6」 목록에 더하기: `POST /api/v1/store-clicks`, `POST /api/v1/course-previews` (3.1 R-10의 두 경로와 함께) | — | C (#24) |
| V-3 | 로그인 · 예약 신청에서 `VisitorService`를 부르는 것 | E3는 서비스만 제공하고 테스트는 서비스 단위로 한다 | C2 (#24) · C4 (#38) |
