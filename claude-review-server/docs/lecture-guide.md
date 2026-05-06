# AI 코드리뷰 서버 만들기 — 실습 가이드

> Spring Boot 3.5 + Spring AI 1.0 + Java 21
> 부트캠프 특강용 실습 자료

---

## 목차

- [Part A: AI Native 개발 워크샵](#part-a-ai-native-개발-워크샵)
  - [Module 0: 오리엔테이션](#module-0-오리엔테이션-10분)
  - [Module 1: AI와 함께 요구사항 정의](#module-1-ai와-함께-요구사항-정의-20분)
  - [Module 2: AI와 함께 아키텍처 설계](#module-2-ai와-함께-아키텍처-설계-15분)
  - [Module 3: AI에게 프롬프트 엔지니어링 맡기기](#module-3-ai에게-프롬프트-엔지니어링-맡기기-15분)
- [Part B: Spring Boot 실습](#part-b-spring-boot-실습)
  - [Module 4: 환경 설정 + 프로젝트 생성](#module-4-환경-설정--프로젝트-생성-20분)
  - [Module 5: 헬스체크 + Spring AI ChatClient](#module-5-헬스체크--spring-ai-chatclient-30분)
  - [Module 6: DTO + ReviewService](#module-6-dto--reviewservice-25분)
  - [Module 7: 인증 + Rate Limiter 인터셉터](#module-7-인증--rate-limiter-인터셉터-25분)
  - [Module 8: Docker + Nginx 배포](#module-8-docker--nginx-배포-25분)
  - [Module 9: GitHub Actions 연동](#module-9-github-actions-연동-20분)
  - [Module 10: End-to-End 테스트](#module-10-end-to-end-테스트-15분)
  - [Module 11: 회고](#module-11-회고--ai-native-개발-팁-10분)
- [부록: Node.js → Spring Boot 매핑표](#부록-nodejs--spring-boot-매핑표)

---

## 사전 준비물

| 항목 | 확인 방법 |
|------|-----------|
| JDK 21+ | `java --version` |
| Docker | `docker --version` |
| API 키 | Anthropic (`sk-ant-...`) 또는 z.ai 게이트웨이 키 |
| GitHub 계정 | 본인 리포지토리에 PR 생성 가능 |
| 도메인 또는 DDNS | 공인 IP + 포트포워딩 가능 환경 |
| IDE | IntelliJ IDEA 권장 |

---

# Part A: AI Native 개발 워크샵

> 강사가 Claude Code와 라이브로 대화하며 요구사항 → 설계 → 프롬프트까지 만드는 과정을 시연합니다.
> 학생은 **관찰하며 AI Native 개발 방식을 체험**합니다.

---

## Module 0: 오리엔테이션 (10분)

### 목표
- 특강의 최종 산출물을 확인한다
- AI Native 개발이 무엇인지 이해한다

### 최종 산출물 미리보기

강사가 미리 만들어둔 서버에 PR을 날려 AI 리뷰가 달리는 것을 실시간 데모:

```
GitHub PR 생성 → GitHub Actions 트리거 → 리뷰 서버 호출 → Claude API → PR 코멘트 등록
```

### AI Native 개발이란?

전통적 개발: 사람이 직접 요구사항 정리 → 설계 → 구현
AI Native 개발: **AI와 대화하며** 요구사항 정리 → 설계 → 구현

핵심 차이:
- AI가 놓친 요구사항을 질문으로 발견해줌
- AI가 여러 아키텍처 옵션을 제시해줌
- AI가 프롬프트의 베스트 프랙티스를 조사해줌
- **사람은 판단하고 선택한다, AI는 탐색하고 제안한다**

---

## Module 1: AI와 함께 요구사항 정의 (20분)

### 실습: Claude Code 라이브 시연

강사가 Claude Code에 다음과 같이 입력:

```
GitHub PR이 올라오면 자동으로 AI가 코드리뷰를 달아주는 서버를 만들고 싶어.
```

AI가 던지는 질문들을 관찰:

| AI의 질문 | 우리의 답변 | 왜 중요한가 |
|-----------|------------|------------|
| 어떤 언어를 리뷰하나요? | Java/Spring Boot | 리뷰 체크리스트가 달라짐 |
| API 키는 어떤 것을 사용하나요? | Anthropic / z.ai | 게이트웨이 설정 필요 |
| 리뷰 대상은 누구인가요? | 부트캠프 수강생 | 프롬프트 톤이 달라짐 |
| 배포 환경은? | Docker + Nginx | SSL, 보안 헤더 필요 |
| 어떤 보안이 필요한가요? | 시크릿 인증, Owner 화이트리스트 | 미처 생각 못한 부분 |

### 핵심 포인트

> **AI에게 맥락을 주면 좋은 질문을 던져줌 → 요구사항 누락 방지**

혼자서 "보안 어떻게 하지?"를 놓칠 수 있지만, AI가 먼저 물어봐줌.

---

## Module 2: AI와 함께 아키텍처 설계 (15분)

### 실습: Claude Code에게 아키텍처 제안받기

강사가 Claude Code에 입력:

```
Spring Boot + Spring AI로 위 요구사항을 구현하는 아키텍처를 제안해줘.
기존에 Node.js로 구현된 버전이 있는데, 그 코드를 보여줄게.
```

AI가 제안하는 계층 구조:

```
Controller (REST API)
    ↓
Service (비즈니스 로직)
    ↓
PromptProvider (프롬프트 관리)
    ↓
Spring AI ChatClient (API 호출)
```

### Node.js → Spring Boot 매핑 (AI가 정리해줌)

| Node.js | Spring Boot | 비고 |
|---------|-------------|------|
| `app.post("/review", ...)` | `@PostMapping("/review")` | |
| `verifyRequest` 미들웨어 | `HandlerInterceptor` | |
| 수동 재시도 루프 (30줄) | `spring.ai.retry.*` (4줄) | **Spring AI의 큰 장점** |
| `process.env.X` | `@Value("${X}")` | |
| `readFileSync("prompts/")` | `ClassPathResource` | |

### 핵심 포인트

> **기존 코드를 AI에게 보여주면 마이그레이션 맵핑을 해줌**
> 특히 "Node.js 30줄 재시도 코드 → Spring AI 설정 4줄" 같은 인사이트를 바로 발견

---

## Module 3: AI에게 프롬프트 엔지니어링 맡기기 (15분)

### 실습: Claude Code에게 프롬프트 작성하기

강사가 Claude Code에 입력:

```
코드리뷰용 시스템 프롬프트의 베스트 프랙티스를 조사해줘.
```

AI가 조사한 결과:

1. **단계별 분석 지시 (CoT)** — 이해 → 검사 → 분류 → 작성
2. **명시적 체크리스트** — 보안, 오류처리, 성능, 아키텍처...
3. **심각도 분류** — 필수/권장/칭찬
4. **제외 항목** — 포맷팅, 자동생성코드 등은 리뷰하지 않음

### 추가 프롬프트 생성

```
시니컬한 원칙주의자 버전 프롬프트도 만들어줘.
```

```
프롬프트 파일을 폴더에서 자동으로 불러오게 하고 싶어. 라운드로빈으로.
```

AI가 `PathMatchingResourcePatternResolver` + `AtomicInteger`로 자동 디스커버리까지 제안.

### 핵심 포인트

> **프롬프트 엔지니어링 자체도 AI와 협업하면 품질이 크게 향상됨**
> 혼자서는 "심각도 분류" 같은 아이디어를 놓칠 수 있음

---

# Part B: Spring Boot 실습

> 여기부터 학생이 직접 코딩합니다.
> 정답 코드는 `claude-review-server/` 디렉토리를 참고하세요.

---

## Module 4: 환경 설정 + 프로젝트 생성 (20분)

### 4-1. JDK 확인

```bash
java --version
# openjdk 21.x 이어야 함
```

### 4-2. Spring Initializr에서 프로젝트 생성

1. https://start.spring.io 접속
2. 설정:
   - Project: **Gradle - Kotlin**
   - Language: **Java**
   - Spring Boot: **3.5.x** (최신 안정)
   - Group: `com.review`
   - Artifact: `server`
   - Java: **21**
3. Dependencies 추가: **Spring Web**, **Validation**
4. GENERATE → 다운로드 → 압축 해제 → IDE에서 열기

### 4-3. Spring AI 의존성 추가

`build.gradle.kts`에 추가:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring AI
    implementation(platform("org.springframework.ai:spring-ai-bom:1.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

### 4-4. application.yml 작성

`src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      base-url: ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
      chat:
        options:
          model: ${AI_MODEL:claude-sonnet-4-20250514}
          max-tokens: 2000
    retry:
      max-attempts: 3
      on-http-codes: 429
      backoff:
        initial-interval: 5s
        multiplier: 2
        max-interval: 20s

review:
  secret: ${REVIEW_SECRET:}
  allowed-owners: ${ALLOWED_GITHUB_OWNERS:}
```

**API 키별 설정 방법**:

| API 키 타입 | 환경변수 설정 |
|-------------|--------------|
| Anthropic 직접 (`sk-ant-...`) | `ANTHROPIC_API_KEY=sk-ant-...` 만 설정 |
| z.ai 게이트웨이 | `ANTHROPIC_API_KEY=your-zai-key` + `ANTHROPIC_BASE_URL=https://api.z.ai/api/anthropic` + `AI_MODEL=glm-5` |

### 4-5. 빌드 확인

```bash
./gradlew bootJar
# BUILD SUCCESSFUL 이어야 함
```

---

## Module 5: 헬스체크 + Spring AI ChatClient (30분)

### 5-1. HealthController

`controller/HealthController.java`:

```java
@RestController
public class HealthController {

    private final long startTime = System.currentTimeMillis();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "timestamp", Instant.now().toString(),
            "uptime", (System.currentTimeMillis() - startTime) / 1000
        );
    }
}
```

### 5-2. Spring AI 테스트 엔드포인트

`ChatClient`가 어떻게 작동하는지 확인용:

```java
@RestController
@RequestMapping("/test")
public class TestController {

    private final ChatClient chatClient;

    public TestController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping
    public String test(@RequestParam(defaultValue = "Hello") String message) {
        return chatClient.prompt()
            .system("한 문장으로 답변하세요.")
            .user(message)
            .call()
            .content();
    }
}
```

### 검증

```bash
# API 키와 함께 실행
ANTHROPIC_API_KEY=your-key ./gradlew bootRun

# 터미널 2: 헬스체크
curl http://localhost:8080/health
# → {"status":"ok","timestamp":"...","uptime":3}

# Spring AI 테스트
curl "http://localhost:8080/test?message=Spring%20AI가%20뭐야?"
# → "Spring AI는 스프링 애플리케이션에서 AI 모델을 쉽게 통합할 수 있게 해주는 프레임워크입니다."
```

`★ Insight ─────────────────────────────────────`
**Node.js 버전과 비교해보세요**:
- Node.js: `new Anthropic({apiKey})` → `messages.create()` (15줄)
- Spring AI: `ChatClient.Builder` 주입 → `.prompt().system().user().call()` (1줄)
- **재시도 로직**: Node.js는 30줄 수동 구현, Spring AI는 `application.yml` 4줄 설정
`─────────────────────────────────────────────────`

---

## Module 6: DTO + ReviewService (25분)

### 6-1. ReviewRequest (Java Record)

`dto/ReviewRequest.java`:

```java
public record ReviewRequest(
    @NotBlank String diff,
    String prTitle,
    String prAuthor,
    String repo,
    Integer prNumber,
    String baseBranch
) {
    // compact constructor — 기본값 설정
    public ReviewRequest {
        if (prTitle == null) prTitle = "(제목 없음)";
        if (prAuthor == null) prAuthor = "unknown";
        if (repo == null) repo = "unknown";
        if (prNumber == null) prNumber = 0;
        if (baseBranch == null) baseBranch = "main";
    }

    public boolean isDiffTooShort() {
        return diff == null || diff.trim().length() < 50;
    }
}
```

### 6-2. ReviewResponse (Java Record)

`dto/ReviewResponse.java`:

```java
public record ReviewResponse(
    String review,
    Usage usage,
    String model,
    long elapsedMs
) {
    public record Usage(Long inputTokens, Long outputTokens) {}
}
```

### 6-3. PromptProvider

`prompt/PromptProvider.java`:

```java
@Component
public class PromptProvider {

    private List<String> systemPrompts;
    private final AtomicInteger index = new AtomicInteger(0);

    @PostConstruct
    public void init() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:prompts/*.md");

        this.systemPrompts = Arrays.stream(resources)
            .sorted(Comparator.comparing(Resource::getFilename))
            .map(r -> r.getContentAsString(StandardCharsets.UTF_8))
            .toList();

        log.info("[Prompt] {}개 시스템 프롬프트 로드 완료", systemPrompts.size());
    }

    public String getSystemPrompt() {
        int i = index.getAndIncrement() % systemPrompts.size();
        return systemPrompts.get(i);
    }

    public String buildUserPrompt(ReviewRequest request) {
        String diff = request.diff();
        boolean wasTruncated = diff.length() > 10000;
        String diffSlice = wasTruncated ? diff.substring(0, 10000) : diff;

        return """
            PR 정보:
            - 저장소: %s
            - PR #%d: %s
            - 작성자: %s
            - 베이스 브랜치: %s
            %s
            ```diff
            %s
            ```
            """.formatted(
                request.repo(), request.prNumber(), request.prTitle(),
                request.prAuthor(), request.baseBranch(),
                wasTruncated ? "⚠️ diff가 길어 앞부분 10,000자만 리뷰합니다.\n" : "",
                diffSlice
            );
    }
}
```

### 6-4. ReviewService

`service/ReviewService.java`:

```java
@Service
public class ReviewService {

    private final ChatClient chatClient;
    private final PromptProvider promptProvider;

    public ReviewService(ChatClient.Builder chatClientBuilder, PromptProvider promptProvider) {
        this.chatClient = chatClientBuilder.build();
        this.promptProvider = promptProvider;
    }

    public ReviewResponse review(ReviewRequest request) {
        long start = System.currentTimeMillis();

        String content = chatClient.prompt()
            .system(promptProvider.getSystemPrompt())
            .user(promptProvider.buildUserPrompt(request))
            .call()
            .content();

        return new ReviewResponse(content, null, null, System.currentTimeMillis() - start);
    }
}
```

### 6-5. ReviewController

`controller/ReviewController.java`:

```java
@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/review")
    public ResponseEntity<?> review(@Valid @RequestBody ReviewRequest request) {
        if (request.isDiffTooShort()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "diff가 없거나 너무 짧습니다."));
        }
        return ResponseEntity.ok(reviewService.review(request));
    }
}
```

### 6-6. 프롬프트 파일 배치

`src/main/resources/prompts/system-review.md` 파일 생성 — Part A에서 AI와 함께 만든 프롬프트 내용을 넣습니다.

### 검증

```bash
curl -X POST http://localhost:8080/review \
  -H "Content-Type: application/json" \
  -d '{
    "diff": "diff --git a/App.java b/App.java\n--- a/App.java\n+++ b/App.java\n@@ -1,5 +1,8 @@\n public class App {\n+    String password = \"admin123\";\n+\n     public static void main(String[] args) {\n-        System.out.println(\"Hello\");\n+        System.out.println(password);\n     }\n }",
    "prTitle": "Add password field",
    "prAuthor": "student",
    "repo": "test/project",
    "prNumber": 1
  }'
# → 한국어 코드리뷰 응답 (하드코딩된 비밀번호 감지)
```

---

## Module 7: 인증 + Rate Limiter 인터셉터 (25분)

### 7-1. AuthInterceptor

`interceptor/AuthInterceptor.java`:

```java
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${review.secret:}")
    private String expectedSecret;

    @Value("${review.allowed-owners:}")
    private String allowedOwners;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"/review".equals(request.getRequestURI())) {
            return true;
        }

        List<String> errors = new ArrayList<>();
        String ip = getClientIp(request);
        String ghRepo = request.getHeader("x-github-repo");

        // 1. 공유 시크릿
        String secret = request.getHeader("x-review-secret");
        if (expectedSecret != null && !expectedSecret.isBlank()
                && (secret == null || !secret.equals(expectedSecret))) {
            errors.add("invalid_secret");
        }

        // 2. GitHub repo 헤더
        if (ghRepo == null || !ghRepo.contains("/")) {
            errors.add("missing_github_repo");
        }

        // 3. Owner 화이트리스트
        if (allowedOwners != null && !allowedOwners.isBlank() && ghRepo != null && ghRepo.contains("/")) {
            String owner = ghRepo.split("/")[0].trim().toLowerCase();
            List<String> allowed = Arrays.stream(allowedOwners.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .toList();
            if (!allowed.isEmpty() && !allowed.contains(owner)) {
                errors.add("unauthorized_owner:" + owner);
            }
        }

        if (!errors.isEmpty()) {
            log.warn("[Auth] BLOCKED — ip:{} repo:{} reason:{}", ip, ghRepo, errors);
            response.sendError(404);  // 401이 아닌 404로 응답 (엔드포인트 숨김)
            return false;
        }

        log.info("[Auth] PASS — ip:{} repo:{}", ip, ghRepo);
        return true;
    }
}
```

`★ Insight ─────────────────────────────────────`
**왜 401이 아니라 404인가?** 인증 실패를 401로 응답하면 공격자에게 "이 엔드포인트가 존재한다"는 정보를 줍니다. 404로 응답하면 엔드포인트 자체를 숨길 수 있습니다.
`─────────────────────────────────────────────────`

### 7-2. RateLimitInterceptor

`interceptor/RateLimitInterceptor.java`:

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int WINDOW_MS = 60_000;
    private static final int MAX_REQUESTS = 10;

    private final ConcurrentHashMap<String, Window> store = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        Window window = store.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.start > WINDOW_MS) {
                return new Window(1, now);
            }
            existing.count++;
            return existing;
        });

        if (window.count > MAX_REQUESTS) {
            response.setHeader("Retry-After", String.valueOf(
                (int) Math.ceil((window.start + WINDOW_MS - now) / 1000.0)));
            response.sendError(429, "Too many requests");
            return false;
        }
        return true;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now - e.getValue().start > WINDOW_MS * 2);
    }

    private static class Window {
        int count;
        final long start;
        Window(int count, long start) { this.count = count; this.start = start; }
    }
}
```

### 7-3. WebConfig에 등록

`config/WebConfig.java`:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AuthInterceptor authInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor, AuthInterceptor authInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
        registry.addInterceptor(authInterceptor);
    }
}
```

**주의**: `ClaudeReviewApplication.java`에 `@EnableScheduling`이 있어야 `@Scheduled`가 동작합니다.

### 검증

```bash
# 인증 없이 → 404
curl -X POST http://localhost:8080/review \
  -H "Content-Type: application/json" \
  -d '{"diff": "...50자 이상..."}'
# → 404

# 올바른 헤더 → 200
curl -X POST http://localhost:8080/review \
  -H "Content-Type: application/json" \
  -H "x-review-secret: your-secret" \
  -H "x-github-repo: yourname/repo" \
  -d '{"diff": "...50자 이상..."}'
# → 200 + 리뷰 내용

# Rate limit 테스트
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/health
done
# → 10 x 200, 2 x 429
```

---

## Module 8: Docker + Nginx 배포 (25분)

### 8-1. Dockerfile (Multi-stage build)

```dockerfile
# 빌드 스테이지
FROM gradle:8.12-jdk21-alpine AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# 런타임 스테이지
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S reviewer && adduser -S reviewer -G reviewer
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown -R reviewer:reviewer /app
USER reviewer
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 8-2. docker-compose.yml

```yaml
services:
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/ssl:/etc/nginx/ssl:ro
      - certbot-web:/var/www/certbot:ro
    depends_on:
      review-server:
        condition: service_healthy
    networks:
      - review-net
    restart: unless-stopped

  review-server:
    build: .
    env_file: .env
    expose:
      - "8080"
    networks:
      - review-net
    restart: unless-stopped

  certbot:
    image: certbot/certbot
    profiles: ["certbot"]
    volumes:
      - certbot-web:/var/www/certbot
      - certbot-etc:/etc/letsencrypt
    networks:
      - review-net

volumes:
  certbot-web:
  certbot-etc:

networks:
  review-net:
    driver: bridge
```

### 8-3. nginx.conf

포트가 `3000`에서 `8080`으로 변경된 것을 확인:

```nginx
# /health
proxy_pass http://review-server:8080/health;

# /review
proxy_pass http://review-server:8080/review;
```

### 8-4. .env 파일 생성

```bash
cp .env.example .env
# 에디터로 API 키 입력
```

### 8-5. 빌드 및 실행

```bash
# Docker 이미지 빌드
docker compose build

# 서버 시작
docker compose up -d

# 로그 확인
docker compose logs -f review-server
```

### 검증

```bash
curl http://localhost/health
# → {"status":"ok",...}
```

---

## Module 9: GitHub Actions 연동 (20분)

### 9-1. 워크플로우 파일

리포지토리에 `.github/workflows/ai-review.yml` 복사 (정답 프로젝트에서 복사)

### 9-2. GitHub Secrets 등록

리포지토리 Settings → Secrets and variables → Actions:

| Secret | 값 |
|--------|-----|
| `REVIEW_SERVER_URL` | `https://yourdomain.com` |
| `REVIEW_SECRET` | `.env`의 `REVIEW_SECRET`과 동일한 값 |

### 9-3. 테스트 PR 생성

```bash
# 테스트 브랜치 생성
git checkout -b test/ai-review

# 의도적 결함이 있는 Java 파일 추가
echo 'public class BadCode {
    String dbPassword = "root1234";
    public void process(String input) {
        System.out.println(input);
    }
}' > BadCode.java

git add . && git commit -m "test: add code with issues"
git push origin test/ai-review

# GitHub에서 PR 생성
```

### 검증

1. GitHub Actions 탭에서 워크플로우 실행 확인
2. PR에 AI 리뷰 코멘트가 달렸는지 확인

---

## Module 10: End-to-End 테스트 (15분)

### 테스트 시나리오

| 시나리오 | 예상 결과 |
|---------|----------|
| 정상 PR (Java 변경) | 리뷰 코멘트 등록 |
| 아주 작은 변경 (100자 미만) | 스킵 코멘트 |
| 잘못된 시크릿 | Actions 로그에 HTTP 404 |
| 하드코딩된 비밀번호 포함 | 리뷰에서 보안 경고 |

### 서버 로그 확인

```bash
docker compose logs -f review-server
# [Auth] PASS → [Review] START → [Review] DONE 흐름 확인
```

---

## Module 11: 회고 + AI Native 개발 팁 (10분)

### AI Native 개발 4가지 팁

1. **요구사항 단계에서 AI와 대화하면 누락 방지**
   - AI가 "보안은 어떻게 하죠?"라고 먼저 물어봄

2. **기존 코드를 AI에게 보여주면 마이그레이션이 쉬움**
   - Node.js 코드를 보여주니 Spring Boot 맵핑을 바로 제시

3. **프롬프트 엔지니어링도 AI와 협업하면 품질 향상**
   - 혼자서는 "심각도 분류"를 놓쳤을 것

4. **에러 메시지를 그대로 AI에게 주면 디버깅이 빠름**
   - 스택트레이스를 복붙하면 원인과 해결책을 바로 제시

### 토론

> "오늘 AI 없이 했으면 얼마나 걸렸을까?"

---

# 부록: Node.js → Spring Boot 매핑표

| Node.js | Spring Boot | 비고 |
|---------|-------------|------|
| `express()` | `@SpringBootApplication` | |
| `express.json()` | `@RequestBody` + Jackson | |
| `app.get("/health", ...)` | `@GetMapping("/health")` | |
| `app.post("/review", ...)` | `@PostMapping("/review")` | |
| `verifyRequest` 미들웨어 | `HandlerInterceptor` | |
| `rateLimiter` 미들웨어 | `HandlerInterceptor` + `ConcurrentHashMap` | |
| `process.env.X` | `@Value("${X}")` | |
| `readFileSync("prompts/")` | `ClassPathResource("prompts/")` | JAR 패키징 시에도 동작 |
| 수동 재시도 루프 (30줄) | `spring.ai.retry.*` (4줄) | **Spring AI의 큰 장점** |
| `sk-ant-` 런타임 감지 | 환경변수로 설정 | 선언적 접근 |
| `res.status(404).end()` | `response.sendError(404)` | |
| `new Anthropic(...)` | `ChatClient.Builder` 주입 | Spring AI 자동 설정 |
| `Map` 스토어 | `ConcurrentHashMap` | 스레드 안전 |
| `setInterval(cleanup, ...)` | `@Scheduled(fixedRate=...)` | |
