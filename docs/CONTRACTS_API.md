# API 계약 — 엔드포인트 · DTO

> 상태: 뼈대. 기획 확정 뒤 채운다. TODO
> 형식: 엔드포인트마다 `메서드 경로` · 역할 · 요청 DTO · 응답 DTO · 실패 코드.

## 예시 형식

### `POST /api/v1/reservations` — 예약 신청

- 역할: 손님
- 요청: `{ "storeId": "string", "slotStart": "2026-12-01T09:00:00Z", "partySize": 2, "shareConsent": true }`
- 응답 `201`: `{ "id": "string", "status": "requested" }`
- 실패: `invalid` · `conflict` · `not_found`
