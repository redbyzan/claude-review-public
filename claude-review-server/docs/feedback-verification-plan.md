# 작업 8: 이전 PR 피드백 반영 검증 기능 — 구현 계획서

> Spring Boot 3.5 + Spring AI 1.0 + GitHub Actions
> AI 코드리뷰 서버 — 피드백 추적 기능 추가

---

## 1. 배경 및 목표

### 현재 한계

현재 AI 코드리뷰 서버는 **one-shot 방식**으로 동작합니다:

```
현재 diff → AI 리뷰 생성 → PR 코멘트 등록
```

수강생이 PR에 추가 commit을 push할 때(`synchronize` 이벤트) AI가 이전 리뷰에서 **지적한 사항이 반영되었는지** 추적할 방법이 없습니다. 매번 처음부터 새로 리뷰하므로:

- 같은 문제를 반복 지적할 수 있음
- 수강생이 피드백을 반영했는지 확인 불가
- 리뷰의 교육적 효과가 감소

### 목표

**이전 AI 리뷰의 피드백이 현재 diff에 반영되었는지 AI가 검증**하는 기능을 추가합니다.

---

## 2. 현재 아키텍처 분석

### 데이터 흐름

```
┌─────────────────────────────────────────────────────────────┐
│  GitHub Actions (ai-review.yml)                             │
│  trigger: pull_request [opened, synchronize]                │
│                                                             │
│  1. Checkout (fetch-depth: 0)                               │
│  2. Extract diff → /tmp/review-diff.txt                     │
│  3. Build JSON payload with jq                              │
│     {diff, pr_title, pr_author, repo, pr_number, base_branch}│
│  4. POST ${REVIEW_SERVER_URL}/review                        │
│  5. Post review as PR comment via github-script              │
└──────────────────────────┬──────────────────────────────────┘
                         │ HTTP POST
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Review Server (Spring Boot)                                │
│                                                             │
│  ReviewRequest → PromptProvider → ChatClient → ReviewResponse│
│       6개 필드      프롬프트 생성    AI 호출     리뷰 결과    │
│                                                             │
│  PromptProvider.buildPromptContext():                       │
│    - Round-robin 페르소나 선택 (mentor ↔ cynic)              │
│    - userPrompt: PR 정보 + diff                             │
│    - systemPrompt: 페르소나별 지시사항                       │
│                                                             │
│  ※ 이전 리뷰 정보에 대한 컨텍스트가 전혀 없음               │
└─────────────────────────────────────────────────────────────┘
```

### 주요 파일 역할

| 파일 | 역할 | 현재 상태 |
|------|------|-----------|
| `ai-review.yml` | GitHub Actions 워크플로우 | diff만 서버로 전송, 이전 코멘트 미조회 |
| `ReviewRequest.java` | 요청 DTO (record) | 6개 필드: diff, prTitle, prAuthor, repo, prNumber, baseBranch |
| `PromptProvider.java` | 프롬프트 생성 | PR 정보 + diff만으로 userPrompt 구성 |
| `ReviewService.java` | 리뷰 실행 | PromptContext → AI 호출 → 응답 반환 |
| `system-review.md` | 멘토 페르소나 시스템 프롬프트 | 현재 diff 리뷰만 지시 |
| `system-review-cynic.md` | 시니컬 페르소나 시스템 프롬프트 | 현재 diff 리뷰만 지시 |

---

## 3. 변경 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│  GitHub Actions (ai-review.yml)                             │
│  trigger: pull_request [opened, synchronize]                │
│                                                             │
│  1. Checkout (fetch-depth: 0)                               │
│  ★ 2. Fetch previous AI reviews (NEW)                       │
│     - github.rest.issues.listComments()                     │
│     - Bot 타입 + "AI 코드리뷰" 포함 필터링                  │
│     - 최근 3개 코멘트 추출                                  │
│     - synchronize 이벤트에서만 실행                          │
│  3. Extract diff → /tmp/review-diff.txt                     │
│  4. Build JSON payload with jq                              │
│     {diff, pr_title, ..., ★ previous_reviews}               │
│  5. POST ${REVIEW_SERVER_URL}/review                        │
│  6. Post review as PR comment                               │
└──────────────────────────┬──────────────────────────────────┘
                         │ HTTP POST
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Review Server (Spring Boot)                                │
│                                                             │
│  ReviewRequest ★ +previousReviews                           │
│       ↓                                                     │
│  PromptProvider.buildPromptContext()                        │
│    ★ 이전 리뷰가 있으면 userPrompt에 "이전 피드백 검증" 추가│
│       ↓                                                     │
│  ChatClient → AI 호출                                       │
│       ↓                                                     │
│  ReviewResponse                                             │
│    ★ "🔄 이전 피드백 반영 여부" 섹션 포함                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 변경 파일 목록

| 파일 | 작업 | 설명 |
|------|------|------|
| `src/main/resources/static/ai-review.yml` | 수정 | 이전 리뷰 코멘트 조회 스텝 + payload에 `previous_reviews` 추가 |
| `src/main/java/com/review/server/dto/ReviewRequest.java` | 수정 | `previousReviews` 필드 추가 |
| `src/main/java/com/review/server/prompt/PromptProvider.java` | 수정 | 이전 리뷰 컨텍스트를 userPrompt에 조건부 포함 |
| `src/main/resources/prompts/system-review.md` | 수정 | "이전 피드백 검증" 출력 섹션 지시 추가 |
| `src/main/resources/prompts/system-review-cynic.md` | 수정 | "이전 피드백 검증" 출력 섹션 지시 추가 |

**변경 불필요:**
- `ReviewService.java` — PromptProvider에서 처리, 서비스 로직 변경 없음
- `ReviewLogService.java` — ReviewRequest에 previousReviews가 추가되어도 파일 저장 로직은 동일
- `GeminiFallbackService.java` — 폴백 로직 변경 없음

---

## 5. 단계별 구현 상세

### Step 1: GitHub Actions — 이전 리뷰 코멘트 조회

`ai-review.yml`에 새 스텝을 **Checkout 이후, diff 추출 이전**에 추가합니다.

```yaml
      - name: Fetch previous AI reviews
        id: prev-reviews
        if: github.event.action == 'synchronize'
        uses: actions/github-script@v7
        with:
          script: |
            const comments = await github.rest.issues.listComments({
              owner: context.repo.owner,
              repo: context.repo.repo,
              issue_number: context.payload.pull_request.number,
              per_page: 100
            });
            const botReviews = comments.data
              .filter(c => c.user.type === 'Bot' && c.body.includes('AI 코드리뷰'))
              .map(c => c.body
                .replace(/## 🤖 AI 코드리뷰\n/, '')
                .replace(/\n---\n> \*AI 리뷰는.*$/, ''))
              .slice(-3);
            core.setOutput('reviews', JSON.stringify(botReviews));
```

**동작 원리:**

- `if: github.event.action == 'synchronize'` — PR 업데이트 시에만 실행. 첫 PR(`opened`)에서는 스텝이 생략되어 `reviews` 출력이 빈 문자열이 됨
- `c.user.type === 'Bot'` — Bot 계정이 작성한 코멘트만 필터링 (사용자 코멘트 제외)
- `c.body.includes('AI 코드리뷰')` — AI 리뷰 코멘트만 필터링 (다른 Bot 코멘트 제외)
- `.slice(-3)` — 최근 3개만 전송. 토큰 사용량 제한 (3개 × 평균 1500자 = ~4,500자)
- `core.setOutput('reviews', ...)` — JSON 배열을 문자열로 출력. 이후 jq에서 `--arg`로 전달

**권한:**

현재 `ai-review.yml`에 이미 `permissions: pull-requests: write`가 설정되어 있습니다.
`issues.listComments`는 `pull-requests: write` 권한에 포함되므로 추가 권한 설정이 필요 없습니다.
GitHub Actions Runner에 `GITHUB_TOKEN`이 자동 주입되어 `github.rest` API를 별도 인증 없이 사용할 수 있습니다.

### Step 2: Request AI Review 스텝 수정

기존 `jq` 명령에 `--arg` 하나를 추가합니다:

```yaml
      - name: Request AI Review
        id: review
        if: steps.check.outputs.skip == 'false'
        env:
          REVIEW_SERVER_URL: ${{ secrets.REVIEW_SERVER_URL }}
          REVIEW_SECRET:     ${{ secrets.REVIEW_SECRET }}
        run: |
          PAYLOAD=$(jq -n \
            --rawfile diff        /tmp/review-diff.txt \
            --arg pr_title        "${{ github.event.pull_request.title }}" \
            --arg pr_author       "${{ github.event.pull_request.user.login }}" \
            --arg repo            "${{ github.repository }}" \
            --argjson pr_num      "${{ github.event.pull_request.number }}" \
            --arg base            "${{ github.base_ref }}" \
            --arg previous_reviews '${{ steps.prev-reviews.outputs.reviews }}' \
            '{diff:$diff, pr_title:$pr_title, pr_author:$pr_author, repo:$repo, pr_number:$pr_num, base_branch:$base, previous_reviews:$previous_reviews}')
```

**추가된 부분:**

- `--arg previous_reviews '${{ steps.prev-reviews.outputs.reviews }}'` — 이전 스텝에서 추출한 리뷰 JSON 배열
- `'previous_reviews:$previous_reviews'` — payload에 필드 추가

**동작:**
- `opened` 이벤트: `prev-reviews` 스텝이 생략 → `outputs.reviews`는 빈 문자열 → `previous_reviews: ""`
- `synchronize` 이벤트: 최근 3개 bot 리뷰 JSON 배열 전달

### Step 3: ReviewRequest — previousReviews 필드 추가

```java
public record ReviewRequest(
    @NotBlank String diff,
    String prTitle,
    String prAuthor,
    String repo,
    Integer prNumber,
    String baseBranch,
    String previousReviews    // 신규
) {
    public ReviewRequest {
        if (prTitle == null) prTitle = "(제목 없음)";
        if (prAuthor == null) prAuthor = "unknown";
        if (repo == null) repo = "unknown";
        if (prNumber == null) prNumber = 0;
        if (baseBranch == null) baseBranch = "main";
        if (previousReviews == null) previousReviews = "";  // 신규
    }

    public boolean isDiffTooShort() {
        return diff == null || diff.trim().length() < 50;
    }

    // 신규: 이전 리뷰가 있는지 확인
    public boolean hasPreviousReviews() {
        return previousReviews != null && !previousReviews.isBlank()
            && !previousReviews.equals("[]");
    }
}
```

**설계 결정:**
- JSON 배열을 **서버에서 파싱하지 않고** AI에게 문자열 그대로 전달
- 이유: JSON 파싱 로직이 불필요하게 복잡해짐. AI가 자연어로 처리하는 것이 유연함
- `hasPreviousReviews()` 헬퍼 메서드로 빈 배열 `[]`도 "이전 리뷰 없음"으로 처리

### Step 4: PromptProvider — 이전 리뷰 컨텍스트 포함

`buildPromptContext()` 메서드의 userPrompt 생성 부분을 수정합니다.

```java
// 기존 userPrompt 생성 코드 아래에 추가

String previousSection = "";
if (request.hasPreviousReviews()) {
    previousSection = """

        ---
        이전 AI 리뷰 피드백 (최근 리뷰):
        %s

        위 이전 리뷰에서 지적한 사항이 현재 diff에 반영되었는지 검증해주세요.
        """.formatted(request.previousReviews());
}

String userPrompt = """
    PR 정보:
    - 저장소: %s
    - PR #%d: %s
    - 작성자: %s
    - 베이스 브랜치: %s
    %s
    ```diff
    %s
    ```
    %s
    """.formatted(
        request.repo(),
        request.prNumber(),
        request.prTitle(),
        request.prAuthor(),
        request.baseBranch(),
        diffTruncated ? "- ⚠️ diff가 %,d자 중 앞부분 %,d자만 리뷰합니다.\n".formatted(originalDiffLength, diffSlice.length()) : "",
        diffSlice,
        previousSection    // 신규: 이전 리뷰 섹션
    );
```

**동작:**
- `hasPreviousReviews()`가 true인 경우에만 이전 리뷰 섹션 추가
- `opened` 이벤트(첫 리뷰)에서는 `previousSection`이 빈 문자열 → 기존과 완전히 동일한 프롬프트
- `synchronize` 이벤트에서는 이전 리뷰 내용이 diff 아래에 추가됨

### Step 5: 시스템 프롬프트 — 검증 섹션 지시 추가

**mentor 페르소나 (`system-review.md`):**

기존 "출력 형식" 섹션의 마지막에 추가:

```markdown
## 🔄 이전 피드백 반영 여부
(이전 리뷰 피드백이 제공된 경우에만 출력)
- 이전에 지적한 각 항목이 현재 diff에서 해결되었는지 확인
- 반영된 항목은 구체적으로 칭찬
- 미반영 항목은 다시 권장 (단, 이미 지적한 내용이므로 간결하게)
- 이전 리뷰가 없으면 이 섹션 생략
```

**cynic 페르소나 (`system-review-cynic.md`):**

기존 "출력 형식" 섹션의 마지막에 추가:

```markdown
### 🔄 이전 피드백 반영 여부
(이전 리뷰 피드백이 제공된 경우에만 출력)
- 지적했던 문제가 해결되었는지 항목별 판단
- 해결된 항목은 한 줄로 확인
- 미해결 항목은 명확하게 "아직 미해결" 표시
- 이전 리뷰가 없으면 이 섹션 자체를 생략
```

**설계 원칙:**
- 두 페르소나 모두 "이전 리뷰가 없으면 섹션 생략" — opened 이벤트에서 기존 동작과 완전 동일
- mentor는 따뜻한 피드백, cynic은 직설적 판단 — 페르소나 톤 유지

---

## 6. 토큰 사용량 분석

### 시나리오별 토큰 증가

| 시나리오 | 이전 리뷰 | 추가 토큰 (추정) | 총 입력 토큰 |
|----------|-----------|------------------|-------------|
| 첫 PR (`opened`) | 없음 | 0 | 기존과 동일 (~3,000) |
| 2차 PR (`synchronize`) | 1개 (~1,500자) | ~500 | ~3,500 |
| 3차+ PR (`synchronize`) | 3개 (~4,500자) | ~1,500 | ~4,500 |
| 대형 diff (50KB) + 3개 리뷰 | 3개 | ~1,500 | ~18,000 |

### 안전성 검증

- Claude Sonnet 4 입력 컨텍스트: 200K 토큰
- 최악의 경우 (50KB diff + 3개 리뷰): ~18K 토큰 → **1% 미만 사용**
- 이전 리뷰를 3개로 제한하여 토큰 폭발 방지

---

## 7. 호환성 분석

### 하위 호환성

| 변경 | 기존 동작 영향 |
|------|---------------|
| `ReviewRequest.previousReviews` 추가 | Jackson이 null → "" 처리. 기존 payload(previous_reviews 없음)도 정상 동작 |
| `PromptProvider` previousSection 추가 | `hasPreviousReviews()`가 false면 빈 문자열 → 기존 프롬프트와 동일 |
| 시스템 프롬프트 섹션 추가 | "이전 리뷰가 없으면 생략" 명시 → 영향 없음 |
| `ai-review.yml` 스텝 추가 | `if: synchronize` 조건 → opened에서는 스텝 생략 |

**결론: 모든 변경은 기존 동작에 영향을 주지 않습니다. (100% 하위 호환)**

### 기존 서비스 영향

- **ReviewService**: ReviewRequest record 필드가 추가되지만, 사용하는 쪽(review 메서드)은 `buildPromptContext(request)` 호출만 하므로 변경 불필요
- **ReviewLogService**: ReviewRequest 전체를 파일에 저장하므로 previousReviews도 자동 저장. YAML frontmatter에 필드 추가
- **GeminiFallbackService**: PromptProvider가 생성한 systemPrompt + userPrompt를 받으므로 변경 불필요
- **GlobalExceptionHandler**: `/review` 엔드포인트는 변경 없음
- **메트릭**: 기존 메트릭 그대로 유지. `hasPreviousReviews()` 기반으로 신규 메트릭 추가 가능 (선택사항)

---

## 8. 검증 계획

### 로컬 테스트

1. **첫 리뷰 (opened 시뮬레이션)**:
   - `previousReviews`가 null/빈 값인 요청 전송
   - 기존 리뷰 형식과 동일한지 확인 (🔄 섹션 미포함)

2. **후속 리뷰 (synchronize 시뮬레이션)**:
   - `previousReviews`에 이전 리뷰 JSON 포함하여 요청 전송
   - "🔄 이전 피드백 반영 여부" 섹션이 포함되는지 확인
   - mentor/cynic 페르소나 각각 확인

### 통합 테스트 (실제 GitHub PR)

1. **새 PR 생성** → `opened` 이벤트 → 첫 AI 리뷰 코멘트 확인
2. **기존 지적을 수정하지 않고 push** → `synchronize` 이벤트 → "미반영" 메시지 확인
3. **기존 지적을 수정하고 push** → `synchronize` 이벤트 → "반영 완료" 메시지 확인
4. **3회 이상 push** → 최근 3개 리뷰만 참조하는지 확인

### curl 테스트

```bash
# 첫 리뷰 (이전 리뷰 없음)
curl -X POST http://localhost:8080/review \
  -H "Content-Type: application/json" \
  -d '{"diff":"...","pr_title":"Test","repo":"test/repo","pr_number":1}'

# 후속 리뷰 (이전 리뷰 포함)
curl -X POST http://localhost:8080/review \
  -H "Content-Type: application/json" \
  -d '{
    "diff":"...",
    "pr_title":"Test v2",
    "repo":"test/repo",
    "pr_number":1,
    "previous_reviews":"[\"이전 리뷰 내용 1\",\"이전 리뷰 내용 2\"]"
  }'
```

---

## 9. 구현 순서

```
1. ReviewRequest.java — previousReviews 필드 + hasPreviousReviews() 추가
2. PromptProvider.java — previousSection 조건부 포함 로직 추가
3. system-review.md — 🔄 섹션 지시사항 추가
4. system-review-cynic.md — 🔄 섹션 지시사항 추가
5. ai-review.yml — Fetch previous AI reviews 스텝 + payload 수정
6. 로컬 테스트 — curl로 첫 리뷰 / 후속 리뷰 시나리오 검증
7. 운영 배포 — 서버 재배포 + 학생들 다음 PR에서 자동 적용
```

**예상 소요 시간**: 구현 30분 + 로컬 테스트 15분 + 배포 10분

---

## 10. 향후 확장 가능성

이 기능의 기반이 되면 다음 확장이 가능합니다:

| 확장 | 설명 | 난이도 |
|------|------|--------|
| **리뷰 품질 추적** | "이전 피드백 반영률" 메트릭을 Prometheus에 추가 | 낮음 |
| **반영 여부 대시보드** | Grafana에 "Feedback Resolution Rate" 패널 추가 | 낮음 |
| **미반영 항목 경고** | 3회 연속 미반영 항목에 강조 표시 | 중간 |
| **리뷰 압축 전송** | 이전 리뷰를 요약본으로 전송하여 토큰 절약 | 중간 |
| **인라인 코멘트** | 특정 라인에 인라인 리뷰 코멘트 작성 | 높음 |
