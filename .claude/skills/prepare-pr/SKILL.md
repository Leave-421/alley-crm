---
name: prepare-pr
description: 현재 branch의 변경을 리뷰 가능한 GitHub Pull Request로 정리할 때 사용한다.
disable-model-invocation: true
---

# Prepare PR

PR을 만들기 전에:

1. `git status`
2. base가 `main`인지, 브랜치 이름이 `<type>/<이슈번호>-<설명>`인지 확인
3. `git diff origin/main...HEAD` 기준 전체 변경 검토
4. unrelated change 제거
5. debug log / 임시 mock / TODO / secret 확인
6. 가능한 검증 수행 — 루트 `README.md` 「빌드 · 테스트 명령」(백엔드 테스트는 MySQL 필요)
7. Issue 완료 조건과 대조
8. 「관련 요구사항」에 이슈의 요구사항 ID와 같은 ID를 적었는지 확인
9. API 계약을 바꿨다면 `docs/CONTRACTS*.md`가 같은 PR에서 함께 바뀌었는지 확인
10. 「고민한 점」이 비어 있으면 작성자에게 묻는다 — 지어내서 채우지 않는다

PR 제목:

```text
<type>(<scope>)?: <설명 50자 이내>
```

PR body는 `.github/PULL_REQUEST_TEMPLATE.md`를 채운다. 검증하지 않은 것을 했다고 적지 않는다. PR 본문 · 커밋에 Claude · AI 작성자 표기를 넣지 않는다.

사용자가 요청하지 않았다면 `git commit`, `git push`, PR 생성, merge를 자동 실행하지 않는다.
