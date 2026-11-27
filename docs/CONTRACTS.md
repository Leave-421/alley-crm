# 공통 계약 — 규칙 · Entity · ID · 에러 · 보안

> 상태: 공통 규칙 · 에러 · 인증은 C1(#5)에서 정했다. 역할 · Entity · 상태 이름은 C2 · D1에서 채운다.
> 이 파일과 `CONTRACTS_API.md`가 API 계약의 **유일한 기준**이다. 계약을 바꾸는 PR은 이 문서와 코드를 함께 바꾼다.

## 1. 공통 규칙

- 시각은 UTC, `Z`로 끝나는 ISO 8601.
- API의 ID는 문자열(Entity는 `Long`).
- 에러 응답: `{ "error": { "code": "...", "message": "..." } }`, code는 소문자 snake.

## 2. 역할

계정의 `role` 값(C2). 비회원은 계정이 없고 비회원 예약 조회의 제한 세션을 받는다(C13).

| 역할 | `role` | 설명 |
| --- | --- | --- |
| 손님 (회원) | `customer` | 회원가입으로 만든다. 성별 · 연령대를 회원정보에 둔다 |
| 손님 (비회원) | — | 계정 없음. 예약번호 + 전화번호로 확인(C13) |
| 사장님 | `owner` | 가게당 1개(`storeId`). 직원 계정 없음. 가게 가입 승인 뒤 쓴다(C5) |
| 상인회 | `association` | 권역당 1개(`regionId`). 가입 기능 없이 초기 데이터로만 만든다 |

## 3. Entity · 상태

D1(#12)에서 정한 enum 값의 **유일한 목록**이다. DB에는 이 문자열 그대로(소문자 snake, `VARCHAR`) 저장하고, API 요청 · 응답 · 프론트 목도 같은 값을 쓴다. 값을 더하거나 바꾸는 것은 계약 변경이다(담당 D에게 요청).
테이블 · 칸의 기준은 기획 저장소 `docs/06-data.md`, 마이그레이션은 `backend/src/main/resources/db/migration/`.

| 대상 | 값 → 뜻 |
| --- | --- |
| 가게 업종 `store.category` | `restaurant` 음식점 · `cafe` 카페 · `dessert` 디저트 · `pub` 주점 · `workshop` 공방 · `bookstore` 서점 · `gift_shop` 소품샵 · `stationery` 문구점 |
| 소속 골목 `store.alley` | `front_gate` 정문 앞 · `center` 중앙 · `back_street` 뒷길 |
| 가게 가입 상태 `store.joinStatus` | `requested` 신청 · `approved` 승인 · `rejected` 거절 · `withdraw_requested` 탈퇴 신청 · `withdrawn` 탈퇴 |
| 영업 상태 `store.businessStatus` | `open` 영업 · `paused` 휴업 · `closed` 운영 중단 (사장님이 바꿈) |
| 휴무일 `store.closedDays` | `mon` · `tue` · `wed` · `thu` · `fri` · `sat` · `sun`의 목록. 빈 목록이면 연중무휴 |
| 역할 `account.role` | `customer` 손님(회원) · `owner` 사장님 · `association` 상인회 (「2. 역할」) |
| 손님 종류 `customer.type` | `member` 회원 · `guest` 비회원 |
| 성별 `gender` | `female` 여성 · `male` 남성. **응답 안 함은 `null`** |
| 연령대 `ageGroup` | `age_20_or_under` 20대 이하 · `age_30s` 30대 · `age_40s` 40대 · `age_50_or_over` 50대 이상. 모르면 `null` |
| 공유 항목 `shareItem` | `name` 이름 · `phone` 연락처 · `visited_stores` 방문한 가게 목록 (이 셋은 항상 공유, 끌 수 없음) · `party_size` 인원 · `visit_datetime` 방문 일시 · `gender_age_group` 성별·연령대 (상인회가 켜고 끔, FR-CUS-06) |
| 데이터 출처 `dataSource` | `real` 실제 운영 · `demo` 시연용 가상 · `public` 외부 공개 통계 (NFR-10) |
| 예약 상태 `reservation.status` | `requested` 신청 → `confirmed` 확정 → `visited` 방문, 그리고 `canceled` 취소 (노쇼 상태 없음) |
| 취소 사유 `reservation.cancelReason` | `customer_canceled` 손님 취소 · `store_rejected` 가게 거절(자동 거절 포함) · `auto_canceled` 자동 취소 |
| 추천 배지 `badge` | `visit_data` 방문 데이터 기반 · `local_popular` 지역 인기 가게 |
| 추천 피드백 `feedback.type` | `reject` 거절 · `reroll` 리롤 |
| 오늘의 코스 기록 `type` | `impression` 노출 · `click` 클릭 |
| 요일 구분 `dayType` | `weekday` 평일 · `weekend` 주말 |
| 시간대 `timeBand` | `night` 00 ~ 06시 · `morning` 06 ~ 11시 · `lunch` 11 ~ 14시 · `afternoon` 14 ~ 17시 · `evening` 17 ~ 21시 · `late` 21 ~ 24시 (운영 시간대, 서울시 추정매출 6구간 그대로. E2) |
| 공공데이터 종류 `publicStat.kind` | `attractiveness` 매력도(업종 매출 비중) · `clq` 업종 동반 입지(A → B) · `day_share` 요일 비중 · `time_band_share` 시간대 비중 · `gender_share` 성별 비중 · `age_share` 연령대 비중. 모두 관측값이고, 교차값(요일 × 시간대 · 성별 × 연령대)은 저장하지 않고 추천 계산 때 곱해 추정한다(결정 11-26). 표 `public_stat` 행은 모두 외부 공개 통계(`dataSource = public`)이고 행마다 `source` · `period`(화면 출처 표시)가 있다. E2 |
| 쿠폰 혜택 `benefitType` | `fixed_discount` 정액 할인 · `percent_discount` 정률 할인(정수 1 ~ 50%, 최대 할인액 선택, 1원 미만 버림) · `service` 서비스 제공 (11-25) |
| 쿠폰 상태 `coupon.status` | `downloaded` 다운로드됨 · `applied` 예약에 적용됨 · `used` 사용 완료 · `expired` 기간 만료 · `apply_canceled` 적용 취소 |
| 쿠폰 발급 주체 `coupon.issuerKind` | `store` 가게 쿠폰(가게 부담) · `association` 상인회 쿠폰(공동 기금 부담) (11-25 (5)) |
| 상인회 쿠폰 쓸 수 있는 가게 `associationCouponEvent.eligibleStoreScope` | `all` 모든 참여 가게 · `selected` 상인회가 고른 가게 |
| 상인회 쿠폰 카드 상태 `associationCouponOffer.status` | `available` 받을 수 있음 · `upcoming` 받는 기간 전 · `received` 이미 받음 · `closed` 마감(다 나감 · 중단 · 받는 기간 끝) (E19, CONTRACTS_API 3.9) |
| 방문 신고 종류 `visitReport.type` | `amount` 금액이 달라요 · `not_processed` 방문했어요(자동 취소된 예약만) |
| 방문 신고 상태 `visitReport.status` | `received` 접수 → `owner_check_requested` 사장님 확인 요청 → `done` 처리 완료 |
| 방문 신고 결과 `visitReport.result` | `valid` 맞음 · `invalid` 아님 (`done`일 때만. 허위 신고 페널티 없음) |
| 지갑 주인 `wallet.ownerType` | `customer` 회원 손님 · `store` 가게 · `fund` 상인회 기금 |
| 거래 종류 `walletTx.type` | `charge` 충전 · `deposit_paid` 예약금 납부 · `deposit_refunded` 예약금 환불 · `referral_fee_charged` 소개 수수료 차감 · `fund_deposited` 기금 입금 · `fund_budget_reserved` 기금 예산 잡기 · `fund_budget_released` 기금 예산 해제(상인회 쿠폰 행사) · `fee_difference_charged` 수수료 차액 차감 · `fee_difference_deposited` 수수료 차액 기금 입금(방문 금액 정정) (11-25 (5)) |
| 월 정산 상태 `settlement.status` | `aggregated` 집계됨 · `confirmed` 확정 |
| 알림 받는 쪽 `notification.recipientType` | `account` 계정(회원 · 사장님) · `guest_reservation` 비회원 예약 |
| 알림 종류 `notification.type` | `reservation_requested` 새 예약 · `reservation_confirmed` 확정 · `reservation_rejected` 거절 · `reservation_canceled` 취소 · `change_requested` 변경 요청 · `change_approved` 변경 승인 · `change_rejected` 변경 거절 · `reminder` 리마인드 · `delay_reported` 지연 입력 · `next_store_recommended` 다음 가게 추천 · `coupon_status` 쿠폰 상태 · `store_join_result` 가게 가입 결과. 종류를 늘리는 것은 C14에서 이 표를 고친다 11-25 (5)로 더함: `visit_amount_recorded` 방문 금액(회원) · `association_coupon_opened` 상인회 쿠폰 행사(회원) · `visit_report_check_requested` 신고 확인 요청(사장님). |

- API JSON 필드 이름은 camelCase(`joinStatus`), DB 칸 이름은 snake_case(`join_status`)다. 값은 둘 다 위 문자열 그대로다.

### 설정값 키

코드 `com.alleycrm.setting.SettingKey`가 키와 기본값의 기준이고, `SettingService`로만 읽는다(NFR-04). 권역에서 바꾼 값만 `setting` 테이블에 둔다. 기준: 기획 저장소 `docs/05-policy.md` 「설정값」 · 「추천 설정값」.

| 키 | 기본값 | 뜻 |
| --- | --- | --- |
| `referral_fee_rate` | 0.04 | 소개 수수료율. 수수료 기록에 생길 때 값을 저장한다 |
| `revisit_days` | 60 | 재방문 기간(일) |
| `late_cancel_refund_rate` | 0.5 | 하루 전이 지난 뒤 취소의 환불 비율 기본값. 가게 값(`store.late_cancel_refund_rate`)이 있으면 그 값 |
| `guest_retention_months` | 6 | 비회원 정보 보관(마지막 방문 후, 개월) |
| `guest_no_visit_retention_days` | 30 | 방문 기록 없는 비회원 보관(마지막 예약 취소 후, 일) |
| `recommend_weight_attractiveness` · `_distance` · `_clq` · `_time_band` · `_demographic` | 0.25 · 0.25 · 0.25 · 0.125 · 0.125 | 추천 기본값 가중치 |
| `recommend_smoothing_k` | 10 | 평활 상수 k |
| `recommend_group_ratio` | 0.3 | 성별·연령대 집단 비율 g |
| `recommend_min_unique_customers` | 5 | 실제 기록 최소 고유 손님 수 |
| `recommend_distance_exponent` | 1.0 | 거리 지수 β — **기획 미결, 임시 기본값** |
| `recommend_count` | 3 | 추천 개수 |

### 추천 (담당 E, `CONTRACTS_API.md` 3.1)

추천 배지 `badge`는 위 표에 있다.

| enum | 값 |
| --- | --- |
| 이유 종류 `reasonType` | `visit_data` 방문 데이터 · `colocation` 업종 동반 입지 · `time_band` 시간대 · `attractiveness` 매력도 · `demographic` 손님층 · `distance` 거리 |
| 기준 시각 출처 `baseTimeSource` | `now` 현재 시각 · `reservation` 기준 가게 예약 시각 |

### 쿠폰 (담당 E, `CONTRACTS_API.md` 3.4)

쿠폰 혜택 `benefitType` · 쿠폰 상태 `coupon.status`는 위 표에 있다.

| enum | 값 |
| --- | --- |
| 다운로드 가능 여부 `downloadStatus` | `available` 받을 수 있음 · `login_required` 로그인 필요 · `waiting` 30일 대기 · `applied_unused` 같은 행사의 미사용 적용 쿠폰 있음 · `unavailable` 받을 수 없음(행사 없음 · 소진 · 가게 영업 중 아님) |

## 4. 에러 코드

| code | HTTP | 언제 |
| --- | --- | --- |
| `invalid` | 400 | 입력 검증 실패 |
| `unauthorized` | 401 | 로그인 안 됨 |
| `forbidden_role` | 403 | 역할 위반 |
| `not_found` | 404 | 없음 · 다른 가게 것(존재 숨김) |
| `conflict` | 409 | 상태 충돌(이미 찬 슬롯 등) |
| `slot_unavailable` | 409 | 예약을 받을 수 없는 시각(지난 시각 · 기간 밖 · 영업시간 밖 · 휴무 · 슬롯 참 · 가게가 예약을 안 받음) |
| `insufficient_balance` | 409 | 회원 가상 잔액이 예약금보다 적음(충전 안내) |
| `csrf_invalid` | 403 | 상태 변경 요청에 CSRF 토큰이 없거나 맞지 않음 |
| `internal` | 500 | 예상하지 못한 서버 오류(내부 메시지는 내보내지 않음) |

코드 기준: `backend/.../common/error/ErrorCode.java`. 새 code를 더하는 것은 계약 변경이다(이 표와 코드를 같은 PR에서). 없는 경로 · 허용되지 않은 메서드는 `not_found`로 응답한다.

## 5. 보안 검사 순서

로그인 → 골목 접근 → 가게 접근 → 역할 (`.claude/rules/security.md`)

## 6. 인증 · 세션 · CSRF

- 서버 세션 쿠키(Spring Security HttpSession). JWT를 쓰지 않는다. 세션 쿠키는 `HttpOnly` · `SameSite=Lax`, HTTPS에서 `Secure`.
- 기본은 모든 `/api/**`가 로그인 필요다. 로그인 없이 여는 경로는 이 절에 적는다: `GET /api/v1/csrf`, `GET /actuator/health`, `POST /api/v1/auth/signup`, `POST /api/v1/auth/login`, `POST /api/v1/store-joins`, `GET /api/v1/stores`, `GET /api/v1/regions`, `GET /api/v1/stores/{storeId}/available-slots`, `POST /api/v1/reservations`, `GET /api/v1/recommendations`, `POST /api/v1/recommendation-exposures`, `POST /api/v1/store-clicks`, `GET /api/v1/coupon-offers/{storeId}`, `GET /api/v1/today-courses`, `POST /api/v1/guest/lookup`. (3.3 `POST /api/v1/course-previews`는 그 API를 만드는 PR에서 더한다.) 로그인 없이 여는 `POST`도 CSRF 토큰은 필요하다.
- 로그인에 성공하면 세션 ID를 새로 발급한다(세션 고정 방지). 로그아웃은 `POST /api/v1/auth/logout`만 받는다.
- **비회원 제한 세션(C13):** `POST /api/v1/guest/lookup`에서 예약번호 + 전화번호가 맞으면 세션 ID를 새로 발급하고, 그 전화번호의 비회원 예약만 다루는 세션을 준다. 이 세션으로 쓸 수 있는 로그인 필요 경로는 `/api/v1/guest/**` · `GET /api/v1/notifications` · `POST /api/v1/notifications/{id}/read` · `POST /api/v1/auth/logout`뿐이고, 그 밖의 로그인 필요 경로는 `403 forbidden_role`이다. 로그인 없이 여는 경로는 그대로 쓸 수 있다(새 예약 신청은 비회원 신청). 서버 코드에서는 `GuestPrincipal(phone)`, 요청자의 예약인지는 `GuestSessionService.reservationIds`로 확인한다.
- **CSRF:** 앱을 처음 열 때 `GET /api/v1/csrf`를 부르면 `XSRF-TOKEN` 쿠키가 내려온다. 상태 변경 요청(`POST` · `PUT` · `PATCH` · `DELETE`)은 그 값을 `X-XSRF-TOKEN` 헤더로 보낸다. 없거나 틀리면 `403 csrf_invalid`.
- 로그인 안 된 요청은 리다이렉트하지 않고 `401 unauthorized` JSON으로 응답한다.
- SSE 연결도 같은 세션 쿠키로 인증한다.
