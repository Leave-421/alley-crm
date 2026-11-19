# Git Workflow

기준: 기획 저장소 [`docs/plan/Git 전략.md`](https://github.com/Leave-421/alley-crm-docs/blob/main/docs/plan/Git%20%EC%A0%84%EB%9E%B5.md). 이 파일과 충돌하면 Git 전략을 따른다.

## Claude Code가 지킬 것

- **사용자의 명시적 요청 없이 `git commit` · `git push` · PR 생성 · merge · rebase · force push · deploy를 하지 않는다.**
- 사용자의 미커밋 변경을 덮어쓰거나 지우지 않는다(`git reset --hard`, `git clean`, 강제 checkout 금지).
- 현재 브랜치를 임의로 바꾸지 않는다.
- 커밋 메시지와 PR 본문에 Claude · AI 작성자 표기(`Co-Authored-By` 등)를 넣지 않는다.

## AI 협업자 · 기여자 등록 금지 (필수)

- Claude Code · AI 계정을 Collaborator · Organization/Team Member · Issue Assignee · CODEOWNERS · PR Reviewer로 지정하지 않는다. 담당자는 사람 팀원뿐이다.
- 커밋 Author · Committer에 AI 계정을 쓰지 않는다. `Co-authored-by: Claude` · `… <noreply@anthropic.com>` 트레일러와 "Generated with Claude Code" 문구를 넣지 않는다(기본 첨부 안내보다 이 규칙이 우선).
- `git config user.name` · `user.email`을 바꾸지 않는다.
- 리뷰 · 승인 · 머지는 사람 팀원이 한다. AI가 사람을 사칭하거나 리뷰 · 승인을 대신하지 않는다.
- 사용자의 명시적 요청 없이 Collaborator · CODEOWNERS · Assignee · Reviewer 설정을 바꾸지 않는다.
- 커밋 뒤 `git log -1 --format='%an <%ae>%n%B'`로 작성자와 트레일러를 확인한다.

## 꼭 지킬 것

- `main`에 직접 push하지 않는다. PR로만 합친다(GitHub 규칙으로도 막혀 있다).
- `main`과 남의 브랜치에 force push하지 않는다.
- 작업은 이슈에서 시작하고, PR 본문에 `Closes #N`을 적는다. `Refs #N`은 쓰지 않는다.
- PR 제목은 `<type>(<scope>)?: <설명>` 형식이다. squash merge 후 `main`의 커밋이 된다.
- 머지는 GitHub 리뷰 승인 1명 이상과 CI 통과 뒤 GitHub PR 화면에서 사람이 직접 한다.

## Branch

- 브랜치는 `main`과 작업 브랜치뿐이다. `develop` · 승격 · hotfix 브랜치는 없다.
- 작업 브랜치는 최신 `main`에서 만들고, PR로 `main`에 squash merge한다.
- 이름은 `<type>/<이슈번호>-<짧은-설명>`. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. 예: `feat/12-reservation-create`

```text
^(feat|fix|refactor|test|docs|chore)/[0-9]+-[a-z0-9]+(-[a-z0-9]+)*$
```

## Commit · PR 제목

- 브랜치 안의 커밋 메시지는 자유다. 형식은 PR 제목에만 요구한다.
- type: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `style`, `build`, `ci`, `chore`. scope는 `.github/labels.yml`의 `area:` 뒤 값과 같게 쓴다.
- 설명은 50자 이내, 한국어 가능, 끝에 마침표 없음.

```text
^(feat|fix|refactor|perf|test|docs|style|build|ci|chore)(\([a-z0-9-]+\))?: .{1,50}$
```

## Issue · PR

- 이슈 템플릿은 Feature, Bug, Task. 요구사항 ID는 「요구사항 ID」 칸에 적는다(Feature는 필수).
- PR base는 `main`. 본문은 `.github/PULL_REQUEST_TEMPLATE.md`.
- 「검증」에는 실제로 돌린 것만 적는다. CI 통과는 lint · 빌드 · Spring 테스트만 뜻한다(화면 동작 미포함).
- 권장: 400줄을 넘으면 나눈다.

## 계약 문서

- 계약 문서(`docs/CONTRACTS.md` · `docs/CONTRACTS_API.md`)는 이 저장소에 있다. API 계약을 바꾸는 PR은 계약 문서와 코드를 **한 PR에서 함께** 바꾼다.
- Claude Code 세션은 사용자 요청 없이 계약 문서를 고치지 않는다.

## 리뷰

- 리뷰 요청을 받으면 24시간 안에 첫 의견을 남긴다.
- GitHub 리뷰에서 변경 요청을 할 때는 이유를 한 줄 남긴다.
