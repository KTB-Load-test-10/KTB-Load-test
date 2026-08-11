# 메시지 히스토리 room/timestamp 복합 인덱스 개선

## 1. 문서 목적

이 문서는 채팅방의 초기 메시지와 이전 메시지를 조회할 때 발생하던 MongoDB 전체 스캔과 정렬 문제를 설명하고, `{ room: 1, timestamp: -1 }` 복합 인덱스를 적용한 이유와 검증 결과를 기록한다.

이번 변경의 목표는 다음과 같다.

- 특정 채팅방의 메시지를 최신순으로 조회하는 쿼리에 맞는 인덱스를 제공한다.
- `COLLSCAN`과 별도 `SORT`를 제거한다.
- 반환 개수와 비슷한 수준으로 `totalDocsExamined`와 `totalKeysExamined`를 제한한다.
- 인덱스를 선언하는 데 그치지 않고 실제 `explain()` 결과로 사용 여부를 검증한다.
- 기존 Socket 이벤트, 메시지 정렬 의미 및 응답 형식을 유지한다.

## 2. 기존 메시지 조회 흐름

채팅방에 입장하거나 이전 메시지를 요청하면 `MessageLoader`가 `MessageRepository`를 호출한다.

```java
Page<Message> findByRoomIdAndTimestampBefore(
        String roomId,
        LocalDateTime timestamp,
        Pageable pageable
);
```

`MessageLoader`는 다음 Pageable을 전달한다.

```java
PageRequest.of(
        0,
        limit,
        Sort.by("timestamp").descending()
);
```

이를 MongoDB 쿼리 형태로 표현하면 다음과 같다.

```javascript
db.messages.find({
  room: "room-id",
  timestamp: { $lt: before }
})
.sort({ timestamp: -1 })
.limit(30)
```

쿼리의 의미는 다음과 같다.

1. `room`이 요청한 채팅방과 같은 메시지만 선택한다.
2. `before`보다 오래된 메시지만 선택한다.
3. `timestamp` 내림차순으로 최신 메시지부터 정렬한다.
4. 기본 30개, 최대 100개만 조회한다.

## 3. Java 필드와 MongoDB 필드의 차이

`Message` 모델의 Java 필드명은 `roomId`지만 MongoDB 문서에는 `room`으로 저장된다.

```java
@Field("room")
private String roomId;
```

따라서 실제 MongoDB 인덱스는 Java 필드명인 `roomId`가 아니라 저장 필드명인 `room`을 사용해야 한다.

```javascript
// 올바른 인덱스
{ room: 1, timestamp: -1 }

// 현재 MongoDB 문서 구조와 맞지 않는 인덱스
{ roomId: 1, timestamp: -1 }
```

## 4. 기존 상황의 문제

기존 `messages` 컬렉션에는 초기 히스토리 쿼리를 지원하는 복합 인덱스가 없었다. MongoDB는 기본 `_id` 인덱스만 사용할 수 있었지만, `_id`는 `room` 필터와 `timestamp` 정렬을 지원하지 않는다.

10,000개의 테스트 메시지를 사용해 인덱스 적용 전 `explain("executionStats")`을 실행한 결과는 다음과 같았다.

```text
실행 계획: COLLSCAN -> SORT
반환 문서: 30
totalDocsExamined: 10,000
totalKeysExamined: 0
로컬 executionTimeMillis: 9ms
```

MongoDB는 다음 순서로 처리하고 있었다.

```text
messages 컬렉션 전체 문서 검사
  -> room과 timestamp 조건에 맞는 문서 필터링
  -> 메모리에서 timestamp DESC 정렬
  -> 상위 30개 반환
```

즉 30개 메시지만 필요해도 10,000개 문서를 검사하고 별도 정렬을 수행했다.

### 4.1 데이터가 증가할 때의 영향

- 방과 메시지가 늘수록 검사 문서 수가 증가한다.
- 정렬 대상이 커지면서 CPU와 메모리 사용량이 증가한다.
- 여러 사용자가 동시에 채팅방에 입장하면 같은 비효율적 조회가 반복된다.
- 초기 메시지 로딩 p95와 MongoDB query p95가 악화될 수 있다.
- 큰 정렬은 메모리 제한과 디스크 사용 위험을 증가시킨다.

로컬의 작은 데이터에서는 실행 시간이 짧게 보일 수 있지만, `COLLSCAN`과 `totalDocsExamined` 증가는 데이터 증가에 따라 확대되는 구조적 문제다.

## 5. 인덱스 설계 이유

적용한 인덱스는 다음과 같다.

```javascript
{ room: 1, timestamp: -1 }
```

필드 순서는 실제 쿼리 형태에 맞춰 결정했다.

### 5.1 room을 첫 번째 필드로 사용

쿼리는 항상 특정 채팅방의 메시지를 조회하므로 `room`은 동등 조건이다.

```javascript
room: "room-id"
```

첫 번째 인덱스 필드로 `room`을 사용하면 MongoDB가 다른 채팅방의 메시지를 검사하지 않고 대상 방의 인덱스 범위로 바로 이동할 수 있다.

### 5.2 timestamp를 두 번째 필드로 사용

`timestamp`는 범위 조건과 정렬에 함께 사용된다.

```javascript
timestamp: { $lt: before }
sort: { timestamp: -1 }
```

`timestamp: -1`을 인덱스에 포함하면 MongoDB가 최신순으로 정렬된 인덱스 키를 따라가며 요청한 개수만 읽을 수 있다.

```text
특정 room 인덱스 범위 선택
  -> timestamp DESC 방향으로 인덱스 탐색
  -> limit 30에 도달하면 중단
```

별도의 메모리 정렬 단계가 필요하지 않다.

## 6. 프로젝트 인덱스 관리 방식

프로젝트에는 다음 설정이 활성화되어 있다.

```properties
spring.data.mongodb.auto-index-creation=true
```

또한 `Session` 모델 등에서 Spring Data MongoDB의 `@CompoundIndex`를 사용하고 있다. 따라서 별도 Mongo shell 배포 스크립트를 추가하지 않고 기존 관리 방식과 동일하게 모델 annotation을 사용했다.

## 7. 적용 내용

`Message` 모델에 이름 있는 복합 인덱스를 선언했다.

```java
@Document(collection = "messages")
@CompoundIndex(
        name = "room_timestamp_idx",
        def = "{'room': 1, 'timestamp': -1}"
)
public class Message {
    // ...
}
```

인덱스 이름을 명시하면 운영 환경과 테스트에서 어떤 인덱스가 사용됐는지 쉽게 확인할 수 있다.

애플리케이션 시작 시 Spring Data MongoDB가 다음 인덱스를 생성한다.

```javascript
{
  name: "room_timestamp_idx",
  key: {
    room: 1,
    timestamp: -1
  }
}
```

## 8. 적용 후 explain 결과

적용 전과 동일한 10,000개 테스트 데이터에서 복합 인덱스를 생성한 뒤 `explain("executionStats")`을 다시 실행했다.

```text
실행 계획: IXSCAN -> FETCH -> LIMIT
사용 인덱스: room_timestamp_idx
반환 문서: 30
totalKeysExamined: 30
totalDocsExamined: 30
별도 SORT: 없음
로컬 executionTimeMillis: 3ms
```

비교 결과는 다음과 같다.

| 측정 항목 | 적용 전 | 적용 후 |
|---|---:|---:|
| 실행 계획 | `COLLSCAN -> SORT` | `IXSCAN -> FETCH -> LIMIT` |
| 반환 문서 | 30 | 30 |
| `totalDocsExamined` | 10,000 | 30 |
| `totalKeysExamined` | 0 | 30 |
| 별도 `SORT` | 있음 | 없음 |
| 로컬 실행 시간 | 9ms | 3ms |

가장 중요한 결과는 단순 실행 시간보다 검사 문서 수와 실행 계획이다.

```text
totalDocsExamined: 10,000 -> 30
COLLSCAN + SORT -> IXSCAN, 별도 SORT 없음
```

실행 시간은 장비 상태와 캐시, 데이터 크기에 따라 달라질 수 있지만 검사량 감소와 인덱스 사용 여부는 실행 계획에서 직접 확인할 수 있다.

## 9. 자동 검증 테스트

`MessageLoaderIntegrationTest`에 실제 MongoDB를 사용하는 검증을 추가했다.

테스트는 다음 내용을 확인한다.

1. 애플리케이션 컨텍스트 시작 시 `room_timestamp_idx`가 자동 생성된다.
2. 인덱스 필드와 방향이 `{ room: 1, timestamp: -1 }`과 일치한다.
3. 초기 메시지 쿼리 실행 계획에 `IXSCAN`이 포함된다.
4. 사용된 인덱스 이름이 `room_timestamp_idx`다.
5. 별도 `SORT` 단계가 없다.
6. 30개 반환 시 검사 키와 문서가 최대 30개다.

검증 코드의 핵심 조건은 다음과 같다.

```java
assertThat(queryPlanner.toJson())
        .contains("IXSCAN", "room_timestamp_idx");

assertThat(queryPlanner.toJson())
        .doesNotContain("\"stage\": \"SORT\"");

assertThat(totalKeysExamined).isLessThanOrEqualTo(30L);
assertThat(totalDocsExamined).isLessThanOrEqualTo(30L);
```

## 10. 테스트 결과

Java 25와 Testcontainers MongoDB를 사용한 결과는 다음과 같다.

```text
MessageLoaderIntegrationTest: 4개 통과
관련 집중 단위 테스트: 16개 통과
```

전체 단위 회귀 테스트는 172개 중 171개가 통과했다. 오류 1개는 `LocalStorageTest`가 Windows에서 열려 있는 임시 `photo.jpg`를 삭제하지 못한 문제다.

```text
java.nio.file.FileSystemException:
photo.jpg: 다른 프로세스가 파일을 사용 중이기 때문에 액세스할 수 없습니다
```

이 오류는 메시지 조회나 MongoDB 인덱스와 관련이 없다.

공식 Playwright E2E는 이전 단계에서 확인된 Next/Turbopack `Internal Server Error` 때문에 이번 인덱스 단계에서는 완료하지 못했다.

수동 `explain()` 검증에 사용한 격리 데이터베이스 `codex_message_index_test`는 측정 후 삭제했다.

## 11. 외부 계약과 기능 영향

이번 변경은 MongoDB의 물리적인 조회 방법만 개선한다. 다음 동작은 변경하지 않는다.

- Repository 메서드 이름과 반환 타입
- Socket.IO 이벤트 이름
- `FetchMessagesResponse`와 `MessageResponse` JSON 구조
- 메시지 조회 기본값 30과 최대값 100
- 최신 메시지를 DB에서 내림차순으로 조회한 뒤 UI에 오름차순으로 반환하는 동작
- `hasMore` 계산 방식
- cursor로 사용하는 `before`의 의미
- sender/file batch 조회 방식

따라서 프론트엔드 변경은 필요하지 않다.

## 12. 비용과 운영 시 주의사항

인덱스는 조회 성능을 높이는 대신 다음 비용을 가진다.

- 메시지 저장 시 인덱스 키도 함께 갱신해야 한다.
- MongoDB 디스크와 메모리를 추가로 사용한다.
- 기존 데이터가 많은 운영 컬렉션에서 최초 인덱스 생성 시 일시적인 CPU와 I/O 부하가 발생할 수 있다.

초기 메시지 조회는 방 입장마다 반복되고, 인덱스 필드도 작은 `room`과 `timestamp`이므로 현재 쿼리에서는 조회 이점이 쓰기 비용보다 크다고 판단했다.

운영 적용 전에는 트래픽이 낮은 시간에 인덱스 생성 시간을 확인하고 MongoDB CPU, I/O 및 복제 지연을 관찰하는 것이 좋다.

## 13. 향후 다시 검토해야 하는 경우

sender/file batch 매핑만 변경하는 것은 이 인덱스와 무관하다. 그러나 메시지 조회 조건이나 정렬이 변경되면 `explain()`을 다시 수행해야 한다.

특히 동일 timestamp 메시지의 cursor 누락을 방지하기 위해 다음과 같은 보조 정렬을 추가할 수 있다.

```javascript
sort({ timestamp: -1, _id: -1 })
```

쿼리가 timestamp와 `_id`를 함께 사용하도록 변경되면 다음 인덱스를 검토해야 한다.

```javascript
{ room: 1, timestamp: -1, _id: -1 }
```

다음 변경도 인덱스 재검증 대상이다.

- room 외의 추가 필터 조건 도입
- timestamp 정렬 방향 변경
- cursor 조건 변경
- aggregation pipeline으로 조회 방식 변경
- 고정된 힌트나 partial index 도입

인덱스를 추가했다는 사실만으로 성능 개선이 계속 보장되지는 않는다. 실제 쿼리 형태가 달라질 때마다 `winningPlan`, `totalDocsExamined`, `totalKeysExamined`와 별도 `SORT` 유무를 다시 확인해야 한다.
