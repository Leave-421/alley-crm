# 공통 계약 — 규칙 · Entity · ID · 에러 · 보안

> 상태: 뼈대. 기획(요구사항 · 데이터 설계) 확정 뒤 채운다. TODO
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

## 5. 보안 검사 순서

로그인 → 골목 접근 → 가게 접근 → 역할 (`.claude/rules/security.md`)
