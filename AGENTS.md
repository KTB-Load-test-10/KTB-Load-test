# AGENTS.md

## 역할과 목표

이 저장소에서 작업하는 에이전트는 기본적으로 **3번 담당자: 메시지 초기 히스토리, 조회 비용, MongoDB 인덱스 및 관련 부하 측정** 역할을 맡는다.

목표는 공식 E2E 동작과 외부 계약을 유지하면서 메시지 초기 조회의 N+1, 불필요한 count 쿼리, 무제한 조회 및 비효율적인 정렬·스캔을 제거하고 개선 전후의 효과를 수치로 입증하는 것이다.

## 필수 작업 범위

다음 항목을 우선순위에 따라 수행한다.

1. 메시지 조회량 제한
   - 기본 `limit`은 30으로 한다.
   - 최대 `limit`은 100으로 제한한다.
   - 누락, 0 또는 음수 값은 기본값으로 처리한다.
   - 최대값을 초과하면 100으로 제한하거나 기존 API 정책에 맞는 400 응답을 사용하되, 공식 계약을 깨지 않는다.

2. 전체 count 쿼리 제거
   - 메시지 히스토리에서는 전체 메시지 수가 아니라 다음 페이지 존재 여부만 계산한다.
   - `Page` 대신 `Slice` 또는 `limit + 1` 방식을 우선 사용한다.
   - `limit + 1`을 조회한 경우 응답에는 요청한 개수만 반환하고 초과 결과로 `hasMore`를 계산한다.

3. sender/file N+1 제거
   - 조회한 메시지에서 중복을 제거한 sender ID와 file ID를 수집한다.
   - 사용자와 파일은 각각 `findAllById()` 등으로 한 번에 조회한다.
   - 조회 결과를 `Map<ID, Entity/DTO>`로 구성해 응답을 매핑한다.
   - 응답 매퍼가 메시지마다 Repository를 직접 호출하는 구조를 만들지 않는다.

4. 메시지 조회 인덱스 적용 및 검증
   - 핵심 후보는 `{ room: 1, timestamp: -1 }`이다.
   - 실제 쿼리의 필터와 정렬 순서에 맞는지 반드시 `explain()`으로 확인한다.
   - 인덱스 이름 또는 생성 위치는 프로젝트의 기존 MongoDB 인덱스 관리 방식을 따른다.
   - 인덱스를 추가했다는 사실만으로 완료 처리하지 않는다.

5. 현재 공식 시나리오와 연결된 초기 히스토리 개선
   - 방 입장 시 로딩되는 초기 메시지를 주 검증 대상으로 삼는다.
   - 공식 Artillery 시나리오에 없는 깊은 스크롤·여러 페이지 반복 조회는 별도 시나리오가 추가되지 않는 한 필수 범위로 확대하지 않는다.

주요 대상 파일은 다음과 같다.

- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageResponseMapper.java`
- `apps/backend/src/main/java/com/ktb/chatapp/dto/FetchMessagesRequest.java`
- `apps/backend/src/main/java/com/ktb/chatapp/repository/MessageRepository.java`
- `apps/backend/src/main/java/com/ktb/chatapp/repository/UserRepository.java`
- `apps/backend/src/main/java/com/ktb/chatapp/repository/FileRepository.java`
- 관련 단위·통합 테스트
- `e2e/artillery/` 아래의 초기 히스토리 측정 코드

## 조건부·후순위 작업

다음 작업은 필요성이 측정되거나 사용자가 명시적으로 요청한 경우에만 진행한다.

- 읽음 상태 bulk update
  - 초기 조회 경로에서 발생할 수 있으나 동시성 및 원자성 검증이 필요하다.
  - 적용한다면 `$addToSet`, 조건부 update 또는 `bulkWrite`를 검토하고 중복 사용자 추가와 데이터 유실을 방지한다.
- `BannedWordChecker` 자료구조 변경
  - 먼저 CPU 프로파일 또는 벤치마크로 실제 병목임을 확인한다.
  - 병목일 때만 Aho-Corasick 또는 Trie를 적용한다.
  - Unicode 정규화, 부분 문자열 및 기존 금칙어 판정 의미를 유지한다.
- `{ file: 1 }`, `{ filename: 1 }` 인덱스
  - 실제 호출 빈도와 `explain()` 결과가 이점을 보일 때만 적용한다.

## 이번 역할에서 기본적으로 제외하는 작업

사용자의 별도 요청이 없으면 다음 영역까지 작업 범위를 넓히지 않는다.

- 스크롤 기반 이전 메시지 반복 조회 기능 자체의 신규 구현
- 메시지 리액션 최적화
- 파일 다운로드 성능 최적화
- Socket.IO 다중 노드 pub/sub 및 공유 adapter
- object storage/CDN 전환
- 방 목록·방 생성 최적화인 1번 담당 영역
- 세션·Rate Limit 최적화인 2번 담당 영역

다른 담당자 코드의 변경이 반드시 필요하면 최소 변경으로 제한하고, 변경 이유와 계약 영향을 명확히 기록한다.

## 호환성 규칙

- 공식 Playwright E2E의 화면 동작, 라우팅, test ID를 유지한다.
- 기존 API 응답과 Socket 이벤트 이름·의미를 임의로 변경하지 않는다.
- pagination 응답을 변경해야 한다면 기존 소비자를 조사하고 호환 계층 또는 동시 변경을 제공한다.
- 최적화 때문에 메시지 순서, 누락 여부, 중복 여부, 파일 매핑, sender 정보 또는 읽음 상태의 의미가 바뀌어서는 안 된다.
- 최신 메시지 정렬 기준과 cursor의 경계 조건을 명시적으로 테스트한다. 동일 timestamp가 가능하면 안정적인 보조 정렬 키를 고려한다.

## 구현 원칙

- 성능 문제를 추측만으로 고치지 말고 쿼리 수, `explain()` 또는 프로파일 결과로 먼저 확인한다.
- 애플리케이션 메모리에서 전체 메시지를 가져온 뒤 정렬·절단하지 않는다. 필터, 정렬, limit은 DB에서 수행한다.
- Repository 호출 횟수가 메시지 개수에 비례하지 않도록 한다.
- 빈 메시지 목록, 존재하지 않는 sender/file, 삭제된 참조를 안전하게 처리한다.
- 기존 사용자 변경 사항과 무관한 파일은 수정하거나 정리하지 않는다.
- 생성물인 `target/`, `.next/`, `node_modules/`는 소스 변경에 포함하지 않는다.

## 테스트 및 검증

변경 위험에 비례하여 다음 검증을 수행한다.

### 백엔드 집중 테스트

```powershell
Set-Location apps/backend
./mvnw -Punit-tests test
./mvnw -Dtest=MessageLoaderTest test
./mvnw -Dtest=MessageLoaderIntegrationTest test
```

환경 또는 Docker가 필요한 통합 테스트를 실행할 수 없다면 실패 원인을 숨기지 말고 결과에 명시한다.

최소한 다음 사례를 테스트한다.

- limit 누락, 0, 음수, 30, 100, 100 초과
- 결과가 0개, limit 미만, 정확히 limit, limit보다 1개 많은 경우
- `hasMore`와 cursor 경계
- 여러 메시지가 같은 sender/file을 참조하는 경우 batch 조회 횟수
- sender/file 참조가 없거나 삭제된 경우
- 정렬 순서와 페이지 간 중복·누락 방지
- count 쿼리가 발생하지 않는지 검증

### 공식 회귀 테스트

서비스 실행 환경이 준비된 경우 저장소 루트에서 다음을 수행한다.

```powershell
pnpm test:e2e
```

공식 평가는 Playwright E2E 23개가 모두 통과해야 Artillery 부하 테스트로 진행된다는 점을 유지한다. 로컬 스크립트 수정으로 테스트를 우회하지 말고 원본 사용자 동작을 만족시킨다.

### 성능 검증

변경 전후를 동일한 seed, 방, 메시지 수, VU 및 실행 시간으로 비교한다. 최소 기록 항목은 다음과 같다.

- 초기 히스토리 p50/p95/p99
- 요청당 MongoDB 쿼리 수
- count 쿼리 발생 여부
- `totalDocsExamined`, `totalKeysExamined`
- `COLLSCAN`/`IXSCAN` 여부와 별도 `SORT` 단계
- MongoDB query p95
- 응답 payload 크기
- 서버 CPU, JVM heap/GC 및 오류율

Artillery의 한 VU는 현재 여러 시나리오를 순차 실행한다. 결과를 해석할 때 초기 히스토리 구간의 지표를 분리하고, 브라우저 또는 시나리오 자체의 고정 지연을 서버 latency로 오해하지 않는다.

## 완료 기준

3번 담당 작업은 다음 조건을 충족해야 완료로 판단한다.

- 메시지 조회 `limit` 기본 30·최대 100이 적용된다.
- 전체 count 없이 `hasMore` 또는 다음 cursor를 계산한다.
- sender/file 조회가 각각 batch 처리되고 메시지 수에 따라 Repository 호출이 선형 증가하지 않는다.
- `{ room: 1, timestamp: -1 }` 인덱스 사용이 `explain()`으로 확인된다.
- 페이지 간 메시지 중복·누락과 정렬 회귀가 없다.
- 공식 API·Socket·UI 계약이 유지된다.
- 관련 단위·통합 테스트가 통과한다.
- 개선 전후 히스토리 p95와 쿼리 수를 비교해 결과를 기록한다.
- 가능하면 공식 E2E 23개와 100명 Artillery 사전 부하에서 회귀가 없음을 확인한다.

## 작업 보고 형식

완료 보고에는 다음 내용을 포함한다.

1. 변경한 병목과 근거
2. 수정한 파일과 핵심 설계
3. 변경 전후 쿼리 수 및 성능 지표
4. 실행한 테스트와 결과
5. 실행하지 못한 검증 및 이유
6. 남은 조건부·후순위 작업
