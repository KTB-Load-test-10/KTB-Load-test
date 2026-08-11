# Redis 기반 인증·인가 성능 최적화

## 목적

세션과 Socket.IO 메시지 rate limit을 MongoDB의 요청당 조회·쓰기 경로에서 분리한다.
MongoDB는 사용자, 채팅 메시지, 채팅방, 파일처럼 영구 보관해야 하는 데이터만 담당한다.

```text
JWT 서명·만료 검증
  -> Redis 세션 검증
  -> REST API 또는 Socket.IO 메시지 처리

Socket.IO 메시지
  -> Redis 세션 검증
  -> Redis rate limit
  -> MongoDB 메시지 저장 및 broadcast
```

제공 Playwright E2E·Artillery 시나리오와 REST/Socket 이벤트, JWT 헤더 계약은 변경하지 않는다.

## 세션 설계

- 키: `chat:session:user:{userId}`
- 값: `sessionId`, `createdAt`, `lastActivity`, `expiresAt`, 메타데이터를 담은 JSON
- TTL: 기본 30분
- 새 로그인 또는 토큰 갱신은 기존 키를 덮어써 단일 세션을 유지한다.
- 로그아웃은 Lua script로 저장된 `sessionId`가 요청 값과 일치할 때만 키를 삭제한다. 이전 토큰이 새 로그인 세션을 삭제하지 못한다.

세션 검증 Lua script는 session ID 일치 여부와 TTL을 한 번에 확인한다. 활동 중인 사용자의 TTL은 다음 경우에만 연장한다.

- 마지막 갱신 후 기본 60초가 지난 경우

따라서 메시지 또는 보호 API 요청마다 MongoDB 문서와 TTL 인덱스를 갱신하던 기존 방식이, Redis 검증과 제한된 TTL 갱신으로 변경된다.

## Rate limit 설계

- 키: `chat:rate-limit:{clientId}`
- 고정 윈도우 카운터: Lua script가 `INCR`, 최초 TTL 설정, TTL 반환을 원자적으로 처리한다.
- 키 TTL은 제한 윈도우와 같아서 일시적인 테스트 사용자 키가 자동으로 제거된다.
- Redis 장애는 fail-closed다. rate limit 또는 세션 검증 저장소가 응답하지 않으면 요청을 허용하지 않는다.

## 설정

| 환경 변수 | 기본값 | 설명 |
|---|---:|---|
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `REDIS_PASSWORD` | 빈 값 | Redis 인증 비밀번호 |
| `REDIS_CONNECT_TIMEOUT` | `500ms` | Redis 연결 timeout |
| `REDIS_COMMAND_TIMEOUT` | `500ms` | Redis 명령 timeout |
| `AUTH_SESSION_TOUCH_INTERVAL_SECONDS` | `60` | 활동 시간/TTL 갱신 간격 |

## 운영 및 검증

- 첫 Redis 전환 배포 후 MongoDB `sessions` 컬렉션의 기존 세션은 Redis에 없으므로, 기존 로그인 사용자는 재로그인해야 한다.
- 메시지, 사용자, 방, 파일 데이터의 마이그레이션은 필요 없다.
- Prometheus에서 기존 `chat.auth.logout.session_delete.*` 지표와 Redis command latency, 인증/Socket 오류율을 함께 확인한다.
- 검증 명령:

```bash
cd apps/backend
./mvnw -Punit-tests test -Dtest=SessionServiceUnitTest,RateLimitServiceUnitTest
./mvnw -Pintegration-tests test -Dtest=SessionServiceTest,RateLimitServiceTest
```

마지막으로 실제 프런트·백엔드·Redis 환경에서 제공 E2E 23개와 Artillery 7개를 수정 없이 실행해 로그인, 로그아웃, Socket 연결, 메시지 전송 계약을 확인한다.
