# {{SERVICE}}

골목 상권 공동 예약 CRM — 개발 저장소. 기획 문서는 [Leave-421/alley-crm-docs](https://github.com/Leave-421/alley-crm-docs).

## 1. 준비물

| 도구 | 버전 |
| --- | --- |
| JDK | 21 (Temurin) |
| Node | `.nvmrc` (24) |
| Docker Desktop | 로컬 MySQL용 |
| Git · GitHub CLI(`gh`) | |

## 2. 처음 한 번

```powershell
git clone https://github.com/Leave-421/alley-crm.git
cd alley-crm
Copy-Item .env.example .env      # 비밀번호를 바꾼다. .env 는 커밋되지 않는다
docker compose up -d --wait      # 로컬 MySQL
```

## 3. 빌드 · 테스트 명령

```powershell
# 백엔드 (테스트는 Docker 필요 — Testcontainers. 실행은 로컬 MySQL)
cd backend
.\gradlew.bat test bootJar
.\gradlew.bat bootRun            # :8080

# 프론트엔드
cd frontend
npm ci
npm run lint
npm run build
npm run dev                      # :5173
```

> 백엔드 · 프론트 뼈대는 아직 없다. 생기면 이 절을 실제 명령으로 고친다.

### 시드 · 시연 데이터 (담당 E)

```powershell
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=seed"                               # 검사만(dry-run), DB 안 바꿈
.\gradlew.bat bootRun --args="--spring.profiles.active=seed --seed.confirm=reset-all-data" # 로컬 DB를 모두 지우고 다시 넣음
```

- 로컬 DB(localhost · 127.0.0.1 · compose `mysql`)만 지운다. 운영 프로필(`prod`)과 함께 켜면 거부한다.
- 직접 만든 계정, 상인회가 바꾼 설정값 · 공유 설정까지 **모든 데이터를 지운다**(마이그레이션 기록은 남김, 설정은 기본값으로 돌아감). 웹 서버(`bootRun`)를 끄고 돌린다.
- 지금 넣는 것: 권역 1개 · 참여 가게 15곳(`backend/src/main/resources/seed/region-stores.json`), 시연 계정 3개. 공공데이터 · 가상 데이터는 각 이슈에서 더한다.
- 시연 계정: 손님 `democustomer` · 사장님 `demoowner`(S01 아침볕 커피) · 상인회 `demoassoc`. 비밀번호는 루트 `.env`의 `DEMO_CUSTOMER_PASSWORD` · `DEMO_OWNER_PASSWORD` · `DEMO_ASSOCIATION_PASSWORD`로 정한다(비어 있으면 그 계정은 건너뜀).

## 4. 작업 흐름

1. 이슈를 만든다(Feature · Bug · Task 템플릿). 담당자를 지정한다.
2. 최신 `main`에서 브랜치: `<type>/<이슈번호>-<설명>` (예: `feat/12-reservation-create`)
3. PR 제목: `<type>(<scope>)?: <설명 50자 이내>`, 본문에 `Closes #N`
4. 승인 1명 + CI 통과 → PR 화면에서 **Squash and merge**. 브랜치는 자동 삭제된다.

자세한 규칙: 기획 저장소 `docs/plan/Git 전략.md`

## 5. 저장소 설정 (관리자 참고)

`setup.ps1`로 아래를 적용했다. 바꾸면 이 표도 고친다.

| 설정 | 값 |
| --- | --- |
| `main` 규칙(ruleset `main-protection`) | PR 필수 · 승인 1명 · force push 금지 · 삭제 금지 · 관리자 예외 없음 |
| 필수 status check | `frontend (lint · build)` · `backend (test · bootJar)` |
| 머지 방식 | squash만, 머지 후 브랜치 자동 삭제 |
| 라벨 | `.github/labels.yml` (기본 라벨은 `bug`만 남김) |
| Code Owners 리뷰 필수 | 끔 (CODEOWNERS는 리뷰어 자동 지정용) |
| CodeRabbit | 자동 리뷰 끔, 필요할 때 PR 코멘트 `@coderabbitai review` |

## 6. AI 도구 사용 정책

Claude Code 등 AI 도구는 팀원이 아니다. Collaborator · Reviewer · CODEOWNERS · 커밋 작성자 · `Co-authored-by`에 올리지 않는다. 리뷰 · 승인 · 머지는 사람이 한다(`CLAUDE.md`).
