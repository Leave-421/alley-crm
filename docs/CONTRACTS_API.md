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
- 가게 가입이 승인되지 않은(신청 · 거절) 사장님도 로그인할 수 있다. 가입 상태는 `GET /api/v1/store-joins/mine`으로 보고, 다른 사장님 기능은 승인 전에 `403 forbidden_role`이다(C5, FR-AUTH-04).

### `POST /api/v1/auth/logout` — 로그아웃 (C2)

- 역할: 로그인한 누구나
- 응답 `204`. 세션을 끝낸다.
- 실패: `unauthorized` · `csrf_invalid`

### `GET /api/v1/auth/me` — 내 계정 (C2)

- 역할: 로그인한 누구나
- 응답 `200`: `Account`
- 실패: `unauthorized`

### 가게 가입 (C5)

사장님은 가게 가입을 신청하고, 그 권역 상인회가 승인 · 거절한다(FR-STO-01). 신청이 가게(`joinStatus: requested`)와 사장님 계정을 함께 만든다. 가게당 사장님 계정은 1개, 직원 계정은 없다(FR-AUTH-04).

**가게 가입 `StoreJoin`**

```json
{
  "storeId": "string", "regionId": "string", "name": "string",
  "category": "cafe", "alley": "center",
  "openTime": "10:00", "closeTime": "22:00", "closedDays": ["mon"],
  "joinStatus": "requested"
}
```

- `category` · `alley` · `closedDays` · `joinStatus`: `CONTRACTS.md` 「3」 `store.*`. `openTime` · `closeTime`: `HH:mm`(Asia/Seoul 벽시계). `closedDays` 빈 목록이면 연중무휴.
- 가게 정보 요청 칸(아래 `StoreJoinInfo`): `name` 1 ~ 50자, `category` · `alley` 값 목록 안, `openTime` < `closeTime`, `closedDays`는 생략 가능(빈 목록), 같은 요일 중복 불가.
- `alley`는 신청 때 사장님이 고르고, 지도 위치와 함께 상인회가 관리한다(C6).

#### `POST /api/v1/store-joins` — 가게 가입 신청

- 역할: 누구나(로그인 불필요). `X-XSRF-TOKEN` 필요.
- 요청: `{ "loginId": "string", "password": "string", "regionId": "string", ...StoreJoinInfo }` — `StoreJoinInfo` = `{ "name", "category", "alley", "openTime", "closeTime", "closedDays" }`. `loginId` · `password` 규칙은 회원가입과 같다.
- 응답 `201`: `StoreJoin`(`joinStatus: "requested"`). 로그인 세션은 만들지 않는다. 같은 아이디로 로그인하면 승인 전에도 이 가게의 가입 상태를 볼 수 있다.
- 실패: `invalid` · `not_found`(없는 권역) · `conflict`(이미 쓰는 `loginId`) · `csrf_invalid`

#### `GET /api/v1/store-joins/mine` — 내 가게 가입 상태

- 역할: 사장님(승인 전 · 거절 포함). 손님 · 상인회는 `403 forbidden_role`.
- 응답 `200`: `StoreJoin`
- 실패: `unauthorized` · `forbidden_role`

#### `POST /api/v1/store-joins/mine/reapply` — 거절된 가입 다시 신청

- 역할: 사장님. 거절된(`rejected`) 가입만 고친 가게 정보로 다시 신청한다. 받아들일지는 상인회가 정한다.
- 요청: `StoreJoinInfo`
- 응답 `200`: `StoreJoin`(`joinStatus: "requested"`)
- 실패: `invalid` · `conflict`(거절 상태가 아님) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `GET /api/v1/store-joins` — 권역의 가게 가입 목록

- 역할: 상인회. 자기 권역 가게만. 사장님 · 손님은 `403 forbidden_role`.
- 쿼리: `status`(선택, 기본 `requested`, `store.joinStatus` 값) + 페이지네이션(공통 규칙). 오래된 신청부터.
- 응답 `200`: 공통 페이지, `items[]` = `StoreJoin`
- 실패: `invalid` · `unauthorized` · `forbidden_role`

#### `POST /api/v1/store-joins/{storeId}/approve` · `/reject` — 가입 승인 · 거절

- 역할: 상인회. 자기 권역 가게만(다른 권역 가게는 `404`). `X-XSRF-TOKEN` 필요. 요청 본문 없음.
- `requested`인 가입만 `approved` · `rejected`로 바꾼다. 커밋 뒤 `StoreJoinDecidedEvent(storeId, joinStatus)`를 내보내고, C14가 그 가게 사장님에게 `store_join_result` 알림(`payload: { "storeId", "joinStatus" }`)을 남긴다.
- 응답 `200`: `StoreJoin`
- 실패: `not_found` · `conflict`(신청 상태가 아님) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `POST /api/v1/owner/store/withdrawal` — 탈퇴 신청 (C7)

- 역할: 사장님(내 가게). 손님 · 상인회는 `403 forbidden_role`. `X-XSRF-TOKEN` 필요. 요청 본문 없음.
- `approved`인 가게만 `withdraw_requested`로 바꾼다. **미정산이 있으면 거절**한다(FR-STO-03 #1). 미정산 = 그 가게가 낸 소개 수수료(`referral_fee.receiving_store_id`)가 있는 달 중 그 권역 `settlement`가 `confirmed`가 아닌 달(05-policy 「정산」).
- 상인회가 처리하기 전까지 가게는 **그대로 운영된다**(FR-STO-03 #3). 목록 · 지도 · 예약 · 추천 · 쿠폰 · 사장님 기능 모두 `withdraw_requested`를 `approved`와 같이 참여 가게로 본다(`StoreJoinStatus.isParticipating`). 철회 API는 없다.
- 응답 `200`: `StoreJoin`(`joinStatus: "withdraw_requested"`)
- 실패: `conflict`(승인된 가게가 아님 · 이미 신청함 · 미정산 있음) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `POST /api/v1/store-joins/{storeId}/withdraw` — 탈퇴 처리 (C7)

- 역할: 상인회. 자기 권역 가게만(다른 권역 가게는 `404`). 사장님 · 손님은 `403 forbidden_role`. `X-XSRF-TOKEN` 필요. 요청 본문 없음. 신청 목록은 `GET /api/v1/store-joins?status=withdraw_requested`.
- `withdraw_requested`인 가게만 `withdrawn`으로 바꾼다. 미정산을 **다시 확인**해 있으면 거절한다(FR-STO-03 #1).
- 같은 트랜잭션에서:
  - 그 가게 미사용 쿠폰을 `apply_canceled`로 바꾼다(`CouponStatusService.cancelUnusedForStore`, FR-CPN-07 #4).
  - `store.business_status_changed_at`을 지금 시각으로 남겨 30일 제한을 푼다(3.4 「휴업 뒤 제한 해제」와 같은 칸).
- 탈퇴한 가게는 목록 · 지도 · 예약 · 추천 후보에서 빠지고 사장님 기능은 `403`이다. 방문 이력 · 수수료 기록은 지우지 않는다(집계에만 남음, FR-STO-03 #2). 이미 받은 예약은 바꾸지 않는다.
- 응답 `200`: `StoreJoin`(`joinStatus: "withdrawn"`)
- 실패: `not_found` · `conflict`(탈퇴 신청 상태가 아님 · 미정산 있음) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 서버 내부: 승인된 사장님 확인 (D · E가 씀)

- 사장님 기능의 Controller는 `StoreService.requireApprovedOwnStore(me)`로 가게 ID를 얻는다. 사장님이 아니거나 가게가 참여 가게(`approved` · `withdraw_requested`)가 아니면 `403 forbidden_role`(FR-AUTH-04 #1, FR-STO-03 #3). 가게 ID는 세션 계정에서만 정하고 요청에서 받지 않는다.
- 경로에 가게 ID가 있으면 `StoreService.requireApprovedOwnStore(me, storeId)` — 다른 가게면 `404 not_found`(IDOR).

### 가게 목록 · 위치 (C6)

권역의 참여 가게 목록(FR-STO-02). 손님 지도(G-01)와 상인회 가게 관리(A-05)가 같이 쓴다. 목록 · 지도 위치 · 주소는 상인회가 관리하고, 영업 상태 · 예약 받기 · 영업시간 · 휴무일은 사장님이 바꾼다(C22).

**가게 `Store`**

```json
{
  "id": "string", "regionId": "string", "name": "string", "category": "cafe", "alley": "center",
  "mapX": 30.0, "mapY": 30.0, "realLat": null, "realLng": null, "address": "string | null",
  "openTime": "10:00", "closeTime": "22:00", "closedDays": ["mon"],
  "businessStatus": "open", "reservationEnabled": true, "reservable": true, "joinStatus": "approved"
}
```

- `category` · `alley` · `closedDays` · `businessStatus` · `joinStatus`: `CONTRACTS.md` 「3」 `store.*`.
- `mapX` · `mapY`: 골목 안 표시 좌표(비어 있으면 `null`). `realLat` · `realLng`: 검증된 실제 좌표만, 아니면 `null`.
- `openTime` · `closeTime`: `HH:mm`(Asia/Seoul 벽시계). 승인 전 값이 없으면 `null`.
- `reservable`: 지금 예약을 받는 가게인지 — `businessStatus`가 `open`이고 `reservationEnabled`가 `true`. 저장하지 않고 계산한다. 슬롯 · 영업시간 계산은 D5.

#### `GET /api/v1/regions` — 권역 목록

- 역할: 누구나(로그인 불필요). 권역 선택은 v1 제외라, 화면(가게 가입 신청 O-11 · 손님 지도 G-01)은 고르는 UI 없이 이 목록에서 권역 ID를 얻는다. v1은 권역이 1개다(#214).
- 응답 `200`: `{ "items": [{ "id": "string", "name": "string" }] }` — ID순, 페이지 없음(공통 페이지네이션 규칙의 예외).
- 실패: 없음

#### `GET /api/v1/stores` — 권역의 참여 가게 목록

- 역할: 누구나(로그인 불필요).
- 쿼리: `regionId`(필수).
- 참여 가게만: `joinStatus`가 `approved` · `withdraw_requested`. ID순.
- 응답 `200`: `{ "items": [Store] }` — 권역 가게 전체를 한 번에 준다(페이지 없음, 권역당 수십 곳이라 지도가 전부 필요). 공통 페이지네이션 규칙의 예외.
- 실패: `invalid`(`regionId` 없음 · 형식) · `not_found`(없는 권역)

#### `PUT /api/v1/stores/{storeId}/location` — 가게 위치 · 주소 수정

- 역할: 상인회. 자기 권역 가게만(다른 권역 가게는 `404`). 사장님 · 손님은 `403 forbidden_role`(FR-STO-02 #3). `X-XSRF-TOKEN` 필요.
- 요청: `{ "address": "string | null", "alley": "center", "mapX": 30.0, "mapY": 30.0 }` — `address` 200자 이하, `alley` 값 목록 안, `mapX` · `mapY` −999.9999 ~ 999.9999(소수 넷째 자리까지) 또는 `null`.
- 응답 `200`: `Store`
- 실패: `invalid` · `not_found` · `unauthorized` · `forbidden_role` · `csrf_invalid`

### 사장님 내 가게 (C22)

사장님이 자기 가게의 영업 상태 · 예약 받기(O-03)와 영업시간 · 휴무일(O-12)을 바꾼다(FR-STO-02). 가게는 세션 계정에서만 정하고 경로 · 요청에서 받지 않는다. 주소 · 지도 위치는 상인회만 바꾼다(위 `PUT /api/v1/stores/{storeId}/location`, FR-STO-02 #3).

- 역할: 사장님, 참여 가게(승인 · 탈퇴 신청 중)만(`StoreService.requireApprovedOwnStore`). 손님 · 상인회 · 승인 전 · 거절 · 탈퇴 가게의 사장님은 `403 forbidden_role`. `PUT`은 `X-XSRF-TOKEN` 필요.
- 응답 `200`: 세 API 모두 위 `Store`.

#### `GET /api/v1/owner/store` — 내 가게

- 실패: `unauthorized` · `forbidden_role`

#### `PUT /api/v1/owner/store/status` — 영업 상태 · 예약 받기

- 요청: `{ "businessStatus": "paused", "reservationEnabled": true }` — 둘 다 필수. `businessStatus`는 `CONTRACTS.md` 「3」 `store.businessStatus`(`open` · `paused` · `closed`).
- 바뀐 값은 바로 반영된다: 추천 후보 · 예약 신청(단계 30) · 예약 가능 시간 · 쿠폰 발급이 모두 `businessStatus = open`이고 `reservationEnabled = true`인 가게만 받는다(FR-STO-02 #1 · #2). 이미 받은 예약은 그대로 둔다.
- `businessStatus`가 실제로 바뀌면 `store.business_status_changed_at`에 지금 시각(서버 시계)을 남긴다. 쿠폰 30일 제한 해제(3.4 「휴업 뒤 제한 해제」)가 이 시각 전 다운로드를 뺀다. 같은 값이면 시각을 바꾸지 않는다.
- `businessStatus`가 실제로 바뀌면 같은 트랜잭션(커밋 전)에서 `StoreBusinessStatusChangedEvent(storeId, businessStatus)`를 내보낸다. 휴업 · 운영 중단 때 그 가게 미사용 쿠폰의 적용 취소(FR-STO-02 #1, FR-CPN-07 #4)는 E14가 `@EventListener`로 받아 처리한다. 예약 받기만 멈춘 것은 이벤트가 없고 쿠폰도 그대로다(FR-STO-02 #2).
- 실패: `invalid` · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `PUT /api/v1/owner/store/hours` — 영업시간 · 휴무일

- 요청: `{ "openTime": "10:00", "closeTime": "22:00", "closedDays": ["mon"] }` — 규칙은 가게 가입 신청의 `StoreJoinInfo`와 같다(`openTime` < `closeTime`, `closedDays` 생략 가능 · 같은 요일 중복 불가). 휴무일은 요일 순서(mon → sun)로 저장 · 응답한다.
- 예약 가능 시간(D5 「슬롯 계산」)과 예약 신청 검사가 바뀐 값을 바로 쓴다(FR-STO-02 #4). 이미 받은 예약은 그대로 둔다.
- 실패: `invalid` · `unauthorized` · `forbidden_role` · `csrf_invalid`

### 고객 목록 · 고객 카드 · 손님 등급 (C9 · C10)

사장님이 우리 가게 손님을 보고 메모를 남긴다(O-04 · O-05, FR-CUS-03 · 04). 가게는 세션 계정에서만 정한다(`requireApprovedOwnStore`). 상인회 · 손님은 `403 forbidden_role`(상인회에는 고객 카드가 없다, FR-CUS-03 #2).

- **우리 가게 손님** = 우리 가게에 예약(상태 무관)이 한 건이라도 있는 손님. 아니면 `404 not_found`(IDOR).
- **손님 등급** `grade`(`CONTRACTS.md` 「3」 `customer.grade`): 우리 가게 **방문 처리**(`visited`) 횟수로 센다(취소 · 신청 · 다른 가게 방문은 세지 않음, FR-CUS-04 #3 · #4). 1회 `new` 신규, 2회 `regular` 일반, 3회 이상 `loyal` 단골, 단골인데 마지막 방문 후 60일이 넘게 지남 `at_risk` 이탈 위험. 방문 처리가 없으면 `null`. 저장하지 않고 조회 때 계산한다(서버 시계 기준).
- **보이지 않는 것**(FR-CUS-05): 전화번호 · 이름 전체 · 다른 가게 방문 · 보낸 가게. 이름은 사장님 예약 목록(D3)과 같은 가린 이름 `maskedName`만.
- **파기된 비회원**(FR-CUS-07): `purged: true`, `maskedName` · `memo`는 `null`. 방문 이력과 등급은 남는다.

**손님 `CustomerSummary`**

```json
{ "customerId": "string", "customerType": "guest", "maskedName": "김*코", "grade": "regular", "visitCount": 2,
  "lastVisitedAt": "2026-11-26T10:00:00Z", "recommendedVisitCount": 1, "purged": false }
```

- `visitCount` · `lastVisitedAt`: 우리 가게 방문 처리 횟수 · 마지막 방문 처리 시각.
- `recommendedVisitCount`: 그중 정식 코스 제안으로 온 예약(`fromRecommendation`과 같은 뜻) 수.

#### `GET /api/v1/owner/customers` — 고객 목록 (O-04)

- 쿼리: `grade`(선택, 위 4개 값) + 페이지네이션(공통 규칙). 최근 예약 순.
- 응답 `200`: 공통 페이지, `items[]` = `CustomerSummary`
- 실패: `invalid`(grade 값 · page · size) · `unauthorized` · `forbidden_role`

#### `GET /api/v1/owner/customers/{customerId}` — 고객 카드 (O-05)

- 응답 `200`: `{ "customer": CustomerSummary, "memo": "string | null", "visits": [Visit] }`
  - `Visit` = `{ "reservationId", "reservationNo", "startAt", "visitedAt", "partySize", "visitAmount", "fromRecommendation" }` — 우리 가게 방문 처리된 예약만, 최근 방문부터.
  - `memo`: 우리 가게 메모(가게마다 따로).
- 실패: `not_found`(우리 가게 손님이 아님) · `unauthorized` · `forbidden_role`

#### `PUT /api/v1/owner/customers/{customerId}/memo` — 메모 쓰기 · 고치기

- `X-XSRF-TOKEN` 필요. 요청: `{ "memo": "string" }` — 앞뒤 공백을 빼고 500자 이하. `null` · 빈 값이면 메모를 지운다.
- 응답 `200`: 고객 카드(위와 같은 모양)
- 실패: `invalid`(500자 초과 · 본문 없음) · `not_found` · `conflict`(파기된 비회원) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 서버 내부: `CustomerCardService.grades(storeId, customerIds)` (D가 쓸 수 있음)

- 그 가게 기준 손님별 등급 `Map<Long, String>`. 방문 처리가 없는 손님은 빠진다. 사장님 예약 목록(D3)에 등급을 더할 때 쓴다(N+1 없이 한 번에).

### 비회원 예약 조회 (C13)

비회원은 계정이 없어서 예약번호 + 전화번호로 확인하고, **그 전화번호의 비회원 예약만** 다루는 제한 세션을 받는다(FR-AUTH-03, G-13). 세션 규칙은 `CONTRACTS.md` 「6」 「비회원 제한 세션」.

#### `POST /api/v1/guest/lookup` — 비회원 예약 확인 · 제한 세션 시작

- 역할: 누구나(로그인 불필요). `X-XSRF-TOKEN` 필요.
- 요청: `{ "reservationNo": "string", "phone": "01012345678" }` — `phone`은 예약 신청의 비회원 `guest.phone`과 같은 형식(숫자 10 ~ 11자). 예약번호는 대소문자를 구분하지 않는다.
- 맞으면 세션 ID를 새로 발급하고(세션 고정 방지) 제한 세션을 시작한다. 이미 로그인한 계정 세션이었다면 제한 세션으로 바뀐다. 끝낼 때는 `POST /api/v1/auth/logout`.
- 응답 `200`: 그 예약(`Reservation`, D2 「예약 응답」. 비회원이라 `coupon`은 `null`). 손님 이름 · 전화번호는 응답에 없다.
- 실패: `invalid`(형식) · `unauthorized`(예약번호가 없거나 전화번호가 다름 — 둘을 구분하지 않는다, FR-AUTH-03 #3. 회원 예약 · 파기된 비회원 예약도 같다) · `csrf_invalid`

#### `GET /api/v1/guest/reservations` — 내 비회원 예약 목록

- 역할: 비회원 제한 세션만. 로그인 안 함 `401 unauthorized`, 계정 세션 `403 forbidden_role`.
- 응답 `200`: `{ "items": [Reservation + 취소 안내] }` — 그 전화번호의 (파기되지 않은) 비회원 예약 전체, 방문 일시가 늦은 것부터. 페이지 없음(비회원 한 명의 예약 수).
  - 항목은 `Reservation`의 칸 그대로에 **`cancelable`**(지금 취소할 수 있는지: 신청 · 확정이고 가게 취소 마감 전) · **`refundIfCanceled`**(지금 취소하면 돌려받는 예약금, 취소할 수 없으면 `null`)를 더한 것. 회원 내 예약(D13)과 같은 뜻 · 같은 D6 규칙이다.
  - 취소 · 변경은 아래 「비회원 취소 · 변경 (D6 · D8)」.

#### 다른 기능이 제한 세션을 받는 법 (서버 내부)

- 예약 변경 · 취소(D), 정식 코스 제안(3.1), 지도 추천의 '같은 날 제외'(3.1) 등 비회원 제한 세션을 받는 기능은 `@AuthenticationPrincipal GuestPrincipal`(또는 principal 타입 확인)으로 세션을 알아보고, 대상 예약이 `GuestSessionService.reservationIds(guest)` 안에 없으면 `404 not_found`(IDOR).
- 제한 세션으로 새 로그인 필요 경로를 열려면 `SecurityConfig`의 비회원 허용 경로에 더한다(C 확인).
- 예약 신청(D2)은 제한 세션에서도 비회원 신청이다(`GuestPrincipal`은 계정 이름이 없다).

### 정보 공유 설정 (C11)

상인회가 권역의 가게 간 공유 항목을 켜고 끈다(A-08, FR-CUS-06). 항목 값은 `CONTRACTS.md` 「3」 `shareItem`. 이름 · 연락처 · 방문한 가게 목록은 **항상 공유하는 필수 항목**이라 끌 수 없다(11-24 (5)). 가게(사장님)는 바꿀 수 없다.

- 바꾼 값은 **그 뒤 예약**의 공유 동의 기록(C8, 예약 신청 단계 82)에 바로 쓰인다. 이미 남은 동의 기록은 바꾸지 않는다.
- `gender_age_group`이 꺼진 권역에서는 추천(E, `RecommendationService`)이 회원의 성별 · 연령대를 쓰지 않는다. 다른 손님 방문 기록과 성별 · 연령대를 비교하는 것도 빠진다(FR-CUS-06 #1).
- 다른 기능이 항목 공유 여부를 볼 때는 `ShareConsentService.isShared(regionId, item)`을 쓴다(서버 내부).
- 공유 항목 `SharePolicyItem`: `{ "item": "gender_age_group", "enabled": true, "required": false }` — `required`가 `true`면 끌 수 없다.

#### `GET /api/v1/association/share-policy` — 공유 설정 보기

- 역할: 상인회(자기 권역). 사장님 · 손님은 `403 forbidden_role`.
- 응답 `200`: `{ "items": [SharePolicyItem] }` — 6개, `name` · `phone` · `visited_stores` · `party_size` · `visit_datetime` · `gender_age_group` 순서. 처음에는 모두 켜짐.
- 실패: `unauthorized` · `forbidden_role`

#### `PUT /api/v1/association/share-policy/{item}` — 항목 하나 켜기 · 끄기

- 역할: 상인회(자기 권역). `X-XSRF-TOKEN` 필요.
- 요청: `{ "enabled": false }` (필수)
- 응답 `200`: `GET`과 같은 전체 목록
- 실패:
  - `invalid` — 없는 항목 · `enabled` 없음
  - `conflict` — 필수 항목을 끄려 함(FR-CUS-06 #2 · #3). 필수 항목을 켜는 요청은 그대로 `200`
  - `unauthorized` · `forbidden_role` · `csrf_invalid`

### 알림 (C14)

앱 · 웹 안 알림함(G-11 손님 · O-10 사장님). 실제 문자 · 카카오 발송은 없다. 알림을 받는 쪽은 계정(회원 · 사장님) 또는 비회원 예약이다(`CONTRACTS.md` 「3」 `notification.recipientType`).

**알림 `Notification`**

```json
{ "id": "string", "type": "reservation_requested", "payload": { "reservationId": "string" }, "readAt": null, "createdAt": "2026-11-27T01:00:00Z" }
```

- `type`: `CONTRACTS.md` 「3」 `notification.type`. 화면 문구는 프론트가 `type`으로 정한다.
- `payload`: 종류마다 다른 JSON 객체. 화면이 이동 · 표시에 쓰는 ID와 값만 넣고 **손님 개인정보(이름 · 전화번호)와 보낸 가게는 넣지 않는다**(FR-CUS-05). ID는 문자열.
- `readAt`: 읽은 시각, 안 읽었으면 `null`.

| `type` | 받는 쪽 | `payload` | 만드는 곳 |
| --- | --- | --- | --- |
| `reservation_requested` | 그 가게 사장님 | `{ "reservationId" }` | 예약 신청 커밋 뒤(`ReservationRequestedEvent`) |
| `reservation_confirmed` | 예약한 손님 | `{ "reservationId" }` | 승인 커밋 뒤(`ReservationStatusChangedEvent` → `confirmed`) |
| `reservation_rejected` | 예약한 손님 | `{ "reservationId", "nextCourseReservationIds": ["string"] }` | 거절 · 자동 거절 커밋 뒤(`canceled` + `store_rejected`). `nextCourseReservationIds`는 이 예약을 기준 가게 예약으로 둔 다음 코스 예약 중 그대로 남은(신청 · 확정) 것, 없으면 `[]`(FR-NTF-03 #2) |
| `reservation_canceled` | 예약한 손님 | `{ "reservationId" }` | 자동 취소 커밋 뒤(`canceled` + `auto_canceled`) |
| `reservation_canceled` | 그 가게 사장님 | `{ "reservationId" }` | 손님이 스스로 취소한 커밋 뒤(`canceled` + `customer_canceled`, FR-NTF-01 #2). 손님 본인에게는 만들지 않는다 |
| `change_requested` | 그 가게 사장님 | `{ "reservationId" }` | 손님 변경 요청 커밋 뒤(D8, 이벤트 `change = requested`) |
| `change_approved` · `change_rejected` | 예약한 손님 | `{ "reservationId" }` | 변경 승인 · 거절(자동 거절 포함) 커밋 뒤(D8, 이벤트 `change = approved` · `rejected`). 이때는 `reservation_confirmed`를 만들지 않는다 |
| `reminder` | 예약한 손님 | `{ "reservationId" }` | 자동 작업 `reservation_reminder`: 방문 일시가 30분 안으로 들어온 확정 예약마다 한 번. 화면은 이 알림에서 늦을 것 같아요(G-07)로 보낸다(FR-NTF-03 #3) |
| `store_join_result` | 그 가게 사장님 | `{ "storeId", "joinStatus" }` | 가입 승인 · 거절 커밋 뒤(`StoreJoinDecidedEvent`) |
| `association_coupon_opened` | 회원 계정 전부 | `{ "associationCouponEventId" }` | 3.9 「행사 알림」 |
| `coupon_status` | 회원 | `{ "couponId", "status" }` (`used` · `expired`) | 사용 확인 · 만료 작업 커밋 뒤(`CouponStatusChangedEvent`, 3.6 「쿠폰 상태 알림」), C14 `CouponStatusNotificationListener`(#305). 비회원은 쿠폰이 없어 만들지 않는다(FR-NTF-03 #4 · #5) |
| `visit_report_check_requested` | 그 가게 사장님 | `{ "visitReportId", "reservationId" }` | 상인회 신고 확인 요청 커밋 뒤(`VisitReportOwnerCheckRequestedEvent`, 「방문 신고 (C23)」) |
| `delay_reported` | 그 가게 사장님 | `{ "reservationId", "startAt", "expectedArrivalAt" }` | 지연 입력 커밋 뒤(D14, 2절 「늦을 것 같아요」) |
| `visit_amount_recorded` | 회원만 | 2절 「방문 금액 알림」 | 방문 처리 커밋 뒤(D21) |
| `next_store_recommended` | 그 예약의 손님(회원 계정 · 비회원 예약) | `{ "reservationId", "baseStoreId" }` | 예약 신청 커밋 뒤 E 계산이 후보가 있을 때 내보내는 `NextStoreRecommendedEvent`(3.11), C14 `ReservationNotificationListener`(#298) |

"예약한 손님"은 회원이면 그 계정(`account`), 비회원이면 그 예약(`guest_reservation`)이다. 기능 쪽은 `notifyReservationCustomer(reservationId, customerId, type, payload)`로 같은 규칙을 쓴다.

#### `GET /api/v1/owner/notifications/stream` — 사장님 실시간 알림 (C15, SSE)

- 역할: 사장님(`requireApprovedOwnStore`). 세션 쿠키로 인증한다(`EventSource`는 쿠키를 같이 보낸다). 세션이 없으면 `401 unauthorized`, 사장님이 아니면 `403 forbidden_role`(오류는 `Accept`와 상관없이 JSON). `GET`이라 CSRF 토큰은 필요 없다.
- 응답 `200` `text/event-stream`. 연결되면 주석 한 줄(`:connected`)을 먼저 보낸다.
- **그 사장님 계정에 알림이 저장되고 커밋될 때마다** 이벤트 하나를 보낸다. 다른 가게 · 다른 계정 알림은 보내지 않는다(NFR-05 #3). 롤백된 일에는 보내지 않는다.
  ```text
  event: notification
  id: <알림 id>
  data: {"id":"12","type":"reservation_requested","payload":{"reservationId":"34"},"readAt":null,"createdAt":"…Z"}
  ```
  - `data`는 위 `Notification`과 같다. 화면(O-01)은 `reservation_requested` · `change_requested` · `reservation_canceled` · `delay_reported`를 받으면 예약 목록을 다시 불러오면 된다(FR-NTF-01 #1).
  - 연결이 없을 때 생긴 알림은 다시 보내지 않는다. 알림함(`GET /api/v1/notifications`)에서 본다.
- 연결은 30분 뒤 서버가 닫는다. 브라우저 `EventSource`가 다시 연결한다.

#### `GET /api/v1/notifications` — 내 알림 목록

- 역할: 로그인한 계정(회원 · 사장님 · 상인회), 비회원 제한 세션(C13). 받는 쪽은 세션으로만 정하고 ID를 받지 않는다. 비회원 제한 세션이면 그 전화번호 비회원 예약들의 알림(`recipientType = guest_reservation`)을 모아 준다.
- 페이지네이션(공통 규칙), 최근 것부터.
- 응답 `200`: 공통 페이지 + `unreadCount`(종 아이콘 표시용, 전체 안 읽은 수)
  `{ "items": [Notification], "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "unreadCount": 2 }`
- 실패: `invalid`(page · size 범위) · `unauthorized`

#### `POST /api/v1/notifications/{notificationId}/read` — 읽음 처리

- 역할: 그 알림을 받은 계정, 또는 그 알림의 예약을 다루는 비회원 제한 세션. 이미 읽은 알림이면 그대로 둔다(처음 읽은 시각 유지).
- 응답 `204`
- 실패: `not_found`(없는 알림 · 남의 알림) · `unauthorized` · `csrf_invalid`

#### 서버 내부: `NotificationService` (D · E가 씀)

- 알림은 `NotificationService`로만 만든다. `notification` 테이블에 직접 넣지 않는다.
- `notifyAccount(accountId, type, payload)` · `notifyGuestReservation(reservationId, type, payload)` · `notifyReservationCustomer(reservationId, customerId, type, payload)`(예약한 손님에게, 회원 · 비회원을 알아서 고른다). `payload`는 `Map<String, Object>`.
- **트랜잭션 커밋 뒤에 만든다.** 기능 쪽은 이벤트를 내보내고, C14가 `@TransactionalEventListener`(커밋 뒤)로 받아 알림을 만든다. 롤백된 일에는 알림이 남지 않는다.
- 생성 시각은 서버 시계(D18)를 따른다.

### 방문 신고 (C23)

회원의 '금액이 달라요'(금액 신고)와 '방문했어요'(미처리 신고)를 받아, 상인회가 사장님에게 확인을 요청하고 결과를 기록한다(FR-STL-08, 05-policy 「방문 금액 확인 · 신고」). 표는 `visit_report`, 값은 `CONTRACTS.md` 「3」 `visitReport.*`. 결과가 '맞음'인 뒤의 금액 정정 · 늦은 방문 처리는 D22 · D23이 한다.

- 상태: `received` 접수 → (상인회) `owner_check_requested` 사장님 확인 요청 → (상인회) `done` 처리 완료 + `result`(`valid` 맞음 · `invalid` 아님). 되돌리지 않는다. 허위 신고 페널티는 없다.
- **그 달**: 신고된 예약의 시작 시각이 속한 달(운영 시간대 Asia/Seoul). 월 정산(D17)과 같은 기준이다. 그 달 정산이 확정되면 신고를 받지 않고, 처리 완료되지 않은 신고가 있으면 그 달을 확정할 수 없다(`unprocessedReportCount`).
- **상인회 · 사장님 응답에는 손님 이름 · 연락처 · 손님 ID를 넣지 않는다**(FR-STL-08 #5, NFR-03의 신고 건 예외).

**신고 건 `VisitReport`** (상인회 · 사장님)

```json
{
  "id": "string", "type": "amount", "status": "owner_check_requested", "result": null,
  "storeId": "string", "storeName": "string", "reservationId": "string", "reservationNo": "string",
  "startAt": "2026-11-20T10:00:00Z", "month": "2026-11", "visitAmount": 30000, "claimedAmount": 25000,
  "ownerReply": "string | null", "ownerRepliedAt": null, "createdAt": "2026-12-02T01:00:00Z", "handledAt": null
}
```

- `startAt`: 예약(방문) 일시. `month`: 정산에 들어가는 달 `YYYY-MM`.
- `visitAmount`: 사장님이 넣은 방문 금액(쿠폰 할인 전). 방문 처리되지 않은 예약(미처리 신고)이면 `null`.
- `claimedAmount`: 손님이 신고한 금액. 금액 신고에서만, 선택. 미처리 신고는 `null`.

**내 신고 `MyVisitReport`** (회원) — `{ "id", "reservationId", "type", "claimedAmount", "status", "result", "createdAt", "handledAt" }`. 사장님 답변은 넣지 않는다.

#### `POST /api/v1/reservations/{reservationId}/visit-reports` — 신고하기 (회원)

- 역할: 회원(손님 계정), 자기 예약만(남의 예약 · 없는 예약은 `404`). 비회원 제한 세션 · 사장님 · 상인회는 `403 forbidden_role`. `X-XSRF-TOKEN` 필요.
- 요청: `{ "type": "amount", "claimedAmount": 25000 }` — `type`은 `amount` · `not_processed`. `claimedAmount`는 0 ~ 10,000,000, 금액 신고에서만 쓰고 생략 가능(미처리 신고에서는 무시).
- 받는 예약: 금액 신고는 `visited`, 미처리 신고는 `canceled` + `auto_canceled`만(FR-STL-08 #3).
- 같은 예약 · 같은 종류 신고는 1건이다(처리 완료 뒤에도 다시 낼 수 없다).
- 응답 `201`: `MyVisitReport`(`status: "received"`)
- 실패: `invalid` · `not_found` · `conflict`(받을 수 없는 예약 상태 · 그 달 정산 확정됨 · 이미 신고함) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `GET /api/v1/visit-reports/mine` — 내 신고 (회원)

- 역할: 회원. 내 예약(G-04)에 신고 여부 · 결과를 보일 때 `reservationId`로 맞춘다.
- 응답 `200`: `{ "items": [MyVisitReport] }` — 최근 것부터, 페이지 없음.
- 실패: `unauthorized` · `forbidden_role`

#### `GET /api/v1/association/visit-reports` — 신고 목록 (상인회, A-11)

- 역할: 상인회. 자기 권역 가게 예약의 신고만.
- 쿼리: `status`(선택, 없으면 전부) + 페이지네이션(공통 규칙). 오래된 신고부터(처리할 순서).
- 응답 `200`: 공통 페이지, `items[]` = `VisitReport`
- 실패: `invalid`(status 값 · page · size) · `unauthorized` · `forbidden_role`

#### `POST /api/v1/association/visit-reports/{reportId}/owner-check` — 사장님에게 확인 요청 (상인회)

- 역할: 상인회. 다른 권역 신고는 `404`. `X-XSRF-TOKEN` 필요. 요청 본문 없음.
- `received`만 `owner_check_requested`로 바꾼다. 커밋 뒤 그 가게 사장님에게 `visit_report_check_requested` 알림(`{ "visitReportId", "reservationId" }`).
- 응답 `200`: `VisitReport`
- 실패: `not_found` · `conflict`(접수 상태가 아님) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `POST /api/v1/association/visit-reports/{reportId}/result` — 결과 기록 (상인회)

- 역할: 상인회. 다른 권역 신고는 `404`. `X-XSRF-TOKEN` 필요.
- 요청: `{ "result": "valid" }` — `valid` 맞음 · `invalid` 아님.
- `owner_check_requested`만 `done`으로 바꾸고 `result` · `handledAt`을 남긴다. 사장님 답변이 없어도 기록할 수 있다. 금액 · 예약은 바꾸지 않는다(FR-STL-08 #4).
- 응답 `200`: `VisitReport`
- 실패: `invalid` · `not_found` · `conflict`(확인 요청 상태가 아님 · 이미 처리됨) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `GET /api/v1/owner/visit-reports` — 내 가게 신고 (사장님, O-10 · O-13)

- 역할: 사장님(`requireApprovedOwnStore`). 확인 요청을 받은 신고(`owner_check_requested` · `done`)만. 접수만 된 신고는 보이지 않는다.
- 페이지네이션(공통 규칙), 최근 것부터. 응답 `200`: 공통 페이지, `items[]` = `VisitReport`
- 실패: `invalid` · `unauthorized` · `forbidden_role`

#### `POST /api/v1/owner/visit-reports/{reportId}/reply` — 사장님 답변

- 역할: 사장님, 내 가게 신고만(다른 가게는 `404`). `X-XSRF-TOKEN` 필요.
- 요청: `{ "reply": "string" }` — 앞뒤 공백을 빼고 1 ~ 200자. `owner_check_requested`인 동안만, 다시 보내면 고쳐 쓴다. 상인회가 `ownerReply`로 본다.
- 응답 `200`: `VisitReport`
- 실패: `invalid` · `not_found` · `conflict`(확인 요청 상태가 아님) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 서버 내부: `VisitReportService.hasValidReport(reservationId, type)` (D22 · D23이 씀)

- 그 예약에 상인회가 '맞음'(`valid`)으로 기록한 그 종류 신고가 있으면 `true`. 금액 정정(D22)은 `"amount"`, 늦은 방문 처리(D23)는 `"not_processed"`로 확인하고, 없으면 거절한다(FR-STL-09 #4). 상수는 `VisitReport.TYPE_AMOUNT` · `VisitReport.TYPE_NOT_PROCESSED`.

### 분석 공통: 5명 미만 숨김 (C20)

- 상인회 화면(FR-ANL-03 · 05)과 사장님 손님 흐름(FR-ANL-02)의 묶음 숫자는 **고유 인원이 1 ~ 4명이면 숨긴다**. 응답 모양은 `{ "count": 12, "belowMinimum": false }`이고, 숨기면 `{ "count": null, "belowMinimum": true }`다. 화면은 `belowMinimum`이면 '5명 미만'으로 보여준다.
- 0명(기록 없음)은 아무도 드러내지 않으므로 `count: 0`으로 준다.
- 기준 5명은 정해진 값이다(`MinimumGroup.MIN_PEOPLE`). 사장님 세그먼트(FR-ANL-01)에는 쓰지 않는다.
- 집계는 네이티브 SQL로 다른 도메인 표를 읽기만 한다. 응답에 손님 · 방문자 단위 값(ID 등)을 넣지 않는다.

### 상인회 방문 흐름 (C20)

#### `GET /api/v1/association/visit-flows` — 방문 흐름 (A-03)

- 역할: 상인회(자기 권역). 사장님 · 손님은 `403 forbidden_role`.
- 응답 `200`:

  ```json
  {
    "links": [
      { "fromStoreId": "3", "toStoreId": "7",
        "moves": { "count": 12, "belowMinimum": false },
        "exposures": { "count": null, "belowMinimum": true } }
    ],
    "top": [ /* links와 같은 모양, 최대 3개 */ ]
  }
  ```

  - `links`: 참여 가게(승인 · 탈퇴 신청 중) 사이의 **방향 있는** 연결 중 이동이나 노출 기록이 있는 것만, 가게 ID 순. 기록이 없는 나머지 선은 화면이 가게 목록(`GET /api/v1/stores`)으로 그린다. 탈퇴한 가게가 낀 연결은 넣지 않는다(기록은 지우지 않음).
  - `moves`(확인된 연계 방문 수, 선 굵기): 같은 손님이 **같은 날**(예약 시작 시각, Asia/Seoul) 두 가게를 **연달아 방문 처리**한 횟수. 날짜 · 순서 기준은 추천 실제 기록 점수(E6)와 같다. 한쪽이라도 취소 · 미방문이면 세지 않는다(FR-ANL-03 #2). 같은 가게를 연달아 간 것은 이동이 아니다. 고유 손님 수로 숨김을 정한다.
  - `exposures`(추천 노출 수): `fromStoreId`를 기준 가게로 `toStoreId`를 추천 카드에 보여준 횟수(3.1 노출 기록). 고유 방문자 수로 숨김을 정한다. 확인된 이동과 따로 보여준다(FR-ANL-03 #4).
  - `top`(인기 연결 TOP 3): 숨기지 않은 연결 중 `moves`가 많은 순(같으면 가게 ID 순), 최대 3개(FR-ANL-03 #3).
- 기간은 전체다. 업종별 이동 비율 · 시간대별 예약(FR-ANL-08, 여유)은 이 API에 없다.
- 실패: `unauthorized` · `forbidden_role`

### 사장님 손님 흐름 (C17)

#### `GET /api/v1/owner/customer-flows` — 우리 가게 손님 흐름 (O-07)

- 역할: 사장님(`requireApprovedOwnStore`, 내 가게). 상인회 · 손님은 `403 forbidden_role`.
- 응답 `200`:

  ```json
  {
    "inbound":  [ { "storeId": "3", "storeName": "string", "moves": { "count": 6, "belowMinimum": false } } ],
    "outbound": [ { "storeId": "7", "storeName": "string", "moves": { "count": null, "belowMinimum": true } } ]
  }
  ```

  - `inbound`: 다른 가게에 들렀다가 같은 날 이어서 우리 가게에 온 이동. `outbound`: 우리 가게 뒤 같은 날 이어서 다른 가게로 간 이동. 이동 정의는 「상인회 방문 흐름 (C20)」의 `moves`와 같다.
  - 다른 가게가 드러나므로 고유 손님 1 ~ 4명 묶음은 「분석 공통: 5명 미만 숨김」대로 숨긴다(FR-ANL-02 #1).
  - 참여 가게(승인 · 탈퇴 신청 중)만, 기록이 있는 가게만. 순서는 보이는 묶음을 많은 순으로 먼저, 숨긴 묶음은 뒤에 가게 ID 순(숨긴 값의 크기가 순서로 드러나지 않게).
  - 묶음 숫자만 준다. 손님 단위 값은 없다(FR-CUS-05). 기간은 전체.
- 실패: `unauthorized` · `forbidden_role`

### 사장님 세그먼트 분석 (C16)

#### `GET /api/v1/owner/segments` — 우리 가게 세그먼트 (O-06)

- 역할: 사장님(`requireApprovedOwnStore`, 내 가게). 상인회 · 손님은 `403 forbidden_role`.
- 대상: 우리 가게를 한 번이라도 **방문 처리**한 손님(취소 · 신청은 세지 않음).
- 기준(FR-ANL-01): 손님마다 '이용한 전체 가게 중 우리 가게를 이용한 비율' = 우리 가게 방문 처리 수 ÷ 권역 전체 방문 처리 수(탈퇴한 가게 방문도 포함). 이 비율로 네 묶음에 나눈다.

  | `segment` | 비율 |
  | --- | --- |
  | `exclusive` 우리 가게만 | 100% |
  | `mostly` 주로 우리 가게 | 50% 이상 100% 미만 |
  | `sometimes` 가끔 | 25% 이상 50% 미만 |
  | `rarely` 드물게 | 25% 미만 |

  경계는 팀이 정한 값이다(`SegmentService.Segment`). 경계값은 위 구간에 든다(정확히 50%는 `mostly`).
- 응답 `200`: `{ "totalCustomers": 7, "segments": [ { "segment": "exclusive", "customerCount": 3, "visitCount": 4 }, … ] }` — 네 묶음을 위 순서로 항상 준다(없으면 0).
  - `customerCount`: 그 묶음 손님 수. `visitCount`: 그 묶음 손님들의 우리 가게 방문 처리 수.
  - **5명 미만 숨김을 적용하지 않는다**(11-23 (2), FR-ANL-01 #1). 묶음 숫자만이고 다른 가게 이름 · 손님 단위 값은 없다.
- 기간은 전체.
- 실패: `unauthorized` · `forbidden_role`

### 상인회 성공 지표 (C18)

#### `GET /api/v1/association/success-metrics?month=2026-11` — 성공 지표 (A-01)

- 역할: 상인회(세션 계정의 권역). 사장님 · 손님은 `403 forbidden_role`.
- `month`(선택, `YYYY-MM`): 수수료 총액의 달. 없으면 서버 시각 기준 이번 달(운영 시간대). 수락률 · 재방문율은 전체 기간이다.
- 응답 `200`:

  ```json
  {
    "acceptance": { "shownProposals": 200, "acceptedProposals": 30, "rate": 0.15 },
    "revisit": {
      "revisitDays": 60,
      "region": { "customers": 6, "revisitedCustomers": 2, "rate": 0.3333, "belowMinimum": false },
      "stores": [ { "storeId": "1", "storeName": "…", "revisit": { "customers": null, "revisitedCustomers": null, "rate": null, "belowMinimum": true } } ]
    },
    "referralFee": { "month": "2026-11", "amount": 2000 }
  }
  ```

- **코스 제안 수락률**(FR-ANL-06 #1 ~ #3)
  - 분모 `shownProposals`: 권역 가게 예약에 표시된 정식 코스 제안 수(`course_proposal`, 같은 기준 예약 · 추천 가게 조합은 1건).
  - 분자 `acceptedProposals`: 그중 코스 예약이 방문 처리되어 소개가 확정된(`referral_fee`가 있는) 제안 수. 한 제안으로 여러 예약이 확정돼도 1이다.
  - `rate`: 분자 ÷ 분모(소수 넷째 자리, 0.15 = 15%). 분모가 0이면 `null`(표시하지 않음). 지도 추천 · 오늘의 코스 노출은 세지 않는다.
- **재방문율**(FR-ANL-06 #4): 방문 처리된 손님 중 60일(`revisit_days` 설정값) 안에 다른 날짜에 다시 방문 처리된 손님의 비율이다.
  - 날짜는 예약 시작 시각의 운영 시간대 날짜다. 같은 날 두 번은 재방문이 아니다. 간격이 정확히 60일이면 재방문이다.
  - `region`은 권역 안 어느 가게든, `stores[].revisit`은 그 가게만 센다.
  - `stores`: 참여 가게(승인 · 탈퇴 신청 중), 이름 순. 방문이 없으면 `0 · 0 · null`.
  - 방문 손님이 1 ~ 4명이면 숫자를 모두 `null`로 숨기고 `belowMinimum: true`다(「분석 공통」).
- **소개 수수료 총액**: 그 달(방문 처리된 달 = `settlement_month`)의 권역 소개 수수료 합계다. 금액 신고 정정 차액을 포함해 월 정산(A-04)과 같은 기준이다. 확정 여부와 관계없다.
- 실패: `invalid`(`month` 형식) · `unauthorized` · `forbidden_role`

### 추가 지표 · 쿠폰 지표 (C21)

#### `GET /api/v1/owner/extra-metrics` — 사장님 추가 지표 (FR-ANL-08 · 09)
#### `GET /api/v1/association/extra-metrics` — 상인회 추가 지표 (FR-ANL-08 · 09)

- 역할: 사장님 경로는 사장님(내 가게, 승인 · 탈퇴 신청 중), 상인회 경로는 상인회(세션 계정의 권역). 다른 역할은 `403 forbidden_role`.
- 범위: 사장님은 내 가게, 상인회는 권역 전체. 기간은 전체 기간이다.
- 응답 `200` (두 경로가 같은 모양):

  ```json
  {
    "funnel": { "clicks": 3, "exposures": 4, "reservations": 7, "visits": 6 },
    "recommendationConversion": { "exposedPairs": 3, "convertedPairs": 1, "rate": 0.3333 },
    "confirmedMoves": { "count": 6, "belowMinimum": false },
    "categoryMoves": [
      { "fromCategory": "cafe", "toCategory": "dessert", "moves": 5, "ratio": 0.8333, "belowMinimum": false },
      { "fromCategory": "dessert", "toCategory": "cafe", "moves": null, "ratio": null, "belowMinimum": true }
    ],
    "hourlyReservations": [ { "dayOfWeek": 2, "hour": 12, "reservations": 5, "belowMinimum": false } ],
    "storeCoupons": {
      "downloads": 3, "applied": 1, "applyRate": 0.3333, "expired": 1, "expireRate": 0.3333,
      "appliedVisited": 1, "visitCompletionRate": 1.0, "used": 1, "belowMinimum": false
    },
    "associationCoupons": null
  }
  ```

- **단계별 건수** `funnel`(FR-ANL-08 #2): 서로 다른 기록에서 따로 센다.
  - `clicks`: 가게 클릭(`store_click`).
  - `exposures`: 이 가게(상인회는 권역 가게)가 지도 추천 카드에 보인 횟수(`recommendation_exposure`의 추천 가게).
  - `reservations`: 모든 상태의 예약 수.
  - `visits`: 방문 처리된 예약 수.
- **추천 후 예약 전환율** `recommendationConversion`
  - 분모 `exposedPairs`: (방문자, 추천 가게) 쌍의 수. 같은 방문자가 같은 가게를 여러 번 봐도 1이다.
  - 분자 `convertedPairs`: 그 쌍을 처음 본 뒤에, 그 방문자와 이어진 손님이 그 가게에 예약을 신청한 쌍. 손님과 이어지지 않은 방문자는 분모에만 든다.
  - 분모가 0이면 `rate: null`. 기간 제한은 없다.
- **확인된 연계 방문** `confirmedMoves`: A-03 방문 흐름과 같은 기준이다(같은 손님이 같은 날 연달아 방문 처리한 서로 다른 두 가게). 사장님은 내 가게가 출발이나 도착인 이동만 센다.
- **업종별 이동 비율** `categoryMoves`: 업종 → 업종 이동 수 ÷ 범위 안 전체 이동 수. 업종 이름 순이다.
- **시간대별 예약** `hourlyReservations`: 예약 시작 시각(운영 시간대)의 요일(`1` 월 ~ `7` 일)과 시(`0` ~ `23`)별 예약 수다. 취소된 예약은 넣지 않는다(FR-ANL-07). 예약이 있는 칸만, 요일 · 시 순서로 준다.
- **쿠폰 지표** `storeCoupons`(가게 쿠폰, 사장님은 내 가게 쿠폰) · `associationCoupons`(상인회 쿠폰 행사, 상인회 경로만. 사장님은 `null`)(FR-ANL-09)
  - `downloads`: 발급된 쿠폰 수. 같은 회원이 여러 번 눌러도 발급된 장수만 센다(FR-ANL-09 #1). 클릭 기록은 섞지 않는다.
  - `applied`: 한 번이라도 예약에 적용된 쿠폰(적용 뒤 사용 · 적용 취소 포함). `applyRate` = `applied` ÷ `downloads`.
  - `expired`: 기간 만료 상태의 쿠폰. `expireRate` = `expired` ÷ `downloads`.
  - `appliedVisited`: 적용된 예약이 방문 처리된 쿠폰. `visitCompletionRate` = `appliedVisited` ÷ `applied`.
  - `used`: 사용 완료 수.
  - 비율은 분모가 0이면 `null`이다.
- **보낸 · 받은 손님 수**는 이 API에 넣지 않았다. 사장님은 `GET /api/v1/owner/referral-stats`(D16), 상인회는 가게 성과 비교(C19)를 쓴다.
- **5명 미만**(「분석 공통」): 숨긴 묶음은 숫자가 `null`이고 `belowMinimum: true`다.
  - 상인회: 이동 · 업종별 이동 · 시간대별 칸 · 쿠폰 묶음마다 고유 손님이 1 ~ 4명이면 숨긴다.
  - 사장님: 다른 가게가 섞이는 이동(`confirmedMoves` · `categoryMoves`)만 숨긴다. 내 가게만의 숫자(단계별 건수 · 전환 · 시간대별 예약 · 쿠폰)는 숨기지 않는다.
  - 단계별 건수 `funnel`과 추천 후 예약 전환율은 상인회에게도 숨기지 않는다(권역 전체 기록 건수).
- 실패: `unauthorized` · `forbidden_role`

## 2. 예약 · 잔액 · 수수료 · 정산 · 서버 시계 (담당 D)

D2 ~ D18. 예약 신청 · 상태 · 승인 · 취소 · 변경 · 코스 예약 · 방문 처리, 가상 잔액 · 예약금, 수수료 · 정산, 시연용 시각 조작.
'방문 처리됨' 이벤트의 이름 · 내용도 이 절에 적는다(D11).

### 시연용 시각 조작 (D18)

`app.demo-tools.enabled=true`(환경 변수 `DEMO_TOOLS_ENABLED=true`)일 때만 있다. 기본은 꺼져 있고, 꺼져 있으면 `404 not_found`. 실제 운영에서는 켜지 않는다. 서버의 모든 시간 규칙(자동 거절 · 자동 취소 · 쿠폰 만료 · 리마인드 · 파기)이 이 시계를 따른다.

응답 `ClockResponse`: `{ "now": "2026-11-28T09:00:00Z", "offsetSeconds": 86400, "jobs": { "auto_reject": 1, "auto_cancel": 0 } }` — 지금 서버 시각, 실제 시각과의 차이(초), 시각을 바꾼 뒤 바로 돈 자동 작업(D7)의 처리 건수(조회 · 되돌리기는 빈 객체, 실패한 작업은 -1)

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
  "coupon": { "couponId": "string", "benefitType": "fixed_discount", "discountAmount": 1000, "discountPercent": null, "maxDiscountAmount": null, "serviceName": null },
  "courseRootId": null,
  "fromRecommendation": false,
  "visitAmount": null,
  "visitedAt": null,
  "refundAmount": null,
  "pendingStartAt": null,
  "pendingPartySize": null
}
```

- `status` · `cancelReason`: `CONTRACTS.md` 「3」 값.
- `reservationNo`: 8자, 영문 대문자 · 숫자(헷갈리는 `0 O 1 I` 제외). 비회원 예약 조회에 쓴다(FR-AUTH-03).
- `depositAmount`: 원 단위. 예약금 없는 가게는 0. 실제 결제 없음(가상 잔액).
- `coupon`: 적용된 쿠폰이 없으면 `null`(비회원은 항상 `null`). 혜택 칸의 뜻은 3.2 CouponEvent와 같다(정률 할인은 `discountAmount: null`). 실제 할인액은 방문 처리 때 결제 금액으로 정한다(E14, FR-CPN-07).
- `courseRootId`: '다음 코스로 예약하기'로 만든 예약만 기준 가게 예약 id(D12). 아니면 `null`.
- `fromRecommendation`: 사장님 화면의 '추천으로 왔어요' 표시용. **보낸 가게 id · 이름은 어떤 응답에도 넣지 않는다**(FR-CUS-05).
- `refundAmount`: 손님 취소(D6) 때 돌려준 예약금(원). 손님 취소가 아니면 `null`.
- `pendingStartAt` · `pendingPartySize`: 변경 신청(D8) 중 바꿀 값. 변경 대기가 아니면 `null`. 대기 중에는 `status`가 `requested`이고 `startAt` · `partySize`는 원래 값이다.
- `visitAmount` · `visitedAt`: 방문 처리(D11) 때 사장님이 넣은 결제 금액(쿠폰 할인 전, 원)과 처리 시각. 방문 전이면 `null`.
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
  - `couponId`: 회원만. 회원이 그 가게의 사용 가능한 쿠폰(3.5) 중 직접 고른 하나. 기본은 '적용 안 함'이라 고르지 않았으면 `null`(FR-CPN-06, 11-25 (3)). 비회원이 보내면 무시한다. 확인 · 실패는 3.5.
  - `courseRootId` · `courseProposalId`: '다음 코스로 예약하기'일 때만(D12). 일반 예약은 `null`.
  - 익명 방문자 ID는 쿠키로 서버가 읽어 손님에 잇는다(E3). 요청 본문에 넣지 않는다.
- 응답 `201`: `Reservation`(`status: "requested"`)
- 실패:
  - `invalid`: 필수 값 없음, `shareConsent`가 `true`가 아님, 비회원 `guest` 없음, 인원이 1 미만이거나 가게 한 팀 최대 인원 초과
  - `not_found`: 없는 가게 · 쿠폰 · 기준 예약(남의 것 포함)
  - `slot_unavailable`: 예약을 받을 수 없는 시각 — 지난 시각, 예약 가능 기간 밖, 영업시간 밖 · 휴무일, 슬롯이 다 참, 가게가 휴업 · 중단 · 예약 받기 멈춤
  - `insufficient_balance`: 회원 잔액이 예약금보다 적음(화면은 충전 안내, FR-RSV-08)
  - `conflict`: 다음 코스의 기준 예약이 취소 · 거절됨, 같은 날 기준 예약 시각과 같거나 이름, 기준 예약과 같은 가게, 정식 제안이 그 기준 예약 · 그 가게 것이 아님(D12, 기획 11-26 (2)), 쓸 수 없는 쿠폰
  - `forbidden_role` · `csrf_invalid`

### 예약 신청 처리 단계 (서버 내부, D2 뼈대)

여러 담당이 같은 신청 코드를 고치지 않도록 단계로 나눈다(기획 저장소 00-backlog 「같이 쓰는 것 규칙」). 각 담당은 **자기 단계 클래스만** 만든다. 한 트랜잭션 안에서 순서대로 돌고, 한 단계라도 실패하면 아무것도 저장되지 않는다.

| 순서 | 단계 | 담당 · 이슈 | 하는 일 |
| --- | --- | --- | --- |
| 10 | 입력 검증 | D2 | 필수 값 · 인원 · 공유 동의 |
| 20 | 손님 식별 | C4 | 회원 → 계정의 손님, 비회원 → 전화번호로 손님 찾기 · 만들기, 익명 방문자 연결 |
| 30 | 예약 가능 확인 | D2 · D5 | 가게 상태 · 영업시간 · 휴무일 · 기간 · 슬롯 · 최대 인원 |
| 40 | 코스 연결 | D12 | 기준 예약(같은 손님 · 취소 아님) · 시간(같은 날은 기준 시각보다 늦게, 겹침 판정 없음) · 정식 제안 확인. 마감(기준 예약일 당일, 신청 시각 기준) 전이면 제안 id · 보낸 가게를 남겨 소개 후보, 지났으면 코스로만 연결 |
| 60 | 쿠폰 확인 | E13 | 쿠폰 확인 · 잠금(3.5) |
| 70 | 예약금 금액 | D10 | 인원당 예약금 × 인원을 정한다(예약에 저장) |
| 80 | 저장 | D2 | 예약번호 발급 · 저장 |
| 82 | 공유 동의 기록 | C8 | 저장된 예약 ID로 `share_consent` 한 행: 동의 시각 = 신청 시각(서버 시계), 항목 = 그 시점 권역 공유 정책에서 켜진 `shareItem`(이름 · 연락처 · 방문한 가게 목록은 항상, `share_policy`에 행이 없으면 켜짐). 기록이 예약을 가리켜 저장 뒤에 둔다(D2 순서 상수 `CONSENT`(50)는 쓰지 않음) |
| 85 | 예약금 납부 | D10 | 회원 잔액 차감(모자라면 `insufficient_balance`) · 비회원 전화번호 납부 기록. 거래에 예약 id를 남기려고 저장 뒤. 0원이면 기록 없음 |
| 90 | 쿠폰 예약 연결 | E13 | 저장된 예약 ID로 쿠폰 적용(3.5). 예약 ID가 저장 뒤에 생겨 따로 둔다 |
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
| 단계 30의 D5 검사 | 한 팀 최대 인원 초과 → `invalid`. 영업시간 밖 · 휴무일 · 슬롯 단위에 안 맞는 시각 → `slot_unavailable` (아래 「슬롯 계산」) | D5 (#71) |

### 손님 예약 취소 (D6)

#### `POST /api/v1/reservations/{id}/cancel` — 회원 손님 취소 (G-04, FR-RSV-06)

- 역할: 회원 손님(내 예약). `X-XSRF-TOKEN` 필요. 본문 없음. 다른 손님 예약은 `404 not_found`(#5).
- `requested` · `confirmed` → `canceled`, `cancelReason: "customer_canceled"`, `refundAmount` 기록. 응답 `200`: `Reservation`.

| 취소 시각 | 환불 |
| --- | --- |
| 예약 시각 24시간 전까지 | 예약금 100%(#1). 가게 취소 마감과 상관없이 보장(#4) |
| 그 뒤, 가게 취소 마감(예약 시각 − `cancelDeadlineHours`) 전 | 예약금 × 가게 비율(`lateCancelRefundRate`, 없으면 권역 설정값 0.5), 1원 미만 버림(#2) |
| 마감이 지남 | 거절 `409 conflict`(#3) |

- 환불은 상태 변경 이벤트를 받은 예약금 환불(D10)이 같은 트랜잭션에서 한다. 회원은 잔액으로, 비회원은 전화번호 기록으로. 쿠폰 적용 취소(E) · 알림(C)도 같은 이벤트로.
- 실패: `not_found` · `conflict`(신청 · 확정이 아님, 마감 지남) · `unauthorized` · `forbidden_role` · `csrf_invalid`
- **비회원 취소:** `POST /api/v1/guest/reservations/{id}/cancel`(아래 「비회원 취소 · 변경」).

### 예약 변경 (D8)

#### `POST /api/v1/reservations/{id}/change` — 회원 손님 예약 변경 (G-05, FR-RSV-07)

- 역할: 회원 손님(내 예약). `X-XSRF-TOKEN` 필요. 비회원은 `POST /api/v1/guest/reservations/{id}/change`(아래 「비회원 취소 · 변경」).
- 요청: `{ "startAt": "2026-11-29T10:00:00Z", "partySize": 3 }` — 둘 중 하나 이상(바뀐 값이 없으면 `invalid`).
- `confirmed`만, **가게 취소 마감 전까지**(기획 11-27). 새 시각 · 인원은 예약 신청과 같은 규칙(영업시간 · 휴무일 · 기간 · 자리 · 한 팀 최대 인원).
- 결과: `status: "requested"`, 원래 `startAt` · `partySize`는 그대로, `pendingStartAt` · `pendingPartySize`에 바꿀 값(#1). 돈은 움직이지 않는다. 응답 `200`: `Reservation`.
- 인원이 늘어 회원이 더 내야 하면 지금 잔액을 확인한다(모자라면 `insufficient_balance`, 충전 안내).
- 실패: `invalid` · `not_found`(남의 예약) · `conflict`(확정이 아님 · 이미 변경 대기 · 마감 지남) · `slot_unavailable` · `insufficient_balance` · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 사장님 처리 (D3 API 그대로)

| 동작 | 변경 건이면 |
| --- | --- |
| `POST /api/v1/owner/reservations/{id}/confirm` | 자리를 다시 확인하고 예약금 차액을 정산(늘면 더 냄 · 줄면 돌려받음, 회원은 잔액 · 비회원은 전화번호 기록), 바꾼 값으로 `confirmed`(#2). 회원 잔액이 모자라면 `409 insufficient_balance` — 변경은 대기 그대로 |
| `POST /api/v1/owner/reservations/{id}/reject` | 원래 값으로 `confirmed` 유지, 돈은 그대로(#3). 취소가 아니다 |
| 자동 거절(D7) | 원래 시각과 바뀐 시각 중 이른 쪽까지 승인이 없으면 변경만 거절, 원래 값 유지(#4) |

같은 예약 ID라 적용된 쿠폰은 그대로다(05-policy).

### 비회원 취소 · 변경 (D6 · D8, G-13)

비회원 예약 조회 제한 세션(C13)에서만 쓴다. 규칙 · 요청 · 응답은 회원 경로와 같다. 대상 예약이 그 전화번호의 비회원 예약(`GuestSessionService.reservationIds`)이 아니면 `404 not_found`.

#### `POST /api/v1/guest/reservations/{id}/cancel` — 비회원 취소

- 역할: 비회원 제한 세션. `X-XSRF-TOKEN` 필요. 본문 없음.
- 규칙 · 응답: 회원 취소(`POST /api/v1/reservations/{id}/cancel`)와 같다. 환불은 전화번호 기록(`wallet_tx.guest_phone`, `deposit_refunded`)으로 남는다.
- 실패: `not_found` · `conflict` · `unauthorized`(세션 없음) · `forbidden_role`(계정 세션) · `csrf_invalid`

#### `POST /api/v1/guest/reservations/{id}/change` — 비회원 예약 변경

- 역할: 비회원 제한 세션. `X-XSRF-TOKEN` 필요.
- 요청 · 규칙 · 응답: 회원 변경(`POST /api/v1/reservations/{id}/change`)과 같다. 비회원은 잔액이 없어 신청 때 잔액 확인을 하지 않고, 사장님이 승인할 때 차액을 전화번호 기록으로 남긴다.
- 실패: `invalid` · `not_found` · `conflict` · `slot_unavailable` · `unauthorized` · `forbidden_role` · `csrf_invalid`

### 내 예약 · 코스 묶음 (D13)

#### `GET /api/v1/reservations/mine` — 내 예약 (G-04, FR-RSV-12)

- 역할: 회원 손님(내 예약만). 비회원은 비회원 예약 조회(C13).
- 응답 `200`: 묶음 목록. 묶음은 가장 이른 예약 시각 순, 묶음 안은 시간순.

```json
{
  "items": [
    {
      "courseRootId": "10",
      "reservations": [
        { "id": "10", "reservationNo": "K7Q2M9XA", "storeId": "1", "storeName": "골목 카페", "startAt": "2026-11-28T09:00:00Z",
          "partySize": 2, "status": "canceled", "cancelReason": "store_rejected", "requestedAt": "2026-11-25T00:00:00Z",
          "depositAmount": 10000, "refundAmount": null, "visitAmount": null, "visitedAt": null, "courseRootId": null,
          "coupon": null, "cancelable": false, "refundIfCanceled": null },
        { "id": "11", "storeName": "골목 주점", "startAt": "2026-11-28T11:00:00Z", "status": "requested", "courseRootId": "10",
          "cancelable": true, "refundIfCanceled": 10000 }
      ]
    },
    { "courseRootId": null, "reservations": [ { "id": "12" } ] }
  ]
}
```

  - **묶음:** '다음 코스로 예약하기'로 만든 예약(`courseRootId` 있음)은 기준 예약과 한 묶음(묶음의 `courseRootId` = 기준 예약 id). 따로 한 일반 예약은 혼자 한 묶음(`courseRootId: null`)(#3).
  - 기준 예약이 취소 · 거절돼도 묶음은 그대로이고 그 예약의 `status`가 `canceled`로 보인다. 다음 코스 예약은 자동 취소하지 않는다(#1 · #2). 거절 때 '다음 코스는 그대로' 알림은 C14가 `reservation_rejected`에서 보낸다.
  - `cancelable` · `refundIfCanceled`: 지금 취소할 수 있는지와 그때 돌려받는 예약금(D6 규칙: 24시간 전 100%, 마감 전 가게 비율, 마감 뒤 취소 불가 → `false` · `null`). 화면의 '취소할 때 환불 금액' 안내(G-04).
  - `refundAmount`: 이미 손님 취소한 예약의 실제 환불액.
  - `visitAmount` · `couponDiscount` · `amountAfterDiscount`: 방문 처리된 예약의 결제 금액(할인 전) · 실제 쿠폰 할인 · 할인 후 금액. 방문 금액 알림(D21)과 같은 값이고, 다르면 '금액이 달라요'(C23)로 신고한다. 방문 전이면 `null`(FR-STL-07 #1).
  - 보낸 가게 · 수수료는 넣지 않는다(FR-CUS-05, 11-25 (5)).
- 실패: `unauthorized` · `forbidden_role`(회원이 아님)

### 지연 입력 (D14)

#### `POST /api/v1/reservations/{id}/delay` — 늦을 것 같아요 (G-07, FR-RSV-10 #2)

- 역할: 회원 손님(내 예약). `X-XSRF-TOKEN` 필요. 비회원은 비회원 예약 조회 세션(C13)에서 같은 `DelayService.reportDelay`를 부른다.
- 요청: `{ "delayMinutes": 20 }` — 1 ~ 120분(화면은 10 · 20 · 30분 선택).
- 확정 예약만, 예약 시각 2시간 뒤까지. 예상 도착 = 예약 시각 + `delayMinutes`, 예약에 `delay_eta`로 남긴다. **예약 시각은 바뀌지 않는다.** 다시 입력하면 새 값으로 바뀐다.
- 응답 `200`: `{ "reservationId": "string", "expectedArrivalAt": "2026-11-28T10:20:00Z" }`
- 커밋 뒤 그 가게 사장님 계정에 `delay_reported` 알림(`payload: { "reservationId", "startAt", "expectedArrivalAt" }`, 손님 정보 없음).
- 실패: `invalid`(범위) · `not_found`(남의 예약) · `conflict`(확정이 아님 · 너무 늦음) · `unauthorized` · `forbidden_role` · `csrf_invalid`
- 리마인드(FR-RSV-10 #1, 예약 30분 전 `reminder` 알림)는 C14 `ReservationReminderJob`이 한다(자동 작업 `reservation_reminder`).

### 예약 운영 설정 · 슬롯 (D5)

#### 슬롯 계산 (서버 내부 `SlotRules`, 신청 검사 · 예약 가능 시간이 같이 씀)

- 슬롯은 영업 시작 시각(`open_time`)부터 슬롯 단위(`slot_minutes`)로 나눈다. **마지막 슬롯은 영업 종료 시각까지 끝나야 한다**(10:00 ~ 22:00, 30분 → 21:30이 마지막).
- 종료 시각이 시작 시각보다 이르거나 같으면 다음 날 새벽까지 영업한다(18:00 ~ 02:00). 자정 뒤 슬롯도 **시작한 날(영업일)**의 것이다.
- 휴무일(`closed_days`)은 영업일의 요일로 판단한다. 영업시간이 비어 있는 가게는 슬롯이 없다.
- 모든 날짜 · 요일은 운영 시간대(Asia/Seoul).

#### `GET /api/v1/stores/{storeId}/available-slots?date=2026-11-28` — 예약 가능 시간 (G-02)

- 선택 `after`(UTC 시각): 다음 코스 시간 제안(D12, FR-RSV-11 #2). 기준 예약 시각을 넣으면 **같은 날**은 그보다 늦은 슬롯만 돌려준다. 다른 날은 영향 없음.

- 역할: 누구나(로그인 불필요).
- 응답 `200`:

```json
{
  "date": "2026-11-28",
  "acceptingReservations": true,
  "maxPartySize": 4,
  "depositPerPerson": 10000,
  "slots": [ { "startAt": "2026-11-28T01:00:00Z", "available": false }, { "startAt": "2026-11-28T01:30:00Z", "available": true } ]
}
```

  - `slots`: 그 영업일의 모든 슬롯(휴무일이면 빈 목록). `available`은 예약 받는 중 · 예약 가능 기간 안 · 지금 이후 · 자리 남음일 때 `true`.
  - `acceptingReservations`: 영업 상태가 `open`이고 예약 받기가 켜져 있으면 `true`. `false`면 모든 슬롯이 `available: false`.
  - `maxPartySize`: `null`이면 제한 없음. 화면은 인원 선택 상한으로 쓴다.
  - 보였던 슬롯도 신청 순간 자리가 차면 신청이 `slot_unavailable`이 될 수 있다.
- 실패: `not_found`(없거나 승인 전 가게) · `invalid`(date)

#### `GET` · `PUT /api/v1/owner/reservation-settings` — 예약 운영 설정 (O-03)

- 역할: 사장님(내 가게). `PUT`은 `X-XSRF-TOKEN` 필요. 영업시간 · 휴무일(O-12)과 영업 상태 · 예약 받기는 C22가 맡는다.
- `PUT` 요청은 **모든 칸을 보낸다**(부분 수정 없음):

```json
{ "slotMinutes": 30, "capacityPerSlot": 2, "maxPartySize": 4, "bookingWindowDays": 30, "cancelDeadlineHours": 24, "lateCancelRefundRate": 0.5, "depositPerPerson": 10000 }
```

| 필드 | 범위 | 비고 |
| --- | --- | --- |
| `slotMinutes` | 10 ~ 240, 10분 단위 | |
| `capacityPerSlot` | 1 ~ 50 | 같은 슬롯에 받는 예약 수 |
| `maxPartySize` | 1 ~ 100 또는 `null` | `null` = 제한 없음 |
| `bookingWindowDays` | 1 ~ 60 | 기본 30 (기획 11-25 (2)) |
| `cancelDeadlineHours` | 0 ~ 24 | 예약 시각 몇 시간 전까지 취소. 24시간 전보다 이르게 못 잡음(FR-RSV-05 #1) |
| `lateCancelRefundRate` | 0 ~ 1(소수 셋째 자리까지) 또는 `null` | `null` = 권역 설정값(`late_cancel_refund_rate`) |
| `depositPerPerson` | 0 ~ 1,000,000 | 원. 0 = 예약금 없음 |

- 응답 `200`(GET · PUT): 위 칸 + `effectiveLateCancelRefundRate`(실제로 쓰는 환불 비율 = 가게 값, 없으면 권역 값).
- 바꾼 설정은 그 뒤 신청부터 적용된다. 이미 받은 예약은 그대로다.
- 실패: `invalid`(범위 밖 · 칸 빠짐) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 정책에 따라 정한 것 (D5)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 마지막 슬롯 | 영업 종료 시각까지 끝나는 슬롯까지 | FR-RSV-05 '예약 가능 시간' |
| 새벽 영업 | 종료 ≤ 시작이면 다음 날 새벽까지, 슬롯은 시작한 날 소속 | 주점 업종 |
| 슬롯 단위 · 동시 예약 수 · 최대 인원 · 예약금 범위 | 위 표 (기획에 범위 없음, 화면 입력 상한용) | — |

### 사장님 예약 (D3 · D4)

가게는 경로 · 요청으로 받지 않고 **로그인한 사장님 계정의 가게**로 정한다(IDOR). 다른 가게 예약은 `404 not_found`(존재 숨김). 사장님이 아니면 `403 forbidden_role`.

#### `GET /api/v1/owner/reservations?date=2026-11-28` — 날짜별 예약 목록 (D4)

- 역할: 사장님. `date`는 운영 시간대(Asia/Seoul) 날짜, 그 날짜에 시작하는 내 가게 예약을 시각 순으로. 하루치라 페이지를 나누지 않는다.
- 응답 `200`:

```json
{
  "date": "2026-11-28",
  "referralFeeRate": 0.04,
  "items": [
    {
      "id": "string", "reservationNo": "K7Q2M9XA", "startAt": "2026-11-28T09:00:00Z", "partySize": 2,
      "status": "requested", "cancelReason": null, "requestedAt": "2026-11-27T07:40:00Z", "requestNote": null,
      "depositAmount": 10000, "customerType": "guest", "maskedName": "김*코", "fromRecommendation": true,
      "visitAmount": null, "visitedAt": null, "referralConfirmed": false, "referralFee": null,
      "pendingStartAt": null, "pendingPartySize": null, "expectedArrivalAt": null,
      "coupon": { "couponId": "string", "benefitType": "fixed_discount", "discountAmount": 5000, "discountPercent": null, "maxDiscountAmount": null, "serviceName": null }
    }
  ]
}
```

  - `customerType`: `member` · `guest`(화면의 '회원 여부').
  - `maskedName`: 가린 이름. 이름이 없으면 `null`. **전화번호 · 보낸 가게 id · 이름은 넣지 않는다**(FR-CUS-05, FR-RSV-04).
  - `fromRecommendation`: 정식 코스 제안으로 온 예약이면 `true`.
  - `visitAmount` · `visitedAt`: 방문 처리(D11) 뒤에만 값. 방문 전이면 `null`.
  - `coupon`: 적용 · 사용된 쿠폰(`Reservation.coupon`과 같은 모양, E14 `appliedCouponFor`). 없으면 `null`. 여기의 `discountAmount`는 **쿠폰 행사의 할인 금액**이고 실제로 깎은 금액이 아니다(방문 처리 응답의 `couponDiscountAmount`). O-01 '쿠폰 사용 확인' 버튼 표시에 쓴다.
  - `referralConfirmed` · `referralFee`: 이 예약이 소개로 확정됐는지(방문 처리 때 D15가 수수료를 만들면 `true`)와 확정된 수수료(원). 소개가 아니거나 방문 전이면 `false` · `null`. 보낸 가게는 넣지 않는다.
  - `referralFeeRate`: 소개 수수료율(설정값, NFR-04). 방문 처리 화면의 수수료 미리보기용. 상세에도 있다.
  - `pendingStartAt` · `pendingPartySize`: 손님이 낸 변경 신청 값(D8, `Reservation`과 같은 뜻). 변경 신청이 없으면 `null`. `status`가 `requested`이고 이 값이 있으면 변경 신청이다(O-02 원래 일시 · 바뀐 일시).
  - `expectedArrivalAt`: 손님이 입력한 지연 예상 도착 시각(D14 `delay_eta`). 입력이 없으면 `null`(O-01 'N분 늦어요', FR-RSV-10).
  - 손님 등급(FR-CUS-04)은 아직 없다. 더할 때는 C10 `CustomerCardService.grades(storeId, customerIds)`를 쓴다(1절 「고객 목록 · 고객 카드 · 손님 등급」).
- 실패: `invalid`(date 없음 · 형식) · `unauthorized` · `forbidden_role`

#### `GET /api/v1/owner/reservations/{id}` — 예약 상세 (D4)

- 역할: 사장님(내 가게 예약).
- 응답 `200`: 목록 항목과 같은 모양 + `referralFeeRate`.
- 실패: `not_found`(없거나 다른 가게 예약) · `unauthorized` · `forbidden_role`

#### `POST /api/v1/owner/reservations/{id}/confirm` — 승인 (D3)

- 역할: 사장님. `X-XSRF-TOKEN` 필요. 본문 없음.
- `requested` → `confirmed`(FR-RSV-03 #1). 응답 `200`: `Reservation`(적용 쿠폰이 있으면 `coupon`이 채워짐).
- 실패: `not_found` · `conflict`(신청 상태가 아님, 또는 예약 시각이 이미 지남 — 자동 거절 대상) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `POST /api/v1/owner/reservations/{id}/reject` — 거절 (D3)

- 역할: 사장님. `X-XSRF-TOKEN` 필요. 본문 없음. 거절 사유는 받지 않는다.
- `requested` → `canceled`, `cancelReason: "store_rejected"`(FR-RSV-03 #2). 예약금 100% 환불 · 쿠폰 적용 취소는 아래 이벤트를 받는 쪽이 같은 트랜잭션에서 한다.
- 응답 `200`: `Reservation`. 실패: 위와 같음(`conflict`: 신청 상태가 아님).

#### 자동 거절 · 자동 취소 (서버 내부, D3 · D4 → 실행 주기는 D7)

`ReservationStateService`의 두 메서드를 D7 스케줄이 주기적으로 부른다. 둘 다 서버 시계(D18)를 따른다.

| 메서드 | 대상 | 결과 |
| --- | --- | --- |
| `autoRejectDue()` | `requested`이고 `startAt` ≤ 지금 | `canceled` · `store_rejected` (FR-RSV-03 #3, 거절과 같은 결과) |
| `autoRejectDue()` (변경 건) | 변경 대기이고 원래 시각과 바뀐 시각 중 이른 쪽 ≤ 지금 | 변경만 자동 거절, 원래 값으로 `confirmed`(FR-RSV-07 #4) |
| `autoCancelDue()` | `confirmed`이고 예약일(운영 시간대) 다음 날이 끝남 = `startAt` < (오늘 − 1일) 00:00 | `canceled` · `auto_canceled` (FR-RSV-02 #1) |

방문 처리된 예약은 바꾸지 않는다(FR-RSV-02 #2). 사장님 처리와 겹쳐도 행을 잠그고 상태를 다시 확인해 한 번만 바뀐다.

#### 자동 작업 스케줄 (서버 내부, D7, NFR-06)

`ScheduledJob`(이름 · `run()` → 처리 건수) 빈을 `ScheduledJobRunner`가 **1분마다** 모두 돈다(`app.scheduler.enabled` · `interval`, 환경 변수 `SCHEDULER_ENABLED`, 기본 켜짐). 한 작업이 실패해도 나머지는 돈다. 각 작업은 서버 시계(D18)로 대상을 찾고, 여러 번 돌아도 결과가 같다.

| 이름 | 하는 일 | 담당 |
| --- | --- | --- |
| `auto_reject` | `autoRejectDue()` | D3 |
| `auto_cancel` | `autoCancelDue()` | D4 |
| `coupon_expiry` | `CouponExpiryService.expireDue()` | E14 |
| `fund_budget_release` | `FundBudgetService.releaseDue()` | D20 |
| `guest_purge` | 비회원 정보 파기(FR-CUS-07): 방문 기록이 있으면 마지막 방문 후 `guest_retention_months`개월, 없으면 마지막 예약 취소 후 `guest_no_visit_retention_days`일이 지난 비회원(신청 · 확정 예약이 남아 있으면 제외)의 이름 · 전화번호 · 사장님 메모 · 지연 입력 도착 시간을 지우고 `purged_at`을 남긴다. 예약 · 방문 기록과 성별 · 연령대는 남는다. 처리 건수 = 파기한 손님 수 | C12 |

시연 시계(D18)를 바꾸면 그 자리에서 한 번 더 돈다(아래 「시연용 시각 조작」의 `jobs`). 테스트에서는 주기 실행을 끄고 `runAll()`을 직접 부른다.

#### 상태 변경 이벤트 `ReservationStatusChangedEvent` (서버 내부)

상태를 바꾸는 곳은 `ReservationStateService` 하나다. 바꿀 때마다 `ReservationStatusChangedEvent(reservationId, storeId, customerId, fromStatus, toStatus, cancelReason, byScheduler, change)`를 상태를 바꾼 트랜잭션 안에서 내보낸다. `change`는 예약 변경(D8)일 때만 값이 있다: `requested`(확정 → 신청), `approved`(신청 → 확정, 바꾼 값), `rejected`(신청 → 확정, 원래 값, 자동 거절 포함). 변경 승인과 거절은 상태만으로 구분되지 않아 이 칸으로 구분한다(C15).

| 받는 쪽 | 받는 방법 | 할 일 |
| --- | --- | --- |
| 예약금 환불 (D10 · D6) | `@EventListener` (같은 트랜잭션, 실패하면 상태 변경도 취소) | `store_rejected` → 100% 환불, `auto_canceled` → 환불 없음(05-policy 「예약 상태」). 손님 취소는 D6 |
| 쿠폰 적용 취소 (E) | `@EventListener` | 방문 전 취소면 적용 쿠폰 → `apply_canceled`(30일 제한 유지) |
| 알림 (C14 · C15) | `@TransactionalEventListener` (커밋 뒤) | 확정 · 거절 · 자동 취소 알림(1절 「알림」 표) |

#### `POST /api/v1/owner/reservations/{id}/visit` — 방문 처리 (D11)

- 역할: 사장님(내 가게 예약). `X-XSRF-TOKEN` 필요.
- 요청: `{ "visitAmount": 30000 }` — 손님이 낸 결제 금액(**쿠폰 할인 전**, 원). 쿠폰 할인 · 수수료는 서버가 계산한다(FR-RSV-09).
- `confirmed` → `visited`. `visitAmount` · `visitedAt`을 남긴다.
- 응답 `200`: `Reservation`(`coupon` 채움) + **`couponDiscountAmount`**: 이 방문 금액에 실제로 깎은 쿠폰 할인액(원, E14 `discountFor`). 쿠폰이 없으면 `null`. 예: 정액 5,000원 쿠폰, 방문 금액 4,000원 → 4,000(FR-CPN-07). + **`referralConfirmed`**(소개로 확정됐는지) · **`referralFee`**(확정된 수수료, 원, 소개가 아니면 `null`). 방문 처리와 같은 트랜잭션에서 D15가 만든 값이다(O-01 처리 결과, B8).
- 쿠폰은 바꾸지 않는다. 적용된 쿠폰은 '예약에 적용됨' 그대로이고, 사용 완료는 사장님이 따로 확인한다(FR-RSV-09 #3, FR-CPN-08).
- 실패:
  - `invalid` — `visitAmount` 없음 · 음수 · 10,000,000 초과(FR-RSV-09 #2)
  - `not_found` — 없는 예약 · 다른 가게 예약
  - `conflict` — `confirmed`가 아님(신청 · 취소 · 이미 방문)
  - `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `POST /api/v1/owner/reservations/{id}/late-visit` — 늦은 방문 처리 (D23, FR-STL-09 #2 · #5, FR-RSV-02 #4, 11-25 (5))

- 역할: 사장님(내 가게 예약). `X-XSRF-TOKEN` 필요. 화면: O-10 · O-13에서 상인회가 '맞음'으로 기록한 미처리 신고 건.
- 요청: `{ "visitAmount": 30000 }` — 방문 처리와 같다(쿠폰 할인 전, 0 ~ 10,000,000원).
- 조건: `canceled`(사유 `auto_canceled`)이고, 그 예약에 상인회가 '맞음'(`valid`)으로 기록한 미처리 신고(`not_processed`)가 있을 때만(C23 `hasValidReport`).
- `canceled` → `visited`. `visitAmount` · `visitedAt`(처리 시각)을 남기고 `cancelReason` · `canceledAt`은 지운다. 예약금은 움직이지 않는다(자동 취소 때 돌려주지 않았다).
- 방문 처리와 같이 `ReservationStatusChangedEvent`(`canceled` → `visited`)와 `VisitProcessedEvent`를 내보낸다: 소개 수수료(D15) · 쿠폰 '적용 취소' → '예약에 적용됨'(E14, 사용 확인은 따로) · 방문 금액 알림(D21).
- 정산 달 = **원래 예약 달**(`VisitProcessedEvent.settlementAt` = 예약 시각, FR-STL-09 #3).
- 응답 `200`: 방문 처리와 같은 모양(`Reservation` + `couponDiscountAmount` · `referralConfirmed` · `referralFee`).
- 실패: `invalid`(금액) · `not_found`(없는 예약 · 다른 가게) · `conflict`(자동 취소가 아님 · '맞음' 미처리 신고 없음) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### `POST /api/v1/owner/reservations/{id}/amount-correction` — 방문 금액 정정 (D22, FR-STL-09 #1 · #3 · #4, 11-25 (5) · 11-27 (2))

- 역할: 사장님(내 가게 예약). `X-XSRF-TOKEN` 필요. 화면: O-10 · O-13에서 상인회가 '맞음'으로 기록한 금액 신고 건.
- 요청: `{ "correctedAmount": 40000 }` — 정정 금액(쿠폰 할인 전, 0 ~ 10,000,000원).
- 조건: `visited`이고, 그 예약에 상인회가 '맞음'으로 기록한 금액 신고(`amount`) 중 아직 정정에 쓰지 않은 것이 있을 때만. 신고 한 건에 정정 한 번(`uk_correction_report`).
- 원래 방문 금액(`reservation.visit_amount`) · 수수료(`referral_fee`)는 **고치지 않고** `visit_amount_correction`을 더한다.
- 수수료 차액(소개 예약만): 정정 금액으로 다시 계산한 수수료(원래 `fee_rate`, 쿠폰 할인은 정정 금액 기준 E14 `discountFor`, 1원 미만 버림) − 지금까지 낸 수수료(원래 수수료 + 앞선 정정 차액).
  - 늘면 가게 → 공동 기금(`fee_difference_charged` · `fee_difference_deposited`). 가게 잔액은 마이너스가 될 수 있다.
  - 줄면 공동 기금 → 가게(`fee_difference_withdrawn` · `fee_difference_refunded`, 11-27 (2)). 기금은 마이너스가 될 수 없어 모자라면 `insufficient_balance`로 거절하고 아무것도 남기지 않는다.
  - 소개 예약이 아니면 차액 0, 돈은 움직이지 않는다.
- 정산 달 = 원래 방문(예약) 달: 수수료 행의 정산 달, 수수료가 없으면 예약 달(FR-STL-09 #3). 월 정산(D17) · 사장님 수수료 내역(D16)의 `totalFee`에 차액이 들어간다.
- 응답 `200`: `{ "reservationId": "string", "originalAmount": 30000, "correctedAmount": 40000, "feeDifference": 400, "settlementMonth": "2026-11", "createdAt": "..." }` — `originalAmount`는 이 정정 직전 금액(앞선 정정이 있으면 그 정정 금액).
- 실패: `invalid`(금액) · `not_found` · `conflict`(방문이 아님 · 쓸 수 있는 '맞음' 금액 신고 없음) · `insufficient_balance`(줄어든 차액을 기금이 못 돌려줌) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### '방문 처리됨' 이벤트 `VisitProcessedEvent` (서버 내부, D11)

방문 처리하면 `ReservationStatusChangedEvent`(`confirmed` → `visited`)와 함께 `VisitProcessedEvent(reservationId, storeId, customerId, visitAmount, visitedAt, courseProposalId, senderStoreId, settlementAt)`를 같은 트랜잭션에서 내보낸다. `settlementAt`은 정산 달을 정하는 시각으로, 보통 `visitedAt`과 같고 늦은 방문 처리(D23)는 원래 예약 시각이다. 받는 쪽은 D11 코드를 고치지 않는다.

| 받는 쪽 | 받는 방법 | 할 일 |
| --- | --- | --- |
| 소개 판정 (E15) | 받지 않음 | 판정은 신청 때 D12(단계 40: 마감 전이면 제안 id · 보낸 가게를 남겨 소개 후보), 확정은 방문 처리 때 D15(예약 건당 한 번 `referral_fee`). 따로 저장하지 않는다. 끝까지 확인은 E15 `ReferralJudgementTest` |
| 소개 수수료 (D15) | `@EventListener` | 소개 확정이면 (방문 금액 − 쿠폰 할인) × 수수료율 → 가게 → 기금(FR-STL-02) |
| 쿠폰 할인 계산 (E14) | `@EventListener` | 적용 쿠폰의 실제 할인액 기록(사용 완료로 바꾸지 않음) |
| 방문 금액 알림 (D21) | `@TransactionalEventListener` (커밋 뒤) | **회원에게만** `visit_amount_recorded` 알림(아래). 비회원은 없음(FR-STL-07 #2) |
| 고객 카드 · 방문 횟수 (C) | `@EventListener` 또는 커밋 뒤 | 마지막 방문 · 방문 횟수 |

`senderStoreId`는 서버 안에서만 쓴다. 응답 · 알림에 내보내지 않는다(FR-CUS-05).

#### 방문 금액 알림 `visit_amount_recorded` (서버 내부, D21, FR-STL-07, 기획 11-25 (5))

`VisitAmountNoticeListener`가 '방문 처리됨'을 커밋 뒤에 받아 **회원** 계정 알림함에 남긴다(비회원은 없음). 알림 목록 · 읽음은 C14 API 그대로.

`payload`: `{ "reservationId", "storeId", "storeName", "visitedAt", "visitAmount", "couponDiscount", "amountAfterDiscount" }` — 결제 금액(할인 전) · 실제 쿠폰 할인(E14 `discountFor`, 없으면 0) · 할인 후 금액(원). **수수료 · 보낸 가게는 넣지 않는다.** 화면은 이 알림에서 내 예약(G-04)으로 보내고, 금액이 다르면 '금액이 달라요' 신고(C23)로 이어진다.

#### 소개 수수료 (서버 내부, D15)

`ReferralFeeListener`가 `VisitProcessedEvent`를 `@EventListener`로 받아 방문 처리와 같은 트랜잭션에서 만든다(실패하면 방문 처리도 취소).

| 항목 | 규칙 |
| --- | --- |
| 대상 | `courseProposalId` · `senderStoreId`가 있는 예약(소개 후보, D12)만. 예약 건당 한 번(`uk_referral_fee_reservation`, FR-STL-01 #8) |
| 계산 금액 | 방문 금액 − 실제 쿠폰 할인(E14 `discountFor`). 서비스 제공 쿠폰은 할인 0(FR-STL-02 #3) |
| 수수료 | 계산 금액 × 수수료율(설정값 `referral_fee_rate`, 그 가게 권역). **1원 미만 버림.** 수수료율은 생길 때 값을 `fee_rate`에 남긴다(NFR-04 #1) |
| 돈 | 받은 가게 지갑 → 권역 공동 기금(`referral_fee_charged` · `fund_deposited`, 예약 id 남김). 가게 잔액이 모자라도 빼서 마이너스가 될 수 있다(기획 11-25 (4)) |
| 쿠폰 비용 | `coupon_cost`에 따로 기록. 가게 쿠폰만(상인회 쿠폰은 기금이 내므로 0, 11-25 (5)) |
| 정산 달 | 방문 처리된 달(운영 시간대 Asia/Seoul) `YYYY-MM`. 늦은 방문 처리(D23)는 원래 예약 달(`settlementAt`) |

수수료 내역 조회 API는 D16에서 이 절에 더한다.

#### 정책에 따라 정한 것 (D3 · D4)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 가린 이름 | 첫 글자와 마지막 글자만 남기고 가운데를 `*`(김로코 → 김\*코). 두 글자면 첫 글자 + `*`(김코 → 김\*) | 07-screens O-01 · O-13 '가린 이름' |
| 예약 시각이 지난 신청 승인 | 거절(`conflict`). 자동 거절 대상이다 | FR-RSV-03 "예약 시각까지 승인되지 않은 신청 예약은 자동 거절" |
| 날짜 목록의 날짜 | 운영 시간대 기준 `startAt`의 날짜 | 05-policy "서비스 운영 시간대" |
| 방문 금액 범위 (D11) | 0 ~ 10,000,000원. 0원 방문도 기록할 수 있다(쿠폰으로 전액 할인 등) | 입력 실수 방지. 기획에 상한 없음 |
| 방문 처리 시각 제한 (D11) | 두지 않는다. 확정 예약이면 예약 시각 전 · 후 언제든(자동 취소되기 전까지) | FR-RSV-09 "손님이 왔을 때" |

### 사장님 소개 수수료 내역 · 소개 실적 (D16)

내 가게는 로그인 계정으로 정한다. **보낸 가게 id · 이름은 넣지 않는다**(FR-CUS-05). 수수료 행은 D15가 방문 처리 때 만든다.

#### `GET /api/v1/owner/referral-fees?month=2026-11` — 월별 수수료 내역 (O-08 · O-15, FR-STL-03)

- 역할: 사장님. `month`(필수, `YYYY-MM`)는 정산 달 = 방문 처리된 달.
- 응답 `200`:

```json
{
  "month": "2026-11",
  "settlementStatus": null,
  "referralFeeRate": 0.04,
  "count": 1,
  "totalFee": 1000,
  "totalCouponCost": 5000,
  "items": [
    { "reservationId": "string", "reservationNo": "K7Q2M9XA", "maskedName": "김*코", "visitedAt": "2026-11-10T10:00:00Z",
      "visitAmount": 30000, "couponDiscount": 5000, "feeBase": 25000, "feeRate": 0.04, "feeAmount": 1000, "couponCost": 5000,
      "feeDifference": 0 }
  ]
}
```

  - `settlementStatus`: 그 달 월 정산(D17) 상태 `aggregated` · `confirmed`, 아직 없으면 `null`(화면의 '잠정').
  - `referralFeeRate`: 지금 설정값. 건별 `feeRate`는 그 수수료가 생길 때 값이다(NFR-04).
  - `couponCost`: 내 가게가 낸 쿠폰 비용(수수료와 따로, FR-STL-02).
  - `feeDifference`: 이 예약의 방문 금액 정정(D22) 수수료 차액 합(줄면 음수, 없으면 0). `feeAmount`는 원래 수수료 그대로이고, `totalFee` = Σ(`feeAmount` + `feeDifference`).
- 실패: `invalid`(month 형식) · `unauthorized` · `forbidden_role`

#### `GET /api/v1/owner/referral-stats?month=2026-11` — 소개 실적 (O-08, FR-STL-04)

- 역할: 사장님. `month`는 선택(없으면 전체 기간).
- 응답 `200`: `{ "month": "2026-11 | null", "sentCount": 3, "receivedCount": 1 }`
  - `sentCount`: 내 가게 예약에서 만든 코스 제안으로 확정된 소개 수. 내 예약이 취소 · 미방문이어도 센다. 보낸 가게는 돈을 받지 않는다.
  - `receivedCount`: 내 가게가 받은(수수료를 낸) 확정 소개 수.
- 실패: `invalid` · `unauthorized` · `forbidden_role`

### 상인회 월 정산 (D17)

권역은 로그인한 상인회 계정으로 정한다. 가게 단위 묶음 숫자만, 손님 정보는 없다(FR-ANL-05). 정산 달 = 방문 처리된 달(D15). 방문 전에 취소된 예약은 수수료가 없어 자동으로 빠진다(FR-STL-05 #2). 공동 기금 잔액 · 내역은 `GET /api/v1/wallets/me`(D9, 상인회 = 기금).

#### `GET /api/v1/association/settlements/{month}` — 월 정산 (A-04)

- 역할: 상인회. `month` = `YYYY-MM`.
- 응답 `200`:

```json
{
  "month": "2026-11",
  "status": "aggregated",
  "confirmedAt": null,
  "canConfirm": true,
  "unprocessedReportCount": 0,
  "count": 2,
  "totalFee": 2200,
  "stores": [ { "storeId": "3", "storeName": "B", "count": 2, "totalFee": 2200, "feeDifference": 0, "avgVisitAmount": 27500, "couponCost": 0 } ]
}
```

  - `status`: 확정 전이면 `aggregated`(수수료 기록에서 그때 집계), 확정 뒤 `confirmed`.
  - `totalFee` · `feeDifference`: 가게별 수수료 합은 원래 수수료 + 방문 금액 정정(D22) 차액. `feeDifference`는 그중 차액 합(줄면 음수). 차액은 원래 수수료의 정산 달에 들어간다(FR-STL-09 #3).
  - `avgVisitAmount`: 가게별 소개 건 평균 방문 금액(**쿠폰 할인 후**, 원 단위 반올림). 낮은지 판단하는 표시는 없다(판단은 상인회 몫, 11-24 (5)).
  - `unprocessedReportCount`: 그 달(예약 시작 달) 예약에 걸린, 처리 완료되지 않은 금액 · 미처리 신고 수(11-25 (5)).
  - `canConfirm`: 확정 전 · 그 달이 끝남 · 처리 안 된 신고 0일 때 `true`.
- 실패: `invalid`(month) · `unauthorized` · `forbidden_role`

#### `POST /api/v1/association/settlements/{month}/confirm` — 확정 (FR-STL-05 #4)

- 역할: 상인회. `X-XSRF-TOKEN` 필요. 본문 없음.
- 상태 `confirmed`, 확정 시각을 남기고 그 달 수수료에 정산 id를 단다. **잔액 · 수수료는 바꾸지 않는다**(확인 기록, 11-24).
- 응답 `200`: 위 GET과 같은 모양.
- 실패: `conflict` — 이미 확정, 그 달이 아직 끝나지 않음, 처리되지 않은 신고가 있음(#5) · `invalid` · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 정책에 따라 정한 것 (D17)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 확정 시점 | 그 달이 끝난 뒤(운영 시간대)부터 | FR-STL-05 예 '12월 1일 정산' |
| 신고의 '그 달' | 신고된 예약의 시작 시각 달 | 05-policy 「방문 금액 확인 · 신고」 '원래 방문(예약) 달' |
| 평균 방문 금액 | 쿠폰 할인 후 금액(`fee_base`) 평균, 원 단위 반올림 | 07-screens A-04 '쿠폰 할인 후' |

### 가상 잔액 · 거래 내역 (D9)

실제 결제는 없다(NFR-02). 잔액이 바뀔 때마다 거래 내역이 한 줄 남는다(FR-STL-06). **가게 잔액만** 소개 수수료로 마이너스가 될 수 있다(기획 11-25 (4)). 회원 잔액과 상인회 공동 기금은 마이너스가 되지 않는다(기금: 11-25 (5)).

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
- 순서 상수는 `ReservationRequestStep`에 모아 둔다(`DEPOSIT_PAYMENT = 85` · `COUPON_LINK = 90` 포함).

#### `POST /api/v1/wallets/me/charge` — 가상 충전 (D10)

- 역할: 회원(FR-RSV-08 #2) · 사장님 가게(FR-STL-06 #2, 기획 11-25 (4)). 상인회 기금은 충전하지 않는다(`forbidden_role`). `X-XSRF-TOKEN` 필요.
- 요청: `{ "amount": 50000 }` — 1,000 ~ 1,000,000원. 실제 결제는 없다(NFR-02).
- 응답 `200`: `GET /api/v1/wallets/me`와 같은 모양(충전 뒤 잔액). 거래 내역에 `charge` 한 줄.
- 실패: `invalid`(금액 범위) · `unauthorized` · `forbidden_role` · `csrf_invalid`

#### 예약금 환불 (서버 내부, D10)

`ReservationStatusChangedEvent`를 `@EventListener`로 받아 상태 변경과 같은 트랜잭션에서 처리한다.

| 사유 | 환불 |
| --- | --- |
| `store_rejected`(가게 거절 · 자동 거절) | 낸 예약금 100%(FR-RSV-03 #2 · #3) |
| `auto_canceled` | 없음(FR-RSV-02 #1) |
| `customer_canceled` | 취소 때 정한 `refundAmount`만큼(D6) |

회원은 잔액으로, 비회원은 전화번호 기록으로 돌려준다. 이 예약의 '납부 − 환불' 합만큼만 돌려줘서 두 번 불려도 한 번만 환불된다.

#### 상인회 쿠폰 기금 예산 (서버 내부, D20 → E18 · E20 · D7이 씀)

`FundBudgetService`. 기금은 마이너스가 될 수 없다(11-25 (5)).

| 메서드 | 부르는 곳 | 하는 일 |
| --- | --- | --- |
| `reserve(eventId)` | E18 행사 만들기(같은 트랜잭션) | 예산 = 수량 × 한 장 최대 할인액(정액은 `discount_amount`, 정률은 `max_discount_amount`)을 기금에서 뺀다(`fund_budget_reserved`, `association_event_id`). `reserved_budget`에 남긴다. 기금이 모자라면 `ApiException(insufficient_balance)` → 행사 생성도 되돌아감. 같은 행사에 두 번 잡지 않는다 |
| `releaseDue()` | D7 스케줄 | `valid_end_at`이 지난 행사(중단한 행사 포함)마다 한 번: 돌려줄 예산 = 잡은 예산 − 쓴 예산을 기금에 넣는다(`fund_budget_released`), `budget_released_at` 기록 |

쓴 예산 = 그 행사 쿠폰 중 `applied` · `used`인 것의 `discount_applied` 합. 아직 방문 전이라 할인액이 없으면 한 장 최대 할인액으로 잡아 둔다(정해서 적음).

#### 정책에 따라 정한 것 (D10)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 충전 한 번 금액 | 1,000 ~ 1,000,000원 | 기획에 범위 없음, 입력 실수 방지 |
| 예약금 납부 시점 | 저장(80) 뒤 85단계. 같은 트랜잭션이라 잔액이 모자라면 예약도 남지 않음 | 거래에 예약 id를 남기기 위해 |

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

  - `applyDeadline`: 소개 예약 신청 마감. 기준 예약일 다음 날 00:00(Asia/Seoul) 직전, UTC로 준다(05-policy 「소개 판정」). 표시용이고 판정은 D12가 신청 시각으로 한다. 마감이 지나도 조회 · 표시 기록은 그대로 된다(아래 「정책에 따라 정한 것」).
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
| 소개 확정 | 소개 후보 예약(`course_proposal_id` · `sender_store_id`가 있는 예약)이 방문 처리됨, 예약 건당 1번 | D15 (`referral_fee` 한 행, D11 이벤트) | **수락률 분자**(소개 확정된 예약의 서로 다른 `course_proposal_id` 수, 제안당 1번), 수수료(D15), 소개 실적(D16) | — |
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
| R-3 | (닫힘, 기획 11-26 (2)) 취소 · 거절된 기준 예약으로는 새 다음 코스를 만들 수 없다(`conflict`). 이미 만든 다음 코스는 그대로 | — | — |
| R-18 | G-01 추천 카드에 '다음 코스로 예약하기'를 두는지 (07-screens G-01 · FR-RSV-11 ↔ 확정 목업, 02-workflow 「맞지 않는 곳」) | G-01 카드는 탐색 노출만 기록한다고 적고, 정식 제안을 겸하는지는 정하지 않는다 | 팀 (A 화면) |
| R-9 | 가게 좌표계. 06-data · 마이그레이션은 `map_x/map_y`를 "골목 안 표시 좌표"로, 05-policy 거리 항은 다른 골목 가게 사이 거리도 씀 | `mapX` · `mapY`는 숫자로만 내려준다 | A1 · D1 · E |
| R-10 | 이 절의 비로그인 경로(`GET /api/v1/recommendations`, `POST /api/v1/recommendation-exposures`)를 보안 설정에 더하기 | **반영됨**(#222) | C (#24) |
| R-13 | (닫힘, D12) 필드 이름은 `courseProposalId`. 제안 검사 실패(그 기준 예약 · 그 가게 제안이 아님)는 `conflict`. 마감 뒤 신청은 거절하지 않고 코스로만 연결(소개 아님) | — | — |

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

- C 쪽 호출 세 곳(로그인 · 신청 단계 20 · 로그아웃)은 **반영됨**(#307). 로그인은 회원(손님) 계정일 때만 잇는다(사장님 · 상인회는 손님이 없다).
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
  - `dayType` · `timeBand`: 그 코스를 고른 칸. 오늘의 코스 응답(3.7 E10 #52)에 있는 값을 그대로 보낸다. `timeBand` 값은 `CONTRACTS.md` 「3」(E2 #16에서 정함, V-1 닫힘)
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
| V-1 | (닫힘) 오늘의 코스 `timeBand` 값 목록 → `CONTRACTS.md` 「3」 `timeBand`(E2 #16, 결정 11-26) | — | E2 (#16) |
| V-2 | 이 절과 3.1의 비로그인 경로를 보안 설정 · `CONTRACTS.md` 「6」 목록에 더하기: `POST /api/v1/store-clicks`, `POST /api/v1/course-previews` (3.1 R-10의 두 경로와 함께) | **반영됨**: `store-clicks`(#222), `course-previews`(#298) | C (#24) · E |
| V-3 | 로그인 · 예약 신청에서 `VisitorService`를 부르는 것 | E3는 서비스만 제공하고 테스트는 서비스 단위로 한다 | C2 (#24) · C4 (#38) |

### 3.4 쿠폰 상세 · 다운로드 (E12 #25 · 계약 #154)

> 상태: **계약.** 구현은 #25에서, 동시 요청 검증은 E9(#106)에서 한다. 프론트 목(A6 #26)은 이 절을 따른다.
> 기준: 기획 저장소 `05-policy.md` 「추천 쿠폰」 「발급 제한」, `03-requirements.md` FR-CPN-02 · 03 · 04 · 10, `06-data.md` COUPON, 결정 기록 11-25(30일 제한은 행사 단위). 표는 `coupon` · `coupon_policy`(D #134). 행사는 3.2, 추천 노출은 3.1 · 3.3.

쿠폰은 **회원만** 받는다. 추천 카드의 '쿠폰 다운로드'를 누를 때만 발급하고, 가게 클릭 · 추천 노출로는 발급하지 않는다(FR-CPN-03). 다운로드는 **추천 노출 ID 하나만** 받고, 대상 가게와 출처(추천 기준 가게 · 노출)는 서버가 그 노출 기록에서 정한다(FR-CPN-10). 그래서 클라이언트가 출처를 바꿀 수 없다.

### `GET /api/v1/coupon-offers/{storeId}` — 쿠폰 상세

- 역할: 누구나(로그인 불필요). 사장님 · 상인회는 `403 forbidden_role`. 기록하지 않는다.
- 추천 카드를 열었을 때 쿠폰 상세와 '쿠폰 다운로드' 버튼 상태를 그린다(FR-CPN-02). 가게를 누를 때마다 부르지 않는다.
- 응답 `200`:

  ```json
  {
    "storeId": "7",
    "event": {
      "eventId": "12",
      "benefitType": "percent_discount",
      "discountAmount": null,
      "discountPercent": 10,
      "maxDiscountAmount": 5000,
      "serviceName": null,
      "validDays": 7,
      "conditions": "2인 이상 방문 시"
    },
    "downloadStatus": "waiting",
    "nextDownloadableAt": "2026-12-31T09:00:00Z",
    "myCoupons": [
      { "couponId": "501", "eventId": "12", "status": "downloaded", "expiresAt": "2026-12-08T09:00:00Z" }
    ]
  }
  ```

  | 필드 | 뜻 |
  | --- | --- |
  | `event` | 그 가게의 진행 중인 행사(3.2). 없으면 `null`. 혜택 칸의 뜻은 3.2 CouponEvent와 같다. 서비스 환산 금액(`serviceValue`)은 쿠폰 비용 기록용이라 손님에게 주지 않는다 |
  | `downloadStatus` | 지금 이 요청자가 받을 수 있는지(`CONTRACTS.md` 「3」 쿠폰). 아래 표 |
  | `nextDownloadableAt` | `waiting`일 때만 값이 있다. 같은 행사의 가장 최근 다운로드 + 30 × 24시간 |
  | `myCoupons` | 회원이 가진 이 가게의 쿠폰 중 '다운로드됨' · '예약에 적용됨'. 이전 행사 쿠폰과 새 행사 쿠폰이 함께 있을 수 있다(11-25). 비회원은 `[]` |

  | `downloadStatus` | 때 (위에서부터 먼저 맞는 것) |
  | --- | --- |
  | `unavailable` | 진행 중인 행사가 없거나 총수량이 다 나감, 또는 가게가 영업 중이 아님(휴업 · 운영 중단) |
  | `login_required` | 로그인하지 않음. 화면은 로그인(G-08) 뒤 이 가게로 돌아온다(FR-CPN-03) |
  | `applied_unused` | 같은 행사의 쿠폰이 예약에 적용된 채 아직 사용 완료가 아님. 30일이 지나도 받을 수 없다(FR-CPN-04 #3) |
  | `waiting` | 같은 행사 쿠폰을 받은 지 30 × 24시간이 안 됨('30일 대기', FR-CPN-04 #1 · #2 · #7) |
  | `available` | 받을 수 있음 |

- 실패: `forbidden_role` · `not_found`(없는 가게 · 승인 전 · 거절 · 탈퇴 가게)

### `POST /api/v1/coupons` — 쿠폰 다운로드

- 역할: 회원 손님. 비로그인 `401 unauthorized`(화면은 G-08로 보낸다), 사장님 · 상인회 `403 forbidden_role`. `X-XSRF-TOKEN` 필요.
- 요청: `{ "exposureId": "123" }` — 그 카드를 그렸을 때 받은 3.1 `POST /api/v1/recommendation-exposures`의 `exposureId`
- 검사 순서:
  1. 노출이 없거나 **이 요청자의 노출이 아니면 `404 not_found`**. 이 브라우저의 방문자(3.3 쿠키)의 노출이거나, 이 회원에 이어진 방문자의 노출이어야 한다.
  2. 대상 가게(노출의 추천 가게)가 손님에게 보이지 않으면 `404 not_found`.
  3. 아래 발급 조건 중 하나라도 어긋나면 `409 conflict`, 아무것도 저장하지 않는다. 다음 다운로드 가능일은 쿠폰 상세(`GET`)의 `nextDownloadableAt`으로 보여준다(에러 응답에는 담지 않는다).
- 발급 조건:
  - 가게가 영업 중(`business_status = open`)
  - 진행 중인 행사가 있고 남은 수량이 1 이상
  - 같은 행사의 미사용 적용 쿠폰이 없음
  - 같은 행사에서 받은 지 30 × 24시간이 지남(행사 단위, FR-CPN-04). 다른 기준 가게에서 추천받아도 같은 행사면 제한을 공유하고, 만료 · 적용 취소 뒤에도 유지한다
- 응답 `201` (Coupon):

  ```json
  {
    "couponId": "501",
    "eventId": "12",
    "storeId": "7",
    "status": "downloaded",
    "benefitType": "fixed_discount",
    "discountAmount": 3000,
    "discountPercent": null,
    "maxDiscountAmount": null,
    "serviceName": null,
    "conditions": null,
    "downloadedAt": "2026-12-01T09:00:00Z",
    "expiresAt": "2026-12-08T09:00:00Z"
  }
  ```

  - `status`는 `coupon.status`(`CONTRACTS.md` 「3」). 혜택 칸은 받은 행사의 값이다(행사는 확정 뒤 바뀌지 않는다, 3.2).
  - `expiresAt` = `downloadedAt` + 행사의 `validDays` × 24시간(FR-CPN-05).
- 저장(FR-CPN-10): 회원(손님 ID) · 행사 · 대상 가게 · 추천 기준 가게(노출의 기준 가게) · 추천 노출 ID · 다운로드 시각. 나중에 다른 가게에서 같은 가게를 추천받아도 바꾸지 않는다.
- 실패: `unauthorized` · `forbidden_role` · `invalid`(exposureId 없음 · 숫자 아님) · `not_found` · `conflict` · `csrf_invalid`

#### 원자 처리 (서버 내부)

- 한 트랜잭션에서 **발급 수 조건부 UPDATE를 먼저** 한다(3.2 「다른 이슈가 쓰는 규칙」): `UPDATE coupon_policy SET issued_count = issued_count + 1 WHERE id = ? AND stopped_at IS NULL AND issued_count < total_quantity`. 바뀐 행이 0이면 `409`. 이 UPDATE가 행사 행을 잠가, 같은 행사의 다운로드가 커밋까지 한 줄로 선다.
- 그다음 회원별 조건(30일 · 미사용 적용)을 **잠그는 읽기**로 확인한다. 걸리면 예외로 트랜잭션 전체(발급 수 포함)를 되돌린다.
- 그래서 같은 회원의 동시 요청도, 총수량을 넘는 동시 요청도 1건만 발급된다(NFR-09). 검증 테스트는 E9(#106).

#### 정책에 따라 정한 것 (3.4)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 30일 | 30 × 24시간. 같은 회원 · 같은 행사의 가장 최근 `downloaded_at`부터 | FR-CPN-04 "다운로드 시각부터 30일", 05-policy 설정값 '쿠폰 재발급 제한'(정해진 값) |
| 휴업 뒤 제한 해제 | 가게의 `business_status_changed_at` 전에 받은 쿠폰은 30일 · 미사용 적용 계산에서 뺀다 | 06-data COUPON, FR-CPN-04 #5 |
| 출처 | 클라이언트가 보내지 않는다. 노출 기록의 기준 가게 · 노출 ID | FR-CPN-10, 3.1 R-12 |

#### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 영업 중이 아닌 가게 | 다운로드 `409`, 상세는 `unavailable` | 05-policy "휴업 · 운영 중단이면 … 추천 후보에서 빠지며" | A6 · C22 |
| 다음 다운로드 가능일 전달 | 쿠폰 상세의 `nextDownloadableAt`. 다운로드 거절(`409`)에는 담지 않음(공통 에러 모양 유지) | FR-CPN-04 #1 "다음 다운로드 가능일 반환" | A6 · C |
| 노출 소유 확인 | 이 브라우저의 방문자 또는 이 회원에 이어진 방문자의 노출만 | FR-CPN-10 | A6 |
| 노출이 오래된 경우 | 기한을 두지 않는다. 발급 조건은 다운로드 시각에 판단 | — | — |
| 쿠폰 상세의 남은 수량 | 주지 않는다(받을 수 있는지만) | FR-CPN-11 "배지를 내리고" | A6 · B15 |

#### 미확정 (3.4)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| K-1 | 쿠폰 상세 `GET /api/v1/coupon-offers/{storeId}`를 로그인 없이 여는 보안 설정 · `CONTRACTS.md` 「6」 목록 | **반영됨**(#222) | C (#24) |

### 3.5 쿠폰 적용 (E13 #29 · 계약 #178)

> 상태: **계약.** 구현은 #29에서 한다. 프론트 목(A9 #30 · G-02 · G-03)은 이 절을 따른다. 방문 할인 계산 · 사용 확인 · 만료 · 날짜 변경은 E14(#45), 상인회 쿠폰은 E20(#172)이다.
> 기준: 기획 저장소 `05-policy.md` 「추천 쿠폰」, `03-requirements.md` FR-CPN-05 · 06, 결정 기록 11-25 (3)(회원이 직접 고름, 기본 '적용 안 함') · 11-25 (5)(예약 한 건에 쿠폰 한 장). 예약 신청은 2 「예약 신청」(D2), 쿠폰 모양은 3.4 Coupon.

예약 화면은 쿠폰을 자동으로 고르지 않는다. 회원이 그 가게의 사용 가능한 쿠폰 목록에서 하나를 고르면 예약 신청 요청의 `couponId`로 보낸다. 고르지 않으면 `null`이다. 화면에서 고르거나 고르지 않는 것은 쿠폰 상태를 바꾸지 않고, **신청이 저장될 때 고른 쿠폰만** '예약에 적용됨'이 된다(05-policy).

### `GET /api/v1/coupons/usable?storeId={storeId}` — 예약 화면의 사용 가능한 쿠폰

- 역할: 회원 손님. 비로그인 `401 unauthorized`, 사장님 · 상인회 · 비회원 제한 세션 `403 forbidden_role`. 기록하지 않는다.
- 쿼리: `storeId` (필수)
- 응답 `200`:

  ```json
  {
    "items": [
      { "couponId": "501", "eventId": "12", "storeId": "7", "status": "downloaded", "benefitType": "fixed_discount",
        "discountAmount": 3000, "discountPercent": null, "maxDiscountAmount": null, "serviceName": null,
        "conditions": null, "downloadedAt": "2026-12-01T09:00:00Z", "expiresAt": "2026-12-08T09:00:00Z" }
    ]
  }
  ```

  - `items`의 원소는 3.4 Coupon이다. 이 회원의 그 가게 쿠폰 중 `downloaded`이고 `expiresAt`이 지금보다 뒤인 것. 만료가 가까운 순(`expiresAt` 오름차순).
  - 행사가 중단된 쿠폰도 기한 안이면 들어간다(FR-CPN-11 #4).
  - 없으면 `items: []`. 화면은 '적용 안 함'만 보여준다.
- 실패: `invalid`(storeId 없음 · 숫자 아님) · `not_found`(손님에게 보이지 않는 가게) · `unauthorized` · `forbidden_role`

### 예약 신청에서 쿠폰 적용 (서버 내부, 2 「예약 신청 처리 단계」)

| 순서 | 단계 | 하는 일 |
| --- | --- | --- |
| 60 | 쿠폰 확인 (E13) | `couponId`가 있으면 쿠폰 행을 잠그고(`FOR UPDATE`) 아래 조건을 확인한다. 같은 쿠폰으로 동시에 신청해도 하나만 통과한다 |
| 90 | 쿠폰 적용 (E13) | 저장(80)된 예약에 쿠폰을 잇는다: `status = applied`, `reservation_id` = 그 예약. 응답의 `coupon`을 채운다 |

- 단계 90은 저장(80) 뒤에 예약 ID가 있어야 해서 따로 둔다. 같은 트랜잭션이라 어느 단계가 실패해도 쿠폰은 바뀌지 않는다.
- `couponId`가 `null`이면 두 단계 모두 아무것도 하지 않는다(`coupon: null`).
- 비회원 예약은 쿠폰 없이 진행한다: 비회원이 보낸 `couponId`는 무시한다(FR-CPN-06 #3).
- 확인(위에서부터 먼저 걸리는 것):

  | 조건 | 실패 |
  | --- | --- |
  | 쿠폰이 없거나 이 회원의 쿠폰이 아님 | `404 not_found` (존재를 숨김) |
  | 쿠폰의 대상 가게가 신청한 가게가 아님 | `409 conflict` |
  | 상태가 `downloaded`가 아님(이미 적용 · 사용 · 만료 · 적용 취소) | `409 conflict` |
  | 신청 시각(`requestedAt`, 서버 시계)이 `expiresAt`보다 뒤이거나 같음 | `409 conflict` (기한은 신청 시각으로 판단, FR-CPN-05) |

- 예약 한 건에는 쿠폰 하나만 적용한다(FR-CPN-06). `couponId`는 문자열 하나라, 배열 등 다른 모양이면 `400 invalid`.
- 행사가 중단됐거나 총수량이 다 나간 것은 이미 받은 쿠폰의 적용을 막지 않는다(FR-CPN-11 #4).
- 적용한 쿠폰은 방문일이 기한을 넘어도 혜택이 유지된다(FR-CPN-05 #3). 할인액은 방문 처리 때 정한다(E14).

### 방문 전 취소 · 거절 → 적용 취소

- 2 「상태 변경 이벤트」 `ReservationStatusChangedEvent`를 `@EventListener`(같은 트랜잭션)로 받는다.
- `toStatus = canceled`(손님 취소 · 가게 거절 · 자동 거절 · 자동 취소)면 그 예약에 적용된 쿠폰을 `applied` → `apply_canceled`로 바꾼다. 다른 예약에 자동으로 다시 주지 않고, 30일 제한은 그대로 센다(FR-CPN-05 #4, FR-CPN-07 #1).
- 방문 처리된 예약(`visited`)과 다른 상태의 쿠폰은 바꾸지 않는다.

#### 정책에 따라 정한 것 (3.5)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 기한 판단 | 신청 시각 < `expiresAt` | FR-CPN-05 "판단 시점은 신청 시각" |
| 기본 선택 없음 | 목록은 순서만 정하고 서버가 고르지 않는다 | FR-CPN-06 · 11-25 (3) |
| 남의 쿠폰 | `404`(존재 숨김), 다른 가게 · 상태 · 기한은 `409` | D2 실패 코드 「없는 … 쿠폰(남의 것 포함)」 · 「쓸 수 없는 쿠폰」 |

#### 미확정 (3.5)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| A-1 | (닫힘) 상인회 쿠폰도 같은 목록 · 같은 `couponId`, 종류는 `issuerKind` → 3.10 | — | E20 (#172) |
| A-2 | (닫힘) 늦은 방문 처리(11-25 (5)) 때 '적용 취소' → '예약에 적용됨' 되돌리기 → 3.6 「늦은 방문 처리」 | — | E14 (#45) · D23 (#170) |

### 3.6 쿠폰 상태 · 만료 · 사용 확인 (E14 #45 · 계약 #187)

> 상태: **계약.** 구현은 #45에서 한다. 프론트 목(사장님 예약 B8 · O-01 · O-13)은 이 절을 따른다. 상인회 쿠폰의 같은 일은 E20(#172)이 더한다.
> 기준: 기획 저장소 `05-policy.md` 「추천 쿠폰」 「상태와 전이」 「혜택과 비용」, `03-requirements.md` FR-CPN-05 · 07 · 08, `06-data.md` COUPON(`discount_applied` · `used_at`) · FEE(`coupon_cost`), 결정 기록 11-25 · 11-25 (5). 방문 처리는 2 「방문 처리」 · 「'방문 처리됨' 이벤트」(D11).

| 지금 상태 | 일 | 다음 상태 | 하는 곳 |
| --- | --- | --- | --- |
| `downloaded` | 신청 시각이 기한 전, 회원이 골라 예약 신청 | `applied` | 3.5 (E13) |
| `downloaded` | 만료 시각이 지남 | `expired` | 만료 작업 (아래) |
| `applied` | 방문 처리된 예약을 사장님이 사용 확인 | `used` | 사용 확인 API (아래) |
| `applied` | 방문 전 취소 (손님 취소 · 가게 거절 · 자동 거절 · 자동 취소) | `apply_canceled` | 3.5 (E13) |
| `downloaded` · `applied` | 대상 가게 운영 중단 · 탈퇴 | `apply_canceled` | 가게 상태 변경 (아래) |
| `apply_canceled` | 자동 취소된 예약을 늦게 방문 처리 (11-25 (5)) | `applied` | 늦은 방문 처리 (아래) |

- 방문 처리만으로 `used`가 되지 않는다. 방문 처리 때는 실제 할인액만 기록한다(FR-CPN-08).
- 같은 예약 ID로 날짜만 바꾸는 변경은 쿠폰을 건드리지 않는다. 적용은 그대로 유지된다(FR-CPN-07 #2). 변경이 거절돼도 원래 예약이 유지되므로 쿠폰도 그대로다.

### `POST /api/v1/owner/reservations/{id}/coupon-use` — 쿠폰 사용 확인

- 역할: 사장님(내 가게 예약). `X-XSRF-TOKEN` 필요. 본문 없음.
- 그 예약에 적용된 쿠폰을 `applied` → `used`로 바꾸고 `used_at`(서버 시계)을 남긴다(FR-CPN-08 #1).
- 응답 `200`:

  ```json
  { "couponId": "501", "reservationId": "88", "status": "used", "discountAmount": 3000, "usedAt": "2026-12-02T10:00:00Z" }
  ```

  - `discountAmount`: 방문 처리 때 기록한 실제 할인액(아래 「방문 할인」). 서비스 제공 쿠폰은 `0`.
- 실패:
  - `not_found` — 없는 예약 · 다른 가게 예약(존재 숨김)
  - `conflict` — 예약이 방문 처리(`visited`) 전(FR-CPN-08 #2), 그 예약에 적용된 쿠폰이 없음, 이미 사용 확인함
  - `unauthorized` · `forbidden_role` · `csrf_invalid`

### 방문 할인 (서버 내부, D11 이벤트)

- 할인액 · 쿠폰 비용은 적용된 쿠폰의 행사 값과 방문 금액(쿠폰 할인 전)으로 정한다(05-policy 「혜택과 비용」).

  | 혜택 | 할인액 | 쿠폰 비용 |
  | --- | --- | --- |
  | `fixed_discount` | `min(discountAmount, 방문 금액)` — 남는 할인은 사라진다(FR-CPN-07 #3) | 할인액 |
  | `percent_discount` | `floor(방문 금액 × discountPercent ÷ 100)`, `maxDiscountAmount`가 있으면 그 금액까지(FR-CPN-07 #5 · #6) | 할인액 |
  | `service` | `0` — 금액을 깎지 않는다 | 행사의 `serviceValue`(환산 금액) |

- E14는 '방문 처리됨' 이벤트 `VisitProcessedEvent`를 `@EventListener`(같은 트랜잭션)로 받아 할인액을 쿠폰의 `discount_applied`에 기록한다. 상태는 `applied` 그대로다.
- **D15(수수료 · 쿠폰 비용)는 이벤트 처리 순서에 기대지 않고** 같은 계산을 E의 서비스에서 받는다:
  `CouponVisitService.discountFor(long reservationId, int visitAmount)` → `Optional<VisitCouponDiscount(long couponId, CouponIssuer issuerKind, int discountAmount, int couponCost)>`. 적용된 쿠폰이 없으면 빈 값. 수수료 계산 금액은 `방문 금액 − discountAmount`(서비스 제공은 원래 방문 금액), `coupon_cost`는 `couponCost`(FR-STL-02, 06-data FEE). `issuerKind`(`CONTRACTS.md` 「3」 `coupon.issuerKind`)로 쿠폰 비용을 가게(`store`)가 낼지 공동 기금(`association`)이 낼지 구분한다(11-25 (5)).

### 만료 작업 (서버 내부 → 실행 주기는 D7)

- `CouponExpiryService.expireDue()`: `downloaded`이고 `expires_at` ≤ 지금(서버 시계)인 쿠폰을 `expired`로 바꾸고 바꾼 수를 돌려준다(FR-CPN-05 #2 · #5). `applied`는 방문일이 기한을 넘어도 그대로다(FR-CPN-05 #3).
- D7 스케줄이 `autoRejectDue()` · `autoCancelDue()`와 같이 주기적으로 부른다. 사용 가능한 쿠폰 목록(3.5)은 작업 전이라도 기한이 지난 쿠폰을 빼고 보여준다.

### 가게 운영 중단 · 탈퇴 → 적용 취소 (서버 내부)

- `CouponStatusService.cancelUnusedForStore(long storeId)`: 그 가게가 대상인 `downloaded` · `applied` 쿠폰을 `apply_canceled`로 바꾼다(FR-CPN-07 #4). 30일 제한 해제는 `business_status_changed_at`으로 이미 처리된다(3.4).
- **휴업 · 운영 중단**: C22(#116)가 영업 상태를 바꾼 트랜잭션 안에서 내보내는 `StoreBusinessStatusChangedEvent(storeId, businessStatus)`를 E14가 `@EventListener`로 받아, `businessStatus`가 `paused` · `closed`면 위 메서드를 부른다(FR-STO-02 #1). 예약 받기 '잠시 멈춤'(이벤트 없음)이나 쿠폰 행사 중단(FR-CPN-11)에는 바뀌지 않는다.
- **탈퇴**: 상인회 탈퇴 처리(C7 `POST /api/v1/store-joins/{storeId}/withdraw`)가 같은 트랜잭션에서 위 메서드를 부르고, `business_status_changed_at`도 남긴다.

### 쿠폰 상태 알림 (서버 내부, E14 → C14)

- 쿠폰이 **사용 완료**(사장님 사용 확인, `POST /api/v1/owner/reservations/{id}/coupon-use`)나 **기간 만료**(만료 작업 `CouponExpiryService.expireDue`)가 되면, 상태를 바꾼 트랜잭션 안에서 `CouponStatusChangedEvent(couponId, customerId, status)`(`com.alleycrm.coupon`, `status` = `used` · `expired`)를 내보낸다. 가게 쿠폰 · 상인회 쿠폰 모두.
- 만료 작업은 대상(`downloaded` · `expires_at` ≤ 지금)을 잠그고 읽어 하나씩 바꾸고, **바뀐 쿠폰마다** 이벤트를 낸다. 읽은 뒤 예약에 적용된 쿠폰에는 내지 않는다.
- C14가 `@TransactionalEventListener`(커밋 뒤)로 받아 그 손님의 회원 계정에 `notifyAccount(accountId, "coupon_status", { "couponId", "status" })`를 만든다(1절 표). 쿠폰은 회원만 받으므로 비회원 알림은 없다.
- 적용 취소 · 늦은 방문 되돌리기는 알리지 않는다(FR-NTF-03은 사용 · 만료만).

### 늦은 방문 처리 → 되돌리기 (서버 내부, 11-25 (5))

- `ReservationStatusChangedEvent`의 `fromStatus = canceled` · `toStatus = visited`(D23 늦은 방문 처리)를 `@EventListener`로 받아, 그 예약의 `apply_canceled` 쿠폰을 `applied`로 되돌린다. 사용 확인은 사장님이 따로 한다. 3.5 미확정 A-2를 이것으로 닫는다.

#### 정책에 따라 정한 것 (3.6)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 할인 계산을 서비스로도 제공 | D15가 이벤트 순서와 상관없이 같은 값을 쓴다 | D11 이벤트 표(E14 · D15 모두 `@EventListener`) |
| 사용 확인 응답 | 쿠폰 요약만. 예약 전체는 D 응답 | 사용 확인은 쿠폰 상태 변경 |
| 운영 중단 쿠폰 | `downloaded`도 `apply_canceled` | 05-policy 「상태와 전이」 |

#### 미확정 (3.6)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| S-1 | 사장님 예약 응답(D3 · D4 · D11 · O-13)의 `coupon` 칸 채우기 — E가 `CouponApplyService.appliedCouponFor(reservationId)`(3.5 모양)를 제공하고 D가 응답에 넣는다. 지금은 `null` | 사장님 화면은 사용 확인 API의 `conflict`로 판단 | D (#14 · #19) |
| S-3 | 만료 작업 실행 주기 | 메서드만 둔다 | D (D7 #103) |
| S-4 | C14 리스너: `CouponStatusChangedEvent` → 회원에게 `coupon_status` 알림 | **반영됨**(#305) | C (C14) |

### 3.7 오늘의 코스 (E10 #52 · 계약 #210)

> 상태: **계약.** 구현은 #52에서 한다. 프론트 목(A20, G-14)은 이 절을 따른다.
> 기준: 기획 저장소 `05-policy.md` 「오늘의 코스」, `03-requirements.md` FR-CRS-11 ~ 13 · FR-MAP-08, `06-data.md` COURSE_PREVIEW_EVENT, 결정 기록 11-23 (8). 노출 · 클릭 기록은 3.3 `POST /api/v1/course-previews`.

**오늘의 코스**는 같은 날 이어서 방문 처리된 기록에서 많이 이어진 3곳 동선을 자동으로 보여주는 미리보기다. 사람이 고르지 않는다. 누르면 첫 가게를 기준 가게로 골목 지도(G-01)를 열 뿐이고 예약 · 정식 코스 제안 · 소개를 만들지 않는다(FR-CRS-13, FR-CRS-07 #4).

### `GET /api/v1/today-courses` — 오늘의 코스 조회

- 역할: 누구나(로그인 불필요). 기록하지 않는다. 노출 · 클릭은 프론트가 3.3으로 따로 보낸다.
- 쿼리: `regionId` (필수). 손님 · 요청자에 따라 달라지지 않는다.
- 기준 칸은 서버 지금 시각(Asia/Seoul, 시연 시계 포함)의 요일 구분 × 시간대다.
- 응답 `200`:

  ```json
  {
    "dayType": "weekend",
    "timeBand": "evening",
    "items": [
      {
        "rank": 1,
        "stores": [
          { "id": "3", "name": "기준 카페", "category": "cafe", "alley": "center", "mapX": 100, "mapY": 80 },
          { "id": "7", "name": "구름 디저트", "category": "dessert", "alley": "center", "mapX": 150, "mapY": 110 },
          { "id": "12", "name": "달빛 포차", "category": "pub", "alley": "center", "mapX": 210, "mapY": 90 }
        ],
        "badge": "visit_data",
        "couponStoreCount": 1
      }
    ]
  }
  ```

  | 필드 | 뜻 |
  | --- | --- |
  | `dayType` · `timeBand` | 이 응답을 고른 칸. 값은 `CONTRACTS.md` 「3」. 3.3 `POST /api/v1/course-previews`에 그대로 보낸다 |
  | `items` | 최대 3개, `rank` 1부터. 동선이 없으면 `[]` — 프론트는 영역을 숨긴다(FR-CRS-11 #4, G-14) |
  | `stores` | 방문 순서대로 3곳. 모양은 3.1 RecommendationCard의 `store`와 같다. 첫 가게가 누르면 기준 가게가 되는 가게다(FR-CRS-13) |
  | `badge` | 언제나 `visit_data`(방문 데이터 기반, FR-CRS-12). 값은 `CONTRACTS.md` 「3」 추천 배지 |
  | `couponStoreCount` | 3곳 중 쿠폰 행사에 참여 중인 가게 수(0 ~ 3). 기준은 3.1 `couponBenefit`과 같다(진행 중이고 총수량이 남은 행사) |

  - **손님 수 · 비율은 응답에 없다**(FR-CRS-12 #1, FR-CUS-05). 순위만 준다.
  - **도보 정보는 응답에 없다.** 3.1 카드와 같이 FR-MAP-08 규칙으로 프론트(A18)가 표시한다 — 경로 결과가 없으면 '확인되지 않음'으로, 직선거리를 보행 거리처럼 보여주지 않는다.
  - 개인화하지 않지만 시각에 따라 바뀌므로 `Cache-Control: no-store`.
- 실패: `invalid`(`regionId` 없음 · 형식) · `not_found`(없는 권역)

#### 계산 (05-policy 「오늘의 코스」)

| 단계 | 규칙 |
| --- | --- |
| 방문 | 그 권역 가게의 `visited` 예약만. 신청 · 확정 · 취소는 세지 않는다. 실제 운영 · 시연 데이터를 모두 쓴다(E6과 같음) |
| 동선 | 한 손님의 같은 날(예약 시작 시각의 Asia/Seoul 날짜) 방문 처리 예약을 시작 시각 순(같으면 예약 ID 순)으로 늘어놓고, **연달아 있는 세 방문**을 A → B → C로 본다. A · B · C는 서로 다른 가게여야 한다. A → B → C → D면 A → B → C와 B → C → D 둘 다 센다 |
| 칸 | 그날의 요일 구분과 **첫 방문(A) 시작 시각의 시간대**가 지금 칸과 같은 동선만 센다 |
| 세기 | 동선(A, B, C)마다 고유 손님 수. 같은 손님이 다른 날 같은 동선을 또 가도 1명 |
| 제외 | 고유 손님 5명 미만인 동선(정해진 값), 지금 예약을 받지 못하는 가게(3.1 후보와 같은 기준: 승인 · `business_status = open` · 예약 받기 켜짐)가 하나라도 들어간 동선 |
| 순위 | 고유 손님 수 내림차순, 동점은 (A, B, C) 가게 ID 오름차순. 위에서부터 고르되 이미 고른 코스와 **첫 가게가 같으면 건너뛴다**. 최대 3개 |

- 예: 토요일 저녁 칸에 A→B→C 12명, A→D→E 9명, F→B→C 7명, G→H→I 4명이면 A→B→C, F→B→C 2개(FR-CRS-11 #1).
- 중간 방문이 취소되면 그 손님의 그날 방문 처리 목록에서 빠지므로 A → B → C로 세지 않는다(FR-CRS-11 #5).

#### 정책에 따라 정한 것 (3.7)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 권역 | 쿼리 `regionId`로 받는다(`GET /api/v1/stores`와 같음). 다른 권역 가게가 섞인 동선은 없다 | 권역 선택은 v1 제외지만 데이터는 권역 단위(06-data) |
| 기록 | 이 `GET`은 기록하지 않는다. 노출 · 클릭은 3.3 | 3.1 「조회와 기록을 나눈다」와 같은 이유 |

#### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 동선이 칸에 속하는 기준 | 그날의 요일 구분 + 첫 방문 시작 시각의 시간대 | 05-policy "그 칸에 속한 방문 기록만 센다" — 세 방문이 여러 시간대에 걸칠 수 있어 첫 방문으로 정함 | A20 |
| 같은 가게 반복(A → B → A) | 동선으로 세지 않는다 | FR-CRS-12 "가게 3곳" | — |
| 기간 | 쌓인 방문 기록 전체(기간 제한 없음) | 정책에 기간 없음, E6과 같음 | — |
| 동점 순서 | (A, B, C) 가게 ID 오름차순 | 05-policy "재현되도록 고정된 순서" | — |

#### 미확정 (3.7)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| T-1 | `GET /api/v1/today-courses`를 로그인 없이 여는 보안 설정 · `CONTRACTS.md` 「6」 목록에 더하기(3.1 R-10 · 3.3 V-2와 함께) | **반영됨**(#222) | C (#24) |

### 3.8 상인회 쿠폰 행사 (E18 #161 · 계약 #243)

> 상태: **계약.** 구현은 #161에서 한다. 프론트 목(A-10 상인회 쿠폰 관리)은 이 절을 따른다. 회원 알림 · 선착순 받기는 E19(#162), 예약 적용 · 기금 부담 기록은 E20(#172)이 이 절의 행사를 쓴다.
> 기준: 기획 저장소 `05-policy.md` 「상인회 쿠폰」, `03-requirements.md` FR-CPN-12 ~ 14, `07-screens.md` A-10, 결정 기록 11-25 (5). 표는 `association_coupon_event` · `association_coupon_event_store`(D24 #159), 기금 예산은 2절 「상인회 쿠폰 기금 예산」(`FundBudgetService`, D20 #160).

**상인회 쿠폰 행사**는 상인회가 만들고 할인은 **공동 기금**이 부담한다. 가게 쿠폰 행사(3.2)처럼 저장하는 순간 확정되고 **고칠 수 없으며 중단만 할 수 있다.** 만들 때 '수량 × 한 장 최대 할인액'을 기금에서 미리 잡고, 기금이 모자라면 만들 수 없다(기금은 마이너스가 되지 않는다). 권역은 **로그인한 상인회 계정의 권역**으로 정하고 권역 ID를 받지 않는다. 가게 쿠폰과 달리 권역에 진행 중인 행사가 여러 개일 수 있다.

**AssociationCouponEvent** (응답):

```json
{
  "id": "5",
  "title": "12월 골목 주말 할인",
  "benefitType": "percent_discount",
  "discountAmount": null,
  "discountPercent": 10,
  "maxDiscountAmount": 5000,
  "eligibleStoreScope": "selected",
  "storeIds": ["3", "7"],
  "totalQuantity": 10,
  "issuedCount": 6,
  "remainingQuantity": 4,
  "issueStartAt": "2026-12-01T00:00:00Z",
  "issueEndAt": "2026-12-07T15:00:00Z",
  "validStartAt": "2026-12-01T00:00:00Z",
  "validEndAt": "2026-12-14T15:00:00Z",
  "reservedBudget": 50000,
  "usedBudget": 15000,
  "remainingBudget": 35000,
  "budgetReleasedAt": null,
  "confirmedAt": "2026-11-30T03:00:00Z",
  "stoppedAt": null,
  "issuable": true
}
```

| 필드 | 뜻 |
| --- | --- |
| `title` | 행사 이름(1 ~ 50자). 회원 알림함(E19)에 보인다 |
| `benefitType` | `fixed_discount` 정액 할인 · `percent_discount` 정률 할인(`CONTRACTS.md` 「3」). **`service`(서비스 제공)는 없다** |
| `discountAmount` | 정액 할인 금액(원). `fixed_discount`일 때만 |
| `discountPercent` · `maxDiscountAmount` | 정률 할인율(퍼센트 정수 1 ~ 50)과 한 장 최대 할인액(원). `percent_discount`일 때만. **최대 할인액은 필수**(가게 쿠폰과 다름) |
| `eligibleStoreScope` · `storeIds` | 쓸 수 있는 가게. `all`(그 권역의 참여 가게 전부, `storeIds: []`) · `selected`(상인회가 고른 가게, `storeIds`에 ID 순) |
| `totalQuantity` · `issuedCount` · `remainingQuantity` | 총수량 · 지금까지 받은 수 · 남은 수. 받은 수는 E19 선착순 받기만 늘린다 |
| `issueStartAt` · `issueEndAt` | 받을 수 있는 기간 `[시작, 끝)` |
| `validStartAt` · `validEndAt` | 쓸 수 있는 기간(유효기간) `[시작, 끝)`. 기한은 예약 신청 시각으로 판단한다(E20). 받은 쿠폰의 만료 시각은 `validEndAt`이다 |
| `reservedBudget` | 만들 때 기금에서 잡은 예산 = 수량 × 한 장 최대 할인액(정액은 `discountAmount`, 정률은 `maxDiscountAmount`) |
| `usedBudget` · `remainingBudget` | 쓴 예산과 남은 예산(= 잡은 예산 − 쓴 예산). 쓴 예산은 2절 `releaseDue`와 같은 계산: 이 행사 쿠폰 중 예약에 적용 · 사용된 것의 실제 할인액, 아직 방문 전이면 한 장 최대 할인액 |
| `budgetReleasedAt` | 쓰지 않은 예산을 기금으로 돌려준 시각(유효기간이 끝난 뒤 D20 `releaseDue`). 그 전에는 `null`. **중단해도 이 시각 전에는 돌아오지 않는다** |
| `confirmedAt` · `stoppedAt` | 확정(저장) 시각 · 중단 시각(`null`이면 진행 중) |
| `issuable` | 지금 받을 수 있는지: 중단 안 됨 · 받을 수 있는 기간 안 · 남은 수 ≥ 1. 회원 화면의 '받기' · '마감'(E19)도 같은 규칙 |

### `GET /api/v1/association/coupon-events` — 상인회 쿠폰 행사 목록 (A-10)

- 역할: 상인회. 손님 · 사장님은 `403 forbidden_role`.
- 응답 `200`: `{ "items": [AssociationCouponEvent] }` — 그 권역 행사 전부, 최근에 만든 순(ID 내림차순). 행사가 많지 않아 페이지를 두지 않는다(`GET /api/v1/stores`와 같은 예외).
- 화면의 '현재 기금 잔액'은 `GET /api/v1/wallets/me`(상인회 = 기금, D9), '잡힐 예산'은 프론트가 수량 × 한 장 최대 할인액으로 미리 보여준다. 가게를 고를 때 보는 가게별 통계는 FR-ANL-05(C) API다.
- 실패: `unauthorized` · `forbidden_role`

### `POST /api/v1/association/coupon-events` — 상인회 쿠폰 행사 만들기(확정)

- 역할: 상인회. `X-XSRF-TOKEN` 필요. 저장하는 순간 확정되고 이후 어떤 값도 바꿀 수 없다.
- 요청:

  ```json
  {
    "title": "12월 골목 주말 할인",
    "benefitType": "percent_discount", "discountAmount": null, "discountPercent": 10, "maxDiscountAmount": 5000,
    "eligibleStoreScope": "selected", "storeIds": ["3", "7"],
    "totalQuantity": 10,
    "issueStartAt": "2026-12-01T00:00:00Z", "issueEndAt": "2026-12-07T15:00:00Z",
    "validStartAt": "2026-12-01T00:00:00Z", "validEndAt": "2026-12-14T15:00:00Z"
  }
  ```

- 검사(어긋나면 `400 invalid`):
  - `title` 1 ~ 50자, `benefitType` · `totalQuantity` · `eligibleStoreScope` · 네 시각 필수.
  - `benefitType`은 `fixed_discount` · `percent_discount`만(`service`는 `invalid`).
  - `fixed_discount`: `discountAmount` 1 이상 필수.
  - `percent_discount`: `discountPercent` 정수 1 ~ 50, **`maxDiscountAmount` 1 이상 필수**(FR-CPN-12 #3).
  - 고른 유형에 쓰지 않는 칸은 무시하고 저장하지 않는다(응답에서 `null`).
  - `totalQuantity` 1 이상.
  - `eligibleStoreScope`가 `selected`면 `storeIds` 1개 이상 · 중복 없음 · 모두 **그 권역의 승인된 가게**. `all`이면 `storeIds`는 무시한다.
  - 시각: `issueStartAt < issueEndAt`, `validStartAt < validEndAt`, `issueEndAt ≤ validEndAt`(쓸 수 없는 때에 받지 않게), `issueEndAt`이 지금보다 뒤.
- 기금 예산: 행사를 저장하고 **같은 트랜잭션에서** `FundBudgetService.reserve(eventId)`(D20)로 예산을 기금에서 뺀다(거래 `fund_budget_reserved`, `associationEventId` 남김). 기금이 모자라면 `409 insufficient_balance`이고 **행사도 만들어지지 않는다**(FR-CPN-12 #2).
- 응답 `201`: 만든 AssociationCouponEvent(`reservedBudget` 채움, `usedBudget` 0).
- 회원 알림(FR-CPN-13)은 E19가 이 행사로 보낸다.
- 실패: `invalid` · `insufficient_balance` · `unauthorized` · `forbidden_role` · `csrf_invalid`

### `POST /api/v1/association/coupon-events/{eventId}/stop` — 상인회 쿠폰 행사 중단

- 역할: 상인회. `X-XSRF-TOKEN` 필요. 요청 본문 없음.
- 중단하면 **새로 받기만 멈춘다.** 이미 받은 쿠폰은 유효기간까지 쓸 수 있다(FR-CPN-12 #4). **예산은 이때 돌려주지 않고** 유효기간이 끝날 때 D20 `releaseDue`가 돌려준다(FR-CPN-12 #5).
- 중단은 `stopped_at IS NULL`일 때만 바꾸는 조건부 UPDATE로 한다. E19의 발급 수 늘리기와 동시에 들어와도 먼저 커밋한 쪽만 반영된다.
- 응답 `200`: 중단된 AssociationCouponEvent(`stoppedAt` 채움, `issuable: false`).
- 실패:
  - `404 not_found` — 없는 행사 · 다른 권역 행사(존재 숨김)
  - `409 conflict` — 이미 중단한 행사
  - `unauthorized` · `forbidden_role` · `csrf_invalid`

### 고치는 API는 없다 (3.8)

- 확정한 행사를 고치거나 다시 켜는 엔드포인트를 두지 않는다(FR-CPN-12 #4). `PUT` · `PATCH`는 공통 규칙대로 `404 not_found`.

#### 다른 이슈가 쓰는 규칙 (서버 내부, 3.8)

| 규칙 | 정의 | 쓰는 곳 |
| --- | --- | --- |
| 받을 수 있음 | `stopped_at IS NULL` AND `issue_start_at ≤ 지금 < issue_end_at` AND `issued_count < total_quantity` | E19 |
| 발급 수 늘리기 | `UPDATE association_coupon_event SET issued_count = issued_count + 1 WHERE id = ? AND stopped_at IS NULL AND issued_count < total_quantity AND issue_start_at <= ? AND ? < issue_end_at` — 바뀐 행이 0이면 마감 · 중단 · 기간 밖 | E19 (NFR-09) |
| 받은 쿠폰 | `coupon.issuer_kind = 'association'`, `association_event_id` 채움, `coupon_policy_id` · `target_store_id`는 `NULL`, `expires_at = valid_end_at`. 한 회원 한 장(`uk_coupon_association_member`) | E19 |
| 쓸 수 있는 가게 | `all`: 쓸 때 그 권역의 승인된 가게. `selected`: `association_coupon_event_store`의 가게 | E20 |
| 쓸 수 있는 기간 | 예약 신청 시각이 `[valid_start_at, valid_end_at)` 안 | E20 |
| 할인액 | 3.2 「할인액」과 같은 계산(정률은 `max_discount_amount`까지) | E20 · E14 · D15 |

### 응답 필드와 테이블 칸 (3.8)

| 필드 | 칸 | 비고 |
| --- | --- | --- |
| `discountPercent` | `discount_rate` | 정수 퍼센트(DB CHECK 1 ~ 50) |
| `storeIds` | `association_coupon_event_store.store_id` | `selected`일 때만 행이 있다 |
| `reservedBudget` · `budgetReleasedAt` | `reserved_budget` · `budget_released_at` | D20이 채운다 |
| `usedBudget` · `remainingBudget` · `issuable` · `remainingQuantity` | — | 계산값(저장하지 않음) |

#### 정책에 따라 정한 것 (3.8)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 확정 시점 | 저장 = 확정. 임시 저장 없음 | FR-CPN-12 "확정 뒤에는 값을 바꾸지 않고" · 3.2와 같음 |
| 서비스 제공 혜택 | 없음(`invalid`) | 05-policy 「상인회 쿠폰」 혜택 = 정액 · 정률 |
| 중단과 예산 | 중단 때 돌려주지 않음, 유효기간 끝에 D20 | FR-CPN-12 #5 |
| 권역의 진행 중 행사 수 | 제한 없음 | 05-policy에 가게 쿠폰 같은 '하나만' 규칙 없음 |

#### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 받는 기간 끝 ≤ 쓰는 기간 끝 | `issueEndAt ≤ validEndAt`이 아니면 `invalid` | 받고 나서 못 쓰는 쿠폰을 막음 | B (A-10) |
| 받는 기간 끝 | 지금보다 뒤여야 함(이미 끝난 행사를 만들지 않음) | — | B |
| 고른 가게 | 그 권역의 승인된 가게만. 아니면 `invalid` | FR-CPN-12 "참여 가게" | — |
| 목록 | 페이지 없이 전부 | A-10 행사별 발급 수 · 남은 예산 | B |

### 3.9 상인회 쿠폰 받기 (E19 #162 · 계약 #251)

> 상태: **계약.** 구현은 #162에서 한다. 프론트 목(G-11 알림함의 상인회 쿠폰 행사 카드)은 이 절을 따른다. 행사는 3.8, 예약 적용은 E20(#172).
> 기준: 기획 저장소 `05-policy.md` 「상인회 쿠폰」, `03-requirements.md` FR-CPN-13 · FR-NTF-03, `07-screens.md` G-11, 결정 기록 11-25 (5). 알림 만들기 규칙은 1절 「알림 (C14)」.

상인회 쿠폰 행사는 **회원 알림함**에 알림으로 뜨고, 회원은 알림에서 **선착순으로** 받는다. 같은 행사에서 한 사람당 1장이다. 수량이 다 나가면 알림은 그대로 두고 카드를 '마감'으로 보여준다. **비회원은 받을 수 없다**(FR-CPN-13).

#### 행사 알림 (서버 내부, E19 → C14)

- E18 행사 만들기(3.8 `POST`)가 같은 트랜잭션에서 `AssociationCouponOpenedEvent(eventId, regionId)`를 내보낸다(`com.alleycrm.coupon`).
- C14가 `@TransactionalEventListener`(커밋 뒤)로 받아 **회원 계정(`account.role = customer`) 전부**에게 `notifyAccount(accountId, "association_coupon_opened", { "associationCouponEventId": "5" })`로 알림을 만든다(1절 「알림」 규칙: 기능은 이벤트, 알림은 C14). 기금이 모자라 행사 생성이 되돌아가면 알림도 없다.
- 비회원 예약(`guest_reservation`)에게는 만들지 않는다(FR-CPN-13 #3).
- 알림은 마감 · 중단 뒤에도 지우지 않는다. 카드 상태는 아래 `GET`으로 그때 정한다.

| `type` | 받는 쪽 | `payload` | 만드는 곳 |
| --- | --- | --- | --- |
| `association_coupon_opened` | 회원 계정 전부 | `{ "associationCouponEventId" }` | 상인회 쿠폰 행사 만들기 커밋 뒤(`AssociationCouponOpenedEvent`, C14 리스너) |

**AssociationCouponOffer** (회원이 보는 행사 카드):

```json
{
  "id": "5",
  "title": "12월 골목 주말 할인",
  "benefitType": "percent_discount",
  "discountAmount": null,
  "discountPercent": 10,
  "maxDiscountAmount": 5000,
  "eligibleStoreScope": "selected",
  "stores": [ { "id": "3", "name": "구름 디저트" }, { "id": "7", "name": "달빛 포차" } ],
  "issueStartAt": "2026-12-01T00:00:00Z",
  "issueEndAt": "2026-12-07T15:00:00Z",
  "validStartAt": "2026-12-01T00:00:00Z",
  "validEndAt": "2026-12-14T15:00:00Z",
  "status": "available",
  "myCouponId": null
}
```

| 필드 | 뜻 |
| --- | --- |
| 혜택 · 기간 칸 | 3.8 AssociationCouponEvent와 같다 |
| `stores` | 쓸 수 있는 가게. `selected`면 고른 가게(ID 순, 이름 포함), `all`이면 `[]` — 화면은 '모든 참여 가게'로 보여준다 |
| `status` | 이 회원에게 보이는 카드 상태(`CONTRACTS.md` 「3」 `associationCouponOffer.status`). 아래 순서로 처음 맞는 것: `received` 이미 받음 → `closed` 마감(다 나감 · 중단 · 받는 기간 끝) → `upcoming` 받는 기간 전 → `available` 받을 수 있음 |
| `myCouponId` | 받았으면 그 쿠폰 ID, 아니면 `null` |

- 발급 수 · 남은 수 · 예산은 회원에게 보여주지 않는다.

### `GET /api/v1/association-coupon-events/{eventId}` — 상인회 쿠폰 행사 카드 (G-11)

- 역할: 회원 손님. 비로그인 `401 unauthorized`, 사장님 · 상인회 · 비회원 제한 세션 `403 forbidden_role`.
- 응답 `200`: AssociationCouponOffer.
- 실패: `not_found`(없는 행사, 숫자가 아닌 ID) · `unauthorized` · `forbidden_role`

### `POST /api/v1/association-coupon-events/{eventId}/claim` — 상인회 쿠폰 받기 (선착순)

- 역할: 회원 손님(위와 같음). `X-XSRF-TOKEN` 필요. 요청 본문 없음.
- 검사 순서:
  1. 없는 행사면 `404 not_found`.
  2. 이미 이 행사 쿠폰을 받았으면 `409 conflict`(한 사람당 1장, FR-CPN-13 #2).
  3. 받을 수 없으면 `409 conflict`: 다 나감(FR-CPN-13 #1) · 중단 · 받는 기간 전 · 받는 기간 끝(#4). 화면은 위 `GET`의 `status`로 '마감'을 보여준다(에러 응답에 이유를 담지 않는다).
- 응답 `201` (Coupon, 3.4와 같은 모양에 상인회 칸):

  ```json
  {
    "couponId": "801",
    "issuerKind": "association",
    "associationCouponEventId": "5",
    "eventId": null,
    "storeId": null,
    "status": "downloaded",
    "benefitType": "percent_discount",
    "discountAmount": null,
    "discountPercent": 10,
    "maxDiscountAmount": 5000,
    "serviceName": null,
    "conditions": null,
    "downloadedAt": "2026-12-01T09:00:00Z",
    "expiresAt": "2026-12-14T15:00:00Z"
  }
  ```

  - `issuerKind`는 `coupon.issuerKind`(`CONTRACTS.md` 「3」). 가게 쿠폰 칸(`eventId` · `storeId`)은 `null`, 쓸 수 있는 가게는 행사 카드의 `stores`.
  - `expiresAt` = 행사의 `validEndAt`(3.8 「받은 쿠폰」).
- 실패: `unauthorized` · `forbidden_role` · `not_found` · `conflict` · `csrf_invalid`

#### 원자 처리 (서버 내부, NFR-09)

- 한 트랜잭션에서 **발급 수 조건부 UPDATE를 먼저** 한다(3.8 「발급 수 늘리기」). 바뀐 행이 0이면 `409`. 이 UPDATE가 행사 행을 잠가, 같은 행사의 받기가 커밋까지 한 줄로 선다.
- 그다음 쿠폰을 넣는다. 같은 회원이 동시에 두 번 눌러도 `uk_coupon_association_member`가 한 장만 남기고, 그 위반은 `409 conflict`로 바꾸며 발급 수 증가도 함께 되돌린다.
- 동시에 많이 눌러도 받은 수는 총수량을 넘지 않는다(E9와 같은 방식으로 검증).

#### 정책에 따라 정한 것 (3.9)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 알림 받는 사람 | 회원 계정 전부(권역 선택이 v1 제외라 한 권역) | FR-CPN-13 "회원 알림함", v1 제외: 권역 선택 |
| 알림 만드는 곳 | 기능은 이벤트, 알림은 C14 리스너 | 1절 「알림」 서버 내부 규칙 |
| 마감 표시 | 알림은 남기고 카드 `status`로 | FR-CPN-13 #1 |

#### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 알림 시점 | 행사를 만들 때 한 번(받는 기간이 나중에 시작해도). 그 전에는 카드가 `upcoming` | FR-CPN-13 | A (G-11) |
| 받기 실패 이유 | 모두 `409 conflict`, 이유는 `GET`의 `status`로 | 새 에러 코드를 만들지 않음 | A |
| 남은 수 표시 | 회원에게 보여주지 않음 | 05-policy에 없음 | A |

#### 미확정 (3.9)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| U-2 | 회원 쿠폰함 · 예약 화면의 쿠폰 목록에 상인회 쿠폰을 넣는 것(3.5 A-1) | E20에서 정한다 | E (#172) |

### 3.10 상인회 쿠폰 적용 · 기금 부담 (E20 #172 · 계약 #257)

> 상태: **계약.** 구현은 #172에서 한다. 3.5(쿠폰 적용) · 3.6(방문 할인 · 사용 확인)에 상인회 쿠폰을 더하는 절이다. 행사는 3.8, 받기는 3.9, 수수료는 2절 D15, 예산 돌려주기는 2절 D20.
> 기준: 기획 저장소 `05-policy.md` 「상인회 쿠폰」, `03-requirements.md` FR-CPN-06 · 14 · FR-STL-02, 결정 기록 11-25 (5).

상인회 쿠폰은 **행사가 정한 가게의 예약에만** 쓴다. 예약 한 건에는 가게 쿠폰과 상인회 쿠폰 중 **한 장만** 적용한다(FR-CPN-06 · 14). 할인은 **공동 기금**이 부담하고, 수수료는 가게 쿠폰과 같이 할인 후 금액 × 수수료율이다. 소개를 만들지 않고 추천 순위에 쓰지 않으며, 사용 확인은 방문 처리와 따로 사장님이 한다.

#### Coupon에 더하는 칸 (3.4 · 3.5 · 3.9 공통)

| 필드 | 뜻 |
| --- | --- |
| `issuerKind` | `store` 가게 쿠폰 · `association` 상인회 쿠폰(`CONTRACTS.md` 「3」 `coupon.issuerKind`) |
| `associationCouponEventId` | 상인회 쿠폰이면 받은 행사 ID(3.8), 가게 쿠폰이면 `null` |

- 가게 쿠폰은 지금처럼 `eventId` · `storeId`가 있고 `issuerKind: "store"`, `associationCouponEventId: null`이다.
- 상인회 쿠폰은 `eventId` · `storeId` · `serviceName` · `conditions`가 `null`이다(3.9 받기 응답과 같은 모양). 쓸 수 있는 가게는 행사 카드(3.9 `GET`)의 `stores`.

#### `GET /api/v1/coupons/usable?storeId=` (3.5)에 상인회 쿠폰을 더한다 — 3.5 미확정 A-1 닫음

- 같은 목록 · 같은 `couponId`로 준다. 이 회원의 쿠폰 중 **그 가게에서 지금 쓸 수 있는** 것: 가게 쿠폰(3.5 그대로)과 아래 조건의 상인회 쿠폰.
- 상인회 쿠폰을 그 가게에서 쓸 수 있음(3.8 「쓸 수 있는 가게 · 기간」):
  - 상태 `downloaded`, 지금 < `expiresAt`(= 행사 `validEndAt`), **행사 `validStartAt` ≤ 지금**
  - 쓸 수 있는 가게: `all`이면 그 가게가 행사 권역의 승인된 가게, `selected`면 행사가 고른 가게에 있음
- 정렬: 종류와 상관없이 `expiresAt` 오름차순(같으면 `couponId` 순). 서버가 고르지 않는다(FR-CPN-06).

#### 예약 신청 단계 60 · 90 (3.5)에 상인회 쿠폰을 더한다

| 조건(위에서부터) | 실패 |
| --- | --- |
| 쿠폰이 없거나 이 회원의 쿠폰이 아님 | `404 not_found` |
| 가게 쿠폰: 대상 가게가 신청한 가게가 아님 / 상인회 쿠폰: 신청한 가게가 쓸 수 있는 가게가 아님(FR-CPN-14 #2) | `409 conflict` |
| 상태가 `downloaded`가 아님 | `409 conflict` |
| 신청 시각 ≥ `expiresAt`, 상인회 쿠폰은 신청 시각 < 행사 `validStartAt`도 | `409 conflict` |

- 쿠폰은 한 장만(`couponId` 하나, 배열이면 `400 invalid`) — 가게 쿠폰과 상인회 쿠폰을 같이 쓸 수 없다(FR-CPN-14 #1).
- 적용 · 방문 전 취소 · 늦은 방문 되돌리기 · 만료는 가게 쿠폰과 같다(3.5 · 3.6). 만료는 `expiresAt`(= 행사 `validEndAt`)으로 E14 `expireDue`가 한다.
- 예약 응답 · 사장님 예약 응답의 `coupon`(3.5 `AppliedCoupon`)도 상인회 쿠폰을 같은 모양으로 채운다(혜택은 행사 값, `serviceName: null`).

#### 방문 할인 · 기금 부담 (3.6에 더함)

- `CouponVisitService.discountFor(reservationId, visitAmount)`가 상인회 쿠폰도 준다: `VisitCouponDiscount(couponId, issuerKind = ASSOCIATION, discountAmount, couponCost = discountAmount)`. 할인액 계산은 3.2 「할인액」과 같다(정액은 방문 금액까지, 정률은 `max_discount_amount`까지, 1원 미만 버림).
- 방문 처리 때 `recordDiscount`가 상인회 쿠폰에도 실제 할인액을 `discount_applied`에 남긴다. 상태는 '예약에 적용됨' 그대로이고 사용 확인(3.6 `coupon-use`)으로 '사용 완료'가 된다(FR-CPN-14 #4).
- **수수료(D15)**: 계산 금액 = 방문 금액 − 상인회 쿠폰 할인. `referral_fee.coupon_cost`는 0(가게 부담 아님). 가게 잔액에서 쿠폰 비용을 빼지 않는다(FR-CPN-14 #3) — D15가 `issuerKind`로 이미 이렇게 한다.
- **기금 부담 기록**: 할인액은 행사를 만들 때 잡은 기금 예산(3.8 `reservedBudget`)에서 쓰인 것으로 본다. 쿠폰의 `discount_applied`가 그 기록이고, 3.8 `usedBudget`과 2절 D20 `releaseDue`(쓰지 않은 예산 돌려주기)가 이 값을 읽는다. 따로 거래를 만들지 않는다(예산을 잡을 때 기금에서 이미 뺐다).

#### 정책에 따라 정한 것 (3.10)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 목록 · ID | 가게 쿠폰과 같은 목록 · 같은 `couponId`, 종류는 `issuerKind` | FR-CPN-06 예약 화면은 회원이 한 장 고름 |
| 쓸 수 있는 기간 시작 | 신청 시각 < 행사 `validStartAt`이면 쓸 수 없음 | 05-policy "기한은 예약 신청 시각으로 판단" |
| 기금 부담 기록 | 쿠폰 `discount_applied`(새 거래 없음) | FR-CPN-12 예산은 만들 때 기금에서 미리 잡음 |
| 가게 휴업 · 운영 중단 | 상인회 쿠폰은 바꾸지 않는다(다른 가게에서 쓸 수 있음). 그 가게 예약이 취소되면 3.5대로 적용 취소 | FR-CPN-07 #4는 대상 가게가 하나인 가게 쿠폰 규칙 |

### 3.11 리롤 · 거절 · 다음 가게 추천 알림 (E8 #49 · 계약 #284)

> 상태: **계약.** 구현은 #49에서 한다. 묶음 넘기기(리롤 화면)는 3.1 `page`로 이미 된다(FR-CRS-06 #1 · #2 · #4). 이 절은 거절 · 리롤 **기록**과 예약 뒤 **다음 가게 추천 알림**을 더한다.
> 기준: 기획 저장소 `05-policy.md` 「다음 방문 추천」 6단계(리롤 · 거절 · 추천 알림), `03-requirements.md` FR-CRS-02 · 06 · FR-NTF-03, `06-data.md` RECOMMENDATION_FEEDBACK. 알림 만들기 규칙은 1절 「알림 (C14)」.

#### `POST /api/v1/recommendation-feedback` — 추천 거절 · 리롤 기록

- 역할: 누구나(로그인 불필요, 3.1 지도 추천과 같음). 상태 변경이라 `X-XSRF-TOKEN` 필요.
- 요청: `{ "type": "reject", "exposureIds": ["123", "124", "125"] }`
  - `type`: `reject` 거절 · `reroll` 리롤(`CONTRACTS.md` 「3」 `feedback.type`). **사유는 받지 않는다**(FR-CRS-06).
  - `exposureIds`: 3.1 `POST /api/v1/recommendation-exposures`로 받은 노출 ID 1 ~ 3개, 중복 없음. 리롤은 다음 묶음을 부르기 전 지금 묶음의 노출들, 거절은 거절한 카드(묶음 전체면 묶음의 노출들).
- 노출은 **이 요청자의 것**이어야 한다: 이 브라우저의 방문자(3.3 쿠키)의 노출이거나 로그인한 회원에 이어진 방문자의 노출(3.4 쿠폰 다운로드와 같은 규칙). 아니면 `404 not_found`(존재 숨김)이고 아무것도 기록하지 않는다.
- 같은 (노출, 종류)는 한 번만 남긴다. 다시 보내도 `204`이고 새 행을 만들지 않는다.
- **순위 · 후보에 쓰지 않는다**(FR-CRS-06 #3). 같은 조건으로 다시 조회하면 순위가 같다.
- 응답 `204`. 쿠키가 없었으면 `Set-Cookie: alley_vid=…`(3.3).
- 실패: `invalid`(type 값 · exposureIds 0개 · 3개 초과 · 중복 · 숫자 아님) · `not_found` · `csrf_invalid`

#### 다음 가게 추천 알림 (서버 내부, E8 → C14)

- 예약 신청이 커밋된 뒤(`ReservationRequestedEvent`, D2) E가 **자기 트랜잭션**에서 그 예약의 다음 가게 추천을 계산한다: 3.1 지도 추천과 같은 계산, 기준 가게 = 그 예약의 가게, **기준 시각 = 그 예약 시각**(FR-CRS-02 #2), 요청자 = 그 손님(회원 · 비회원 모두. 같은 날 제외 · 성별 · 연령대는 3.1 「손님 식별에 따른 차이」).
- 후보가 1곳 이상이면 같은 트랜잭션에서 `NextStoreRecommendedEvent(reservationId, storeId, customerId)`(`com.alleycrm.recommend`)를 내보낸다. 후보가 없으면 내보내지 않는다.
- 계산이 실패해도 **예약은 그대로**다(예약 커밋 뒤라 되돌리지 않음). 알림만 생기지 않는다.
- C14가 `@TransactionalEventListener`(커밋 뒤)로 받아 알림을 만든다(1절 규칙: 기능은 이벤트, 알림은 C14). 받는 쪽은 그 예약의 손님 — 회원이면 계정(`notifyAccount`), 비회원이면 그 예약(`notifyGuestReservation`, C13 제한 세션에서 봄).

| `type` | 받는 쪽 | `payload` | 만드는 곳 |
| --- | --- | --- | --- |
| `next_store_recommended` | 그 예약의 손님(회원 계정 · 비회원 예약) | `{ "reservationId", "baseStoreId" }` | 예약 신청 커밋 뒤 E 계산(`NextStoreRecommendedEvent`, C14 리스너) |

- 화면: 알림을 누르면 `baseStoreId`를 기준 가게로 G-01을 연다(FR-CRS-02 #1). 추천 카드는 3.1 `GET /api/v1/recommendations`(회원은 그 가게에 예약이 있어 기준 시각이 예약 시각, 3.1 R-6). 정식 코스 제안은 3.1 `GET /api/v1/course-proposals?baseReservationId=`로 열고, 이미 표시된 같은 제안과 1건으로 센다(FR-CRS-02 #3, 3.1 「조합당 1건」).
- 알림은 숫자 근거(점수 · 손님 수)를 담지 않는다(FR-CRS-05).

#### 정책에 따라 정한 것 (3.11)

| 항목 | 정한 것 | 근거 |
| --- | --- | --- |
| 거절 단위 | 노출 ID(카드 한 장 또는 묶음) | 06-data RECOMMENDATION_FEEDBACK `exposure_id` |
| 거절 · 리롤과 순위 | 기록만, 순위 · 후보에 쓰지 않음 | FR-CRS-06, 05-policy 6단계 |
| 알림 계산 | 지도 추천과 같은 계산, 기준 시각 = 예약 시각 | FR-CRS-02 #2, 05-policy 6단계 "추천 알림도 같은 계산" |

#### 구현 기준 (정책에 명시 없음 — 관련 담당 확인 요청)

| 항목 | 이 계약의 기준 | 정책 근거 | 확인할 담당 |
| --- | --- | --- | --- |
| 알림 시점 | 예약 **신청** 커밋 뒤 한 번(확정 때 다시 보내지 않음) | FR-CRS-02 "예약 뒤" | A (G-11) |
| 후보 없음 | 알림을 만들지 않음 | 빈 추천 알림 방지 | A |
| 같은 (노출, 종류) 다시 보냄 | 한 번만 기록 | 네트워크 재전송 | — |

#### 미확정 (3.11)

| # | 내용 | 정해지지 않은 동안 | 결정 주체 |
| --- | --- | --- | --- |
| N-1 | C14 리스너: `NextStoreRecommendedEvent` → 손님(회원 계정 · 비회원 예약)에게 `next_store_recommended` 알림, 1절 표 줄 | **반영됨**(#298) | C (C14) |
| N-2 | 이 절의 `POST` 경로를 로그인 없이 여는 보안 설정(3.1 R-10 · 3.3 V-2와 함께) | **반영됨**(#298). CSRF 토큰은 계속 필요 | C |
