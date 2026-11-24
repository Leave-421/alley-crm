# Code Quality

- 현재 codebase의 패턴을 먼저 따른다.
- 중복을 제거한다는 이유만으로 성급한 추상화를 만들지 않는다. 구현이 하나뿐인 인터페이스 · 팩토리를 만들지 않는다.
- 함수/컴포넌트는 하나의 주된 책임을 갖도록 한다.
- 이름은 구현 방식보다 역할과 의미를 표현한다.
- 불필요한 상태를 만들지 않는다. 파생 가능한 값은 저장 상태로 중복 관리하지 않는다.
- error를 catch한 뒤 무시하지 않는다.
- 외부 데이터의 null/undefined/invalid 가능성을 고려한다.
- magic value가 도메인 의미를 가진다면 상수/enum으로 표현한다. 설정값(수수료율 · 소개 인정 기간 · 보관 기간 등)은 한 곳에서 읽는다.
- 기존 public contract를 바꾸면 호출부 전체를 확인한다.
- 생성 파일을 사람이 수동 편집하지 않는다(lockfile, 이미 적용된 Flyway 마이그레이션 포함).
- 이슈 범위를 넘는 대규모 리팩터링 · 파일 이동을 하지 않는다(`scope-control.md`).

## 백엔드 (`backend/` — Java 21 · Spring Boot · Spring MVC · Spring Data JPA · Spring Security 세션 · MySQL 8.4 · Flyway)

> 세부 버전 · 패키지 구성은 기획 확정 뒤 채운다. TODO

- **Controller → Service → Repository.** Controller는 요청 매핑 · 요청 DTO 검증 · 인가 검사 · 응답 DTO 반환만 한다. 규칙과 트랜잭션(`@Transactional`)은 Service 공개 메서드에, 저장은 Spring Data JPA Repository에 둔다.
- **요청 DTO와 응답 DTO를 나누고, Entity를 응답으로 바로 내보내지 않는다.** DTO 필드 · 경로 · enum 값은 `docs/CONTRACTS_API.md` 그대로 쓴다.
- 패키지 사이는 그 패키지의 Service로만 부르고 다른 패키지의 Repository를 직접 쓰지 않는다.
- 가게 단위 데이터는 항상 가게(·골목) 조건으로 조회 · 저장한다. 쿼리는 매개변수 바인딩만 쓴다.
- 스키마는 Flyway 마이그레이션(`backend/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql` — 여러 사람이 동시에 만들어도 겹치지 않게 타임스탬프 버전)으로만 바꾼다. `ddl-auto`로 테이블을 만들지 않는다.
- 알림(SSE 등)은 트랜잭션 커밋 뒤에 보낸다.
- 시각은 UTC로 저장 · 응답한다(`Z`로 끝나는 ISO 8601). API의 내부 ID는 문자열이다(Entity는 `Long`).
- **에러 응답**: `{ "error": { "code": "...", "message": "..." } }`. code는 소문자 snake. 새 code는 계약 변경이다. 전역 예외 처리 한 곳에서 이 모양으로 바꾼다.

## 프론트엔드 (`frontend/`)

> React + JavaScript (Vite). TypeScript 전환은 미결이라, 전환해도 설정만 바꾸면 되게 둔다. 개발 서버는 Vite 프록시로 API를 같은 출처로 붙인다.

- API 요청은 상대 경로 `/api/**`. 화면 코드 곳곳에서 `fetch`를 직접 부르지 말고 API 호출 모듈 한 곳을 거친다.
- 새 라이브러리 · 상태 관리 도구는 팀 합의 뒤 들인다.
