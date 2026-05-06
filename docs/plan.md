# 특강 제작 계획: AI 코드리뷰 시스템 (Spring Boot + Spring AI)

## Context

기존 Node.js/Express로 구현된 AI 코드리뷰 시스템을 Spring Boot + Spring AI로 재구성하여,
부트캠프 수강생들이 직접 따라 만들 수 있는 실습형 특강 자료를 제작한다.
수강생은 Java 21+, Spring Boot 3.5.x, Spring AI 1.0.0 환경에서 실습한다.

---

## 실습 환경 결정

- **배포**: Docker + Nginx (기존 방식 그대로 — 운영 환경 경험)
- **API 키**: 학생 개별 발급 (Anthropic 또는 z.ai)
- **산출물**: 강의 안내서 + 완성된 정답 프로젝트

## 추천 기술 스택

| 항목 | 선택 | 이유 |
|------|------|------|
| Build | **Gradle (Kotlin DSL)** | Spring Initializr 기본, 간결함 |
| Language | **Java 21** | LTS, Record, Text Blocks |
| Framework | **Spring Boot 3.5.x** | 최신 안정 버전 |
| AI | **spring-ai-starter-model-anthropic 1.0.0** | ChatClient 자동 설정, 재시도 내장 |
| Web | **spring-boot-starter-web** | REST Controller, Tomcat |
| Validation | **spring-boot-starter-validation** | `@Valid`, `@NotBlank` |
| Test | **spring-boot-starter-test** | JUnit 5, MockMvc |
| 배포 | **Docker + Nginx + Let's Encrypt** | 실제 운영 환경 경험, 기존 프로젝트 구성 재사용 |

### Spring AI가 자동 제공하는 기능 (직접 구현 불필요)
- Anthropic API 호출 (`ChatClient` 빈 자동 설정)
- **429 재시도 + 지수 백오프** (`spring.ai.retry.*` 설정만으로 완료)
- `base-url` 설정으로 z.ai 게이트웨이 지원

### 직접 구현해야 할 것
- 인증 인터셉터 (공유 시크릿 + GitHub Owner 화이트리스트)
- Rate Limiter (인메모리 슬라이딩 윈도우)
- 프롬프트 로더 (클래스패스에서 `.md` 파일 읽기)
- User Prompt 빌더

---

## 최종 프로젝트 구조

```
claude-review-server/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile                       # Java 21 기반 컨테이너
├── docker-compose.yml               # nginx + app + certbot 3서비스
├── nginx/
│   ├── nginx.conf                   # 리버스 프록시 + SSL
│   └── ssl/
├── certbot/
│   ├── issue-cert.sh                # SSL 인증서 발급
│   └── renew.sh                     # SSL 갱신
├── setup.sh                         # 초기 설정 스크립트
├── src/
│   ├── main/
│   │   ├── java/com/review/server/
│   │   │   ├── ClaudeReviewApplication.java
│   │   │   ├── controller/
│   │   │   │   ├── HealthController.java
│   │   │   │   └── ReviewController.java
│   │   │   ├── dto/
│   │   │   │   ├── ReviewRequest.java      (Record)
│   │   │   │   └── ReviewResponse.java      (Record)
│   │   │   ├── service/
│   │   │   │   └── ReviewService.java
│   │   │   ├── interceptor/
│   │   │   │   ├── AuthInterceptor.java
│   │   │   │   └── RateLimitInterceptor.java
│   │   │   ├── prompt/
│   │   │   │   └── PromptProvider.java
│   │   │   └── config/
│   │   │       └── WebConfig.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── prompts/
│   │           ├── system-review.md
│   │           └── system-review-cynic.md
│   └── test/
│       └── java/com/review/server/
│           └── ReviewControllerTest.java
├── .github/
│   └── workflows/
│       └── ai-review.yml
└── docs/
    └── lecture-guide.md
```

---

## 특강 모듈 구성 (총 ~250분)

### Part A: AI Native 개발 워크샵 (~60분)
**목표**: AI와 협업하여 요구사항을 분석하고 설계하는 과정을 체험

#### Module 0: 오리엔테이션 (10분)
- 특강 목표 소개: "여러분이 직접 AI 코드리뷰 서버를 만듭니다"
- 최종 산출물 미리보기: 실제 PR에 AI 리뷰가 달리는 데모
- AI Native 개발이란: AI와 대화하며 요구사항 정의 → 설계 → 구현하는 방식

#### Module 1: AI와 함께 요구사항 정의 (20분)
**라이브 코딩**: 강사가 Claude Code와 대화하며 요구사항을 정의하는 과정을 시연

- "GitHub PR이 올라오면 자동으로 AI가 코드리뷰를 달아주는 서버를 만들고 싶어"
- AI가 질문하는 내용을 통해 학습:
  - "어떤 언어를 리뷰하나요?" → Java/Spring Boot
  - "API 키는 어떤 것을 사용하나요?" → Anthropic / z.ai 게이트웨이
  - "리뷰 대상은 누구인가요?" → 부트캠프 수강생
- AI가 도출한 요구사항을 학생들과 함께 리뷰
- **핵심 포인트**: AI에게 맥락을 주면 좋은 질문을 던져줌 → 요구사항 누락 방지

#### Module 2: AI와 함께 아키텍처 설계 (15분)
**라이브 코딩**: Claude Code에게 아키텍처를 제안받고 토론

- "Spring Boot + Spring AI로 위 요구사항을 구현하는 아키텍처를 제안해줘"
- AI가 제안한 아키텍처 분석:
  - Controller → Service → Prompt 계층 구조
  - Spring AI ChatClient 자동 설정
  - Interceptor로 인증/Rate Limit
  - `application.yml`로 dual API mode
- Node.js 버전과 비교하며 Spring Boot 매핑 이해
- **핵심 포인트**: 기존 코드를 AI에게 보여주면 마이그레이션 맵핑을 해줌

#### Module 3: AI에게 프롬프트 엔지니어링 맡기기 (15분)
**라이브 코딩**: Claude Code에게 시스템 프롬프트를 작성하게 하고 개선

- "코드리뷰용 시스템 프롬프트의 베스트 프랙티스를 조사해줘"
- AI가 조사한 결과 리뷰 (CoT, 체크리스트, 심각도 등)
- "시니컬한 원칙주의자 버전 프롬프트도 만들어줘"
- 라운드로빈 아이디어를 AI와 논의하여 자동 디스커버리까지 발전
- **핵심 포인트**: 프롬프트 엔지니어링 자체도 AI와 협업하면 품질이 크게 향상됨

---

### Part B: 실습 — Spring Boot 프로젝트 구축 (~190분)

#### Module 4: 환경 설정 + 프로젝트 생성 (20분)
- JDK 21, Docker 설치 확인
- API 키 발급 안내: Anthropic (`sk-ant-...`) 또는 z.ai 게이트웨이
- Spring Initializr에서 프로젝트 생성
- `application.yml` 설정 (Spring AI + retry + dual API mode)
- **검증**: 프로젝트 빌드 성공

#### Module 5: 헬스체크 + Spring AI ChatClient (30분)
- `HealthController` 구현
- `ChatClient.Builder` 주입 및 테스트 엔드포인트
- Claude 호출 확인 — Part A에서 설계한 대로 구현
- **검증**: `curl localhost:8080/health` → OK, Claude 응답 확인

#### Module 6: DTO + ReviewService (25분)
- Java Record로 `ReviewRequest`, `ReviewResponse` 정의
- `PromptProvider`: 클래스패스에서 시스템 프롬프트 로드
- `ReviewService`: ChatClient로 리뷰 생성
- `ReviewController`: POST /review 엔드포인트
- **검증**: curl로 리뷰 요청 → 한국어 코드리뷰 응답

#### Module 7: 인증 + Rate Limiter 인터셉터 (25분)
- `AuthInterceptor`: 공유 시크릿, GitHub Repo 헤더, Owner 화이트리스트
- 실패 시 404 반환 (엔드포인트 존재 숨김)
- `RateLimitInterceptor`: ConcurrentHashMap 슬라이딩 윈도우
- `WebConfig`에 인터셉터 등록
- **검증**: 인증 없이 → 404, 올바른 헤더 → 200, 11번째 요청 → 429

#### Module 8: Docker + Nginx 배포 (25분)
- `Dockerfile` 작성 (Java 21 베이스)
- `docker-compose.yml` (nginx + app + certbot)
- `nginx.conf` 설정 (리버스 프록시 + SSL + 보안 헤더)
- Let's Encrypt 인증서 발급
- **검증**: `curl https://yourdomain.com/health` → `{"status":"ok"}`

#### Module 9: GitHub Actions 연동 (20분)
- `.github/workflows/ai-review.yml` 작성
- GitHub Secrets 설정 (REVIEW_SERVER_URL, REVIEW_SECRET)
- **검증**: PR 생성 → Actions 실행

#### Module 10: End-to-End 테스트 (15분)
- 의도적 결함이 있는 PR 생성
- 전체 파이프라인 실시간 확인
- 에러 케이스 테스트
- **검증**: PR에 AI 리뷰 코멘트 등록 완료

#### Module 11: 회고 + AI Native 개발 팁 (10분)
- "AI 없이 했으면 얼마나 걸렸을까?" 토론
- Part A에서 경험한 AI 협업 팁 정리:
  - 요구사항 단계에서 AI와 대화하면 누락 방지
  - 기존 코드를 AI에게 보여주면 마이그레이션이 쉬움
  - 프롬프트 엔지니어링도 AI와 협업하면 품질 향상
  - 에러 메시지를 그대로 AI에게 주면 디버깅이 빠름

### Bonus (시간 여유 시): 라운드로빈 프롬프트
- `PathMatchingResourcePatternResolver`로 `.md` 파일 자동 탐색
- `AtomicInteger`로 라운드로빈 순환

---

## Node.js → Spring Boot 개념 매핑표 (특강 자료용)

| Node.js | Spring Boot | 비고 |
|---------|-------------|------|
| `express()` | `@SpringBootApplication` | |
| `express.json()` | `@RequestBody` + Jackson | |
| `app.post("/review", ...)` | `@PostMapping("/review")` | |
| `verifyRequest` 미들웨어 | `HandlerInterceptor` | |
| `rateLimiter` 미들웨어 | `HandlerInterceptor` + `ConcurrentHashMap` | |
| `process.env.X` | `@Value("${X}")` | |
| `readFileSync("prompts/")` | `ClassPathResource("prompts/")` | |
| 수동 재시도 루프 (30줄) | `spring.ai.retry.*` 설정 4줄 | Spring AI의 큰 장점 |
| `sk-ant-` 런타임 감지 | 환경변수로 설정 | 선언적 접근 |

---

## 핵심 코드 스니펫

### application.yml (Dual API Mode)
```yaml
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
```
- z.ai 사용 학생: `ANTHROPIC_BASE_URL=https://api.z.ai/api/anthropic`, `AI_MODEL=glm-5`
- Anthropic 직접 사용 학생: 환경변수 미설정 시 기본값 사용

### ReviewService (핵심 로직)
```java
@Service
public class ReviewService {
    private final ChatClient chatClient;
    private final PromptProvider promptProvider;

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

---

## 산출물

1. **강의 안내서** (`docs/lecture-guide.md`) — 모듈별 상세 실습 가이드 (한국어)
   - Part A: AI Native 개발 워크샵 (요구사항 정의, 아키텍처 설계, 프롬프트 엔지니어링)
   - Part B: Spring Boot 실습 (프로젝트 구축, 인터셉터, 배포, GitHub Actions 연동)
2. **완성된 Spring Boot 프로젝트** (`claude-review-server/`) — 정답 코드
3. **Docker 배포 설정** — `Dockerfile`, `docker-compose.yml`, `nginx/`, `certbot/` (기존 구성 Spring Boot에 맞게 수정)
4. **GitHub Actions 워크플로우** (`.github/workflows/ai-review.yml`) — 기존 파일 재사용
5. **프롬프트 파일** (`src/main/resources/prompts/`) — 기존 `.md` 파일 재사용
6. **초기 설정 스크립트** (`setup.sh`) — 기존 스크립트 Spring Boot에 맞게 수정
7. **AI Native 개발 가이드** (`docs/ai-native-guide.md`) — Claude Code와 협업하는 방법 정리

## 검증 방법

1. 각 모듈 완료 후 curl 명령으로 개별 검증
2. Module 8에서 PR 생성 → Actions 로그 → 리뷰 코멘트 전체 파이프라인 확인
3. `./gradlew test` 로 자동화 테스트 실행

## 참조 파일 (기존 Node.js 프로젝트)

- 기존 서버: `server/server.js` (모든 Spring Boot 컴포넌트의 원본)
- Auth 미들웨어: `server/middleware/auth.js`
- Rate Limiter: `server/middleware/rateLimiter.js`
- GitHub Actions: `.github/workflows/ai-review.yml`
- 프롬프트: `server/prompts/system-review.md`, `server/prompts/system-review-cynic.md`
- Docker 배포: `docker-compose.yml`, `nginx/nginx.conf`, `server/Dockerfile`
- SSL: `certbot/issue-cert.sh`, `certbot/renew.sh`
- 설정 스크립트: `setup.sh`

## 작업 순서

1. Spring Boot 프로젝트 정답 코드 작성 (`claude-review-server/`)
2. Docker/Nginx 배포 설정을 Spring Boot에 맞게 수정
3. 기존 프롬프트 파일을 `src/main/resources/prompts/` 로 복사
4. 강의 안내서 작성 (`docs/lecture-guide.md`) — Part A (AI Native) + Part B (실습)
5. AI Native 개발 가이드 작성 (`docs/ai-native-guide.md`)
6. End-to-End 테스트로 전체 파이프라인 검증
