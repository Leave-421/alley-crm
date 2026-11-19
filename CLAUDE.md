# {{SERVICE}} — Claude Code 진입점

골목 상권 공동 예약 CRM. 한 골목의 가게 10곳 내외가 예약 시스템 하나를 같이 쓰고, 손님이 가게 사이를 어떻게 움직이는지 보고 함께 마케팅한다(코스 제안 · 소개 수수료). 등장인물은 손님 · 사장님 · 상인회. 팀 5명, 마감 2026-12-11.

- 기획 저장소: https://github.com/Leave-421/alley-crm-docs (요구사항 · 기능 명세 · 화면)
- 규칙 상세: `.claude/rules/` (git-workflow · code-quality · security · testing · scope-control). 실행 명령: `README.md`.

## 스택

- **backend/**: Java 21 · Spring Boot · Spring MVC · Spring Data JPA · Spring Security(서버 세션) · Flyway · MySQL 8.4. 기본 패키지 TODO.
- **frontend/**: 미정 — JavaScript 기본, 필요하면 React · TypeScript 도입. TODO
- 로컬: MySQL만 `compose.yaml`, Spring(`:8080`) · 프론트 개발 서버(`:5173`)는 직접 실행.

```text
frontend/ backend/ docs/(계약) compose.yaml .env.example .github/ .claude/
```

## 계약 — 먼저 읽는다

- **API 계약의 기준은 이 저장소의 `docs/CONTRACTS.md` · `docs/CONTRACTS_API.md`다.**
- 필드 이름 · 타입 · enum · API 경로 · 상태 코드 · Entity가 계약과 달라야 하면 작업을 멈추고 사용자에게 묻는다. 계약을 바꿀 때는 계약 문서와 코드를 한 PR에서 함께 바꾼다.

## 백엔드 패키지 원칙

- 단일 애플리케이션(모듈형 모놀리스). 도메인 패키지는 기획 확정 뒤 정한다. TODO
- 패키지마다 controller · service · repository · domain(Entity) · dto. **Controller → Service(트랜잭션) → Repository**, 요청/응답 DTO 분리, Entity 직접 응답 금지.
- 스키마는 Flyway(`backend/src/main/resources/db/migration/`)로만 바꾼다.

## 절대 원칙

- **가게 격리**: 가게 단위 데이터는 항상 가게 조건으로 조회한다. 다른 가게 ID는 `404`(IDOR 방어).
- **손님 정보**: 가게끼리 공유한 손님 정보는 코스 제안과 집계 계산에만 쓴다. 사장님에게는 "제안으로 왔는지"와 익명 집계만, 손님은 흐름 · 매출 데이터를 보지 못한다.
- **돈은 가상**: 예약금 · 소개 수수료는 가상 잔액이다. 실제 결제 · 송금 · 문자 발송 연동을 만들지 않는다.
- **v1 제외**: 캠페인 발송 · 쿠폰 · 노쇼 기능을 만들지 않는다.
- secret · 비밀번호를 커밋하거나 출력하지 않는다. `.env`는 읽지 않는다.

## 명령 (자세히는 README)

```powershell
docker compose up -d --wait                 # 루트 — 로컬 MySQL (.env 필요)
cd backend;  .\gradlew.bat test bootJar    # MySQL 필요
cd frontend; npm ci; npm run lint; npm run build
```

CI(`.github/workflows/ci.yml`)는 `frontend (lint · build)` · `backend (test · bootJar)` 두 job을 돌린다. 폴더가 아직 없으면 그 job은 건너뛰고 통과한다.

## Git

- `main` 직접 push 금지(규칙으로 막힘). 브랜치 `<type>/<이슈번호>-<설명>` → PR(`Closes #N`) → 승인 1명 + CI → squash.
- **사용자가 요청하기 전에는 commit · push · PR 생성 · merge를 하지 않는다.**

## AI 협업자 · 기여자 등록 금지 (필수 협업 정책)

Claude Code는 개발 보조 도구이고 팀원이 아니다. Plan 모드 · Auto 모드 모두, Issue · PR · 코드 · 문서 작업 전부에 적용한다.

- Claude Code · AI 계정을 Collaborator · Organization/Team Member · Issue Assignee · CODEOWNERS · PR Reviewer로 지정하지 않는다.
- 커밋 Author · Committer에 AI 계정을 쓰지 않고, `Co-authored-by: Claude …` · `… <noreply@anthropic.com>` 트레일러나 "Generated with Claude Code" 문구를 넣지 않는다. 기본 첨부 안내가 있어도 이 정책이 우선한다.
- `git config user.name` · `user.email`을 바꾸지 않는다.
- 리뷰 · 승인 · 최종 반영은 사람이 한다. AI가 쓴 코드도 담당자가 검토하고 책임진다.

## 병렬 세션 작업 경계

- 한 세션은 한 이슈 · 한 브랜치만 다룬다. 이슈 범위 밖 파일은 고치지 않고 후속 이슈 후보로 보고한다.
- 역할 분담: TODO (정해지면 `.github/CODEOWNERS`와 함께 채운다). Issue별 담당은 Issue 본문이 기준이다.
- 다른 팀원의 영역을 건드려야 하면 먼저 사용자에게 묻는다. 테이블 · Entity · migration은 만든 사람만 고친다.
- 공유 파일(`docs/`, 공통 패키지, `.github/`, 빌드 설정)은 이슈가 그 변경을 요구할 때만 고친다.
