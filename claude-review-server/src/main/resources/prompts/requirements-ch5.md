# CH5 플러스 프로젝트 과제 요구사항

## 필수 기능

### 1. 동시성 제어
- **Lettuce 기반 Redis Lock 필수** (Redisson 사용 금지 — 도전 기능에서만 허용)
- SETNX + TTL로 Lock 획득 및 자동 만료 설정
- UUID + Lua Script로 본인이 잂은 Lock만 원자적으로 해제
- Lock Key 설계: 비즈니스 도메인 식별자 활용 (예: lock:product:{productId})
- Lock 획득 실패 전략: Fail Fast / Retry with backoff / Blocking 중 선택
- ExecutorService + CyclicBarrier로 동시성 이슈 재현 테스트 코드 작성 (Lock 적용 전에는 실패해야 정상)

### 2. 캐싱을 이용한 성능 개선
- 검색 API v1: LIKE 조건 검색 + Paging (JPA/QueryDSL, SQL에 LIKE 포함 필수)
- 인기 검색어: Redis Sorted Set (ZINCRBY + ZREVRANGE), 동일 사용자 중복 카운팅 방지
- 검색 API v2: Caffeine Local Memory Cache 적용 (@Cacheable + @EnableCaching)
- v2 설정: TTL(만료 시간) + maximumSize(최대 캐시 수) 명시
- v1, v2 두 API 공존 필수
- QueryDSL: BooleanExpression/BooleanBuilder 동적 쿼리, Projections.constructor() DTO 직접 조회

### 3. 검색 기능 공통
- offset/limit 페이징 처리
- count 쿼리 분리 패턴 권장

## 도전 기능 (코드에서 감지되면 리뷰, 아니면 언급하지 않음)

### 동시성 고도화
- AOP 방식 Lock 리팩토링: 커스텀 애노테이션(@RedisLock) + @Aspect + ProceedingJoinPoint
- 낙관적 락: JPA @Version + OptimisticLockException 재시도
- 비관적 락: JPA 비관적 락 / MySQL Exclusive Lock
- Redisson: Lettuce 대비 선택 이유 설명 가능해야 함
- 3가지 락 방식 비교 분석 (낙관/비관/분산)

### 캐싱 고도화
- v2를 Redis Remote Cache로 전환 (RedisTemplate, StringRedisSerializer + GenericJackson2JsonRedisSerializer)
- ObjectMapper에 JavaTimeModule 등록 (LocalDate/LocalDateTime 직렬화)
- 5만건+ Dummy 데이터 적재 (SQL Stored Procedure / JDBC Batch / Datafaker)
- K6 부하 테스트: TPS, 평균 응답 시간, 포화 지점 측정
- Cache Eviction: @CacheEvict vs @CachePut 적절히 활용

### 인덱스 최적화
- EXPLAIN으로 type=ALL, key=NULL (Full Table Scan) 식별
- 단일 인덱스 vs 복합 인덱스 선택 근거 (카디널리티, Leftmost Prefix Rule)
- Before/After EXPLAIN 비교 (rows, type, key 변화)

### 실시간 채팅
- WebSocket + STOMP 프로토콜
- 채팅방 생성/입장/퇴장, 메시지 영속화 (DB 저장)
- 커서 기반 페이징으로 채팅 메시지 조회
- JWT 인증: HTTP Filter가 아닌 STOMP ChannelInterceptor에서 처리
- Redis Pub/Sub으로 분산 서버 메시지 브로드캐스팅

### 배포와 CI/CD
- Docker 컨테이너화 (Dockerfile + docker-compose)
- AWS: EC2 + RDS + ElastiCache, VPC + Public/Private Subnet, NAT Gateway
- GitHub Actions CI: Push/PR 트리거 분리 (PR→테스트, main merge→이미지 빌드+배포)
- CD: SSM + Docker Pull 방식 권장
- 헬스체크 (/actuator/health), 민감 정보 관리 (GitHub Secrets / AWS Parameter Store)
