# PR 코멘트 대화형 응답 기능 — 작업계획서

> 작성일: 2026-02-17  
> 프로젝트: claude-review-server  
> 대상 경로: `~/Documents/sparta/spring-3nd/special-lecture/AI-NATIVE/claude-review/`

---

## 0. 확정 의사결정 (2026-02-17)

1. 트리거: **A+B** (`issue_comment` + `pull_request_review_comment`)
2. 컨텍스트: **해당 파일 중심** (라인 코멘트는 해당 파일 diff 우선)
3. 대화 정책: **연속 대화 허용** (히스토리 최대 10턴)
4. 배포 방식: **바로 운영 반영**
5. 멘션 트리거: `@sparta` (**정확 일치 only**)
6. GitHub 인증/코멘트 등록: **기존 `GITHUB_TOKEN` 유지 (Actions가 등록)**
   - 별도 GitHub Bot 계정/토큰 미사용
   - 서버는 응답 생성만 담당, 코멘트 등록은 Actions에서 수행
7. 필터링 정책(최종):
   - `@sparta` 멘션 + 멘션 외 텍스트가 있으면 **응답**
   - 멘션만 있고 본문이 없으면 **스킵**

---

## 멘션 트리거 정책 (확정)

- 트리거 문자열은 **정확히 `@sparta`만 허용**합니다.
- 대소문자/별칭/유사 문자열은 허용하지 않습니다.
- `@sparta` 외 텍스트가 함께 있을 때만 응답합니다.
- `@sparta`만 단독으로 있으면 스킵합니다.

---

## 1. 기능 개요

코드리뷰봇이 작성한 PR 리뷰 코멘트에 개발자가 멘션(`@sparta` 또는 설정된 봇 계정)과 함께 질문/코멘트를 남기면, 봇이 후속 답변을 PR 코멘트로 등록하는 기능.

### 기본 흐름

```
개발자가 PR 코멘트에 @sparta 멘션 + 질문
    ↓
GitHub Webhook (issue_comment / pull_request_review_comment)
    ↓
POST /review/comment (클라이언트가 설정한 webhook URL)
    ↓
Actions: 멘션 여부 확인 → 서버로 질문/컨텍스트 전달
    ↓
서버: Claude 응답 생성(JSON 반환)
    ↓
Actions: PR 코멘트로 답변 등록 (GITHUB_TOKEN)
```

---

## 2. 트리거 방식 (A+B)

### 2-1. `issue_comment` webhook (PR 전체 질문)

- **이벤트**: `issue_comment` (created)
- **대상**: PR의 일반 코멘트
- **용도**: "이 PR 전체적으로 어떻게 생각해?", "리뷰에서 지적한 X 부분 더 설명해줘"
- **필터**: 봇 멘션 포함 코멘트만 처리

### 2-2. `pull_request_review_comment` webhook (라인 단위 질문)

- **이벤트**: `pull_request_review_comment` (created)
- **대상**: 리뷰 코멘트에 대한 답글 (특정 코드 라인)
- **용도**: "이 라인에서 왜 NPE 위험이 있다고 했어?", "제안한 리팩토링 예시 코드 보여줘"
- **필터**: 봇 리뷰 코멘트에 대한 답글이거나, 멘션 포함 코멘트

### 2-3. 트리거 공통 필터링 규칙

| 조건 | 처리 |
|------|------|
| 봇 자신이 작성한 코멘트 | 스킵 (무한 루프 방지) |
| 멘션 없는 코멘트 | 스킵 |
| edited/deleted 이벤트 | 스킵 (created만 처리) |
| 이미 답변한 코멘트 | 스킵 (중복 방지) |

---

## 3. 컨텍스트 전략 (해당 파일만)

### 3-1. issue_comment (PR 전체 질문)

```
시스템 프롬프트: 대화형 응답 전용 프롬프트
사용자 프롬프트:
  - PR 메타 정보 (제목, 작성자, 브랜치)
  - 해당 PR의 전체 diff
  - 봇이 이전에 작성한 리뷰 코멘트 전체
  - 개발자의 질문
```

### 3-2. pull_request_review_comment (라인 단위 질문)

```
시스템 프롬프트: 대화형 응답 전용 프롬프트
사용자 프롬프트:
  - PR 메타 정보
  - 해당 파일의 diff만 (path 기반 필터링)
  - 해당 라인 주변의 기존 리뷰 코멘트
  - 개발자의 질문
```

### 3-3. 대화 히스토리 (연속 대화 지원)

- GitHub 코멘트 스레드를 대화 히스토리로 활용
- 같은 PR의 이전 질문-답변 페어를 컨텍스트에 포함
- 최대 10턴 제한 (토큰 비용 관리)
- 히스토리는 GitHub API로 실시간 조회 (별도 DB 불필요)

---

## 4. 상세 설계

### 4-1. 신규/수정 파일 목록

#### 신규 파일

| 파일 | 설명 |
|------|------|
| `dto/CommentWebhookPayload.java` | GitHub webhook 페이로드 DTO |
| `dto/ConversationRequest.java` | 대화형 응답 서비스 내부 요청 DTO |
| `dto/ConversationResponse.java` | 대화형 응답 서비스 내부 응답 DTO |
| `controller/CommentWebhookController.java` | comment webhook 수신 엔드포인트 |
| `service/ConversationService.java` | 대화형 응답 핵심 로직 |
| `service/GitHubApiService.java` | GitHub API 조회 전용 (PR 정보/코멘트/파일 diff 조회) |
| `service/CommentContextBuilder.java` | 컨텍스트 조립 (diff 필터링, 히스토리 수집) |
| `interceptor/WebhookSignatureInterceptor.java` | (선택) webhook 사용 시 GitHub 서명(HMAC-SHA256) 검증 |
| `resources/prompts/system-conversation.md` | 대화형 응답 전용 시스템 프롬프트 |

#### 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `config/WebConfig.java` | `/review/comment` 경로에 WebhookSignatureInterceptor 등록 |
| `interceptor/AuthInterceptor.java` | `/review/comment` 경로 인증 로직 추가 |
| `interceptor/RateLimitInterceptor.java` | comment 엔드포인트 rate limit 별도 적용 |
| `service/ReviewLogService.java` | 대화형 응답 로그 기록 추가 |
| `application.yml` | 멘션 트리거 문자열 및 conversation 설정 추가 |
| `.env` | GITHUB_WEBHOOK_SECRET, GITHUB_BOT_TOKEN 추가 |

### 4-2. 핵심 클래스 설계

#### CommentWebhookController

```java
@RestController
public class CommentWebhookController {

    @PostMapping("/review/comment")
    public ResponseEntity<?> handleCommentWebhook(
            @RequestBody String payload,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-Hub-Signature-256") String signature) {

        // 1. 서명 검증
        // 2. 이벤트 타입 분기 (issue_comment / pull_request_review_comment)
        // 3. 멘션 필터링
        // 4. 봇 자신 코멘트 스킵
        // 5. ConversationService에 위임
        // 6. GitHub 코멘트 등록
        return ResponseEntity.ok().build();
    }
}
```

#### ConversationService

```java
@Service
public class ConversationService {

    public ConversationResponse respond(ConversationRequest request) {
        // 1. PR diff 조회 (GitHub API 또는 캐시)
        // 2. 기존 리뷰 코멘트 + 대화 히스토리 수집
        // 3. 대화형 프롬프트 조립
        // 4. Claude API 호출
        // 5. 응답 후처리 (notice prepend 등)
        // 6. 로그 기록
        // 7. 응답 반환
    }
}
```

#### GitHubApiService

```java
@Service
public class GitHubApiService {

    // PR 정보 조회
    PullRequestInfo getPullRequest(String repo, int prNumber);

    // PR diff 조회
    String getPullRequestDiff(String repo, int prNumber);

    // 특정 파일 diff만 필터링
    String getFileDiff(String fullDiff, String filePath);

    // PR 코멘트 목록 조회 (히스토리용)
    List<CommentInfo> getPullRequestComments(String repo, int prNumber);

    // 리뷰 코멘트 목록 조회
    List<ReviewCommentInfo> getReviewComments(String repo, int prNumber);

    // 코멘트 등록 (일반)
    void createComment(String repo, int prNumber, String body);

    // 리뷰 코멘트에 답글 등록
    void createReplyToReviewComment(String repo, int prNumber,
                                     long commentId, String body);
}
```

### 4-3. API 명세

#### `POST /review/conversation` (Actions 호출)

**요청 헤더**:
- `X-GitHub-Event`: `issue_comment` | `pull_request_review_comment`
- `X-Hub-Signature-256`: `sha256=<HMAC 서명>`
- `Content-Type`: `application/json`

**요청 바디** (issue_comment 예시):
```json
{
  "action": "created",
  "comment": {
    "id": 123456,
    "body": "@sparta 이 부분 왜 NPE 위험이 있다고 했어?",
    "user": { "login": "developer1" }
  },
  "issue": {
    "number": 42,
    "pull_request": { "url": "..." }
  },
  "repository": {
    "full_name": "owner/repo"
  }
}
```

**응답**:
- `200 OK`: 처리 완료 (비동기 응답 후 코멘트 등록)
- `200 OK` (스킵): 멘션 없음, 봇 코멘트 등 사유로 스킵
- `401 Unauthorized`: 서명 불일치
- `404 Not Found`: 엔드포인트 숨김 (기존 정책 유지)

### 4-4. 보안 설계

```
레이어              역항
──────────────────────────────────────────────────────────────────
WebhookSignatureInterceptor   HMAC-SHA256 서명 검증
AuthInterceptor               x-review-secret 또는 webhook secret
멘션 필터                      @sparta (또는 설정값) 포함 시만 처리
봇 코멘트 스킵                 봇 GitHub 계정 ID와 일치하면 스킵
Owner 화이트리스트              기존 ALLOWED_GITHUB_OWNERS 재사용
Rate limit                     IP당 분당 20회 (리뷰보다 여유)
```

### 4-5. 대화형 응답 전용 프롬프트

```markdown
# 파일: resources/prompts/system-conversation.md

당신은 코드리뷰 어시스턴트입니다. 개발자가 코드리뷰에 대한 후속 질문을 했습니다.

## 규칙
- 이전 리뷰 내용과 일관성 있게 답변하세요
- 코드 예시가 필요하면 구체적으로 보여주세요
- 질문이 모호하면 질문의 의도를 먼저 확인한 뒤 답변하세요
- Korean로 답변하세요
- PR의 실제 코드(diff)를 기반으로만 답변하세요 (추측 금지)
- 답변은 간결하고 실용적으로 유지하세요 (max 1500자)
```

### 4-6. 연속 대화 관리

```
대화 히스토리 구조 (Claude에 전달):

[이전 리뷰 봇 코멘트]
"AI 코드리뷰: NPE 위험이 있습니다..."

[개발자 질문 1]
"@sparta 왜 NPE 위험이 있나요?"

[봇 답변 1]
"해당 메서드에서 user 객체가 null일 수 있기 때문입니다..."

[개발자 질문 2]  ← 현재 질문
"@sparta 그럼 Optional 쓰는 게 나을까요?"
```

- 히스토리는 GitHub 코멘트를 실시간 조회하여 구성
- 최대 10턴 (5 페어) 까지 포함
- 초과 시 가장 오래된 것부터 제거

---

## 5. 설정 변경

### application.yml 추가 항목

```yaml
review:
  # ... 기존 설정 ...
  conversation:
    enabled: ${REVIEW_CONVERSATION_ENABLED:true}
    mention-trigger: ${REVIEW_MENTION_TRIGGER:@sparta}
    max-history-turns: ${REVIEW_MAX_HISTORY_TURNS:10}
    max-response-tokens: ${REVIEW_CONVERSATION_MAX_TOKENS:1500}
```

### .env 추가 항목

```bash
# ── 대화형 응답 설정 ────────────────────────────────────────
REVIEW_CONVERSATION_ENABLED=true
REVIEW_MENTION_TRIGGER=@sparta
REVIEW_MAX_HISTORY_TURNS=10
REVIEW_CONVERSATION_MAX_TOKENS=1500
```

---

## 6. GitHub Webhook 설정 변경

### 각 팀 레포에 추가할 Workflow

- 신규 파일: `.github/workflows/ai-review-conversation.yml`
- 트리거: `issue_comment`, `pull_request_review_comment`
- 역할: 멘션 검증(`@sparta`) → 서버 `/review/conversation` 호출 → 응답 코멘트 등록(GITHUB_TOKEN)

> 기존 `ai-review.yml`은 PR 오픈/동기화 리뷰를 계속 담당하고,
> 신규 workflow가 후속 Q&A를 담당합니다.

---

## 7. 모니터링

### 신규 메트릭

| 메트릭 | 타입 | 태그 |
|--------|------|------|
| `review.conversation.requests.total` | Counter | status (success/fail/skip), type (issue_comment/review_comment) |
| `review.conversation.duration` | Timer | type |
| `review.conversation.tokens` | Counter | type (input/output) |
| `review.conversation.history.turns` | Gauge | — |

### Grafana 대시보드 추가 패널

- 대화형 응답 요청 수 (정상/실패/스킵)
- 평균 응답 시간
- 토큰 사용량
- 턴별 분포

---

## 8. 비용 영향 분석

| 항목 | 추정 |
|------|------|
| 대화형 응답 1회 평균 토큰 | ~2,000 (input) + ~1,500 (output) |
| 일일 예상 대화 수 | 10~30회 (초기) |
| 월간 추가 비용 | Claude Sonnet 기준 약 $5~15 |

---

## 9. 테스트 계획

### 9-1. 단위 테스트

| 테스트 | 설명 |
|--------|------|
| `CommentWebhookControllerTest` | webhook 수신, 서명 검증, 필터링 |
| `ConversationServiceTest` | 컨텍스트 조립, Claude 호출, 응답 처리 |
| `GitHubApiServiceTest` | GitHub API 모킹, diff 파싱, 코멘트 등록 |
| `CommentContextBuilderTest` | 파일 diff 필터링, 히스토리 수집 |
| `WebhookSignatureInterceptorTest` | HMAC 서명 검증 |

### 9-2. 통합 테스트

| 테스트 | 설명 |
|--------|------|
| E2E — issue_comment | 실제 webhook 페이로드로 전체 플로우 |
| E2E — review_comment | 라인 단위 질문 전체 플로우 |
| 멘션 필터링 | 멘션 없는 코멘트 스킵 확인 |
| 봇 코멘트 스킵 | 봇 자신 코멘트 무시 확인 |
| 연속 대화 | 3턴 이상 대화 히스토리 유지 확인 |
| 오류 처리 | Claude API 실패 시 fallback 동작 |

---

## 10. 작업 순서 및 일정

### Phase 1: 기반 작업
| 순서 | 작업 | 예상 시간 |
|------|------|----------|
| 1 | DTO 클래스 작성 (CommentWebhookPayload, ConversationRequest/Response) | 30분 |
| 2 | WebhookSignatureInterceptor 구현 | 30분 |
| 3 | GitHubApiService 구현 | 2시간 |
| 4 | application.yml / .env 설정 추가 | 15분 |

### Phase 2: 핵심 로직
| 순서 | 작업 | 예상 시간 |
|------|------|----------|
| 5 | CommentContextBuilder 구현 (diff 필터링, 히스토리 수집) | 1.5시간 |
| 6 | 대화형 프롬프트 (system-conversation.md) 작성 | 30분 |
| 7 | ConversationService 구현 | 2시간 |
| 8 | CommentWebhookController 구현 | 1시간 |

### Phase 3: 연동/보안
| 순서 | 작업 | 예상 시간 |
|------|------|----------|
| 9 | WebConfig, AuthInterceptor 수정 | 30분 |
| 10 | Rate limit 설정 (comment 엔드포인트) | 15분 |
| 11 | ReviewLogService 확장 | 30분 |
| 12 | 모니터링 메트릭 추가 | 30분 |

### Phase 4: 테스트/배포
| 순서 | 작업 | 예상 시간 |
|------|------|----------|
| 13 | 단위 테스트 작성 | 2시간 |
| 14 | 통합 테스트 (ngrok + 테스트 레포) | 1시간 |
| 15 | GitHub webhook 설정 가이드 작성 | 30분 |
| 16 | Docker 재빌드 및 배포 | 30분 |

### 총 예상 시간: **약 13시간**

---

## 11. 롤백 계획

- `REVIEW_CONVERSATION_ENABLED=false` 설정 시 즉시 비활성화
- webhook은 GitHub 레포 설정에서 개별 삭제 가능
- 기존 `/review` 엔드포인트에는 영향 없음 (완전 독립)

---

## 12. 향후 확장 가능성

- **이모지 반응 트리거**: 👀 이모지로 질문 없이 "이 부분 다시 봐줘"
- **슬랙 연동**: PR 대화 내용을 슬랙 채널에도 미러링
- **자동 학습**: 대화 히스토리를 기반으로 프롬프트 개선
- **멀티 모델**: 질문 복잡도에 따라 모델 자동 선택 (Haiku/Sonnet)
