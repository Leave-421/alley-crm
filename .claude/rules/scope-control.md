# Scope Control

Claude가 작업하면서 "같이 고치면 좋아 보이는 것"을 발견해도 자동으로 범위를 넓히지 않는다.

현재 Issue와 무관하면:
1. 문제를 짧게 기록한다.
2. 현재 작업에는 포함하지 않는다.
3. 필요하면 별도 Issue 후보로 제안한다.

다음 변경은 명시적 근거 없이 하지 않는다.

- folder 구조 전면 변경
- framework/library 교체
- package manager 변경
- dependency 대량 업데이트
- formatter 전체 적용
- naming convention 일괄 변경
- API contract 변경
- DB migration/schema 변경
- auth 정책 변경
- CI/CD 또는 deployment target 변경

## 이 프로젝트 범위

- **확정된 요구사항 범위를 줄이거나 늘리지 않는다.** 기준은 기획 저장소의 요구사항 정의서와 v1 결정 정리다.
- **v1에서 뺀 것(캠페인 발송 · 쿠폰 · 노쇼 상태/관리/페널티)을 만들지 않는다.**
- **실제 결제 · 송금 · 문자/카카오 발송 연동을 만들지 않는다.** 돈은 가상 잔액, 알림은 앱 · 웹 안에서만.
- 단일 Spring Boot 애플리케이션이다. 마이크로서비스 · 메시지 브로커 같은 구조를 들이지 않는다.
- **계약(API 경로 · DTO 필드 · enum · 에러 코드 · Entity)을 코드에서 먼저 바꾸지 않는다.** 필요하면 계약 문서와 함께 바꾸고, 사용자에게 먼저 묻는다.
