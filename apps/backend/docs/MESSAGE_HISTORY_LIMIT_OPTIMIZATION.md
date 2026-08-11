# 메시지 히스토리 조회량 제한 개선

## 1. 문서 목적

이 문서는 채팅방의 메시지 히스토리를 조회할 때 적용한 `limit` 정책의 배경과 구현 내용을 설명한다.

이번 변경의 목표는 다음과 같다.

- 기본 조회량을 30개로 유지한다.
- 한 번에 조회할 수 있는 메시지를 최대 100개로 제한한다.
- 누락되거나 유효하지 않은 `limit`을 안전하게 처리한다.
- 기존 Socket 이벤트와 응답 계약을 변경하지 않는다.

이번 문서는 메시지 히스토리 성능 개선 작업 중 첫 번째 단계만 다룬다. 전체 count 제거, sender/file N+1 제거 및 MongoDB 인덱스 검증은 후속 단계에서 진행한다.

## 2. 기존 메시지 조회 흐름

사용자가 채팅방에 들어가거나 이전 메시지를 요청하면 다음 흐름으로 처리된다.

```text
클라이언트
  -> FetchMessagesRequest 수신
  -> MessageLoader.loadMessages()
  -> MongoDB 메시지 조회
  -> MessageResponse 변환
  -> 클라이언트에 FetchMessagesResponse 전달
```

`FetchMessagesRequest`는 다음 값을 전달한다.

| 필드 | 의미 |
|---|---|
| `roomId` | 메시지를 조회할 채팅방 ID |
| `limit` | 한 번에 가져올 메시지 개수 |
| `before` | 이 시점보다 오래된 메시지를 조회하기 위한 기준 시각 |

예를 들어 다음 요청은 특정 방의 최신 메시지 30개를 요청한다.

```text
roomId = "room-1"
limit = 30
before = null
```

`before`가 없으면 현재 시각을 기준으로 초기 메시지를 조회한다.

## 3. 기존 구현의 문제

기존 `FetchMessagesRequest`의 `limit` 처리 방식은 다음과 같았다.

```java
public int limit(int defaultLimit) {
    return limit != null && limit > 0 ? limit : defaultLimit;
}
```

이 코드는 `null`, 0, 음수를 기본값으로 바꿔주지만 양수에는 상한을 적용하지 않았다.

| 요청한 `limit` | 기존 처리 결과 |
|---:|---:|
| `null` | 30 |
| 0 | 30 |
| -1 | 30 |
| 30 | 30 |
| 100 | 100 |
| 100,000 | 100,000 |

따라서 잘못된 클라이언트나 임의로 만든 Socket 요청이 매우 큰 값을 전달하면 서버가 그만큼의 메시지를 조회하려고 시도할 수 있었다.

### 3.1 발생 가능한 영향

조회량이 과도하게 커지면 다음 비용이 함께 증가한다.

- MongoDB가 읽고 반환해야 하는 문서 수
- 애플리케이션 메모리에 생성되는 메시지 객체 수
- 메시지를 응답 DTO로 변환하는 작업량
- sender 및 file 참조를 조회하는 횟수와 비용
- Socket 응답 payload 크기
- 네트워크 전송 시간
- 서버 CPU, JVM heap 및 GC 부담
- 사용자가 채팅방에 입장할 때 경험하는 초기 로딩 시간

기존 코드에는 메시지별 sender/file 조회 문제도 남아 있으므로, 무제한 조회는 N+1 문제의 영향을 더 크게 만들 수 있다.

## 4. 서버에서 제한해야 하는 이유

공식 프론트엔드가 항상 30을 전송하더라도 서버는 요청값을 독립적으로 검증해야 한다.

클라이언트 요청값은 다음 이유로 달라질 수 있다.

- 프론트엔드 구현 오류
- 이전 버전 클라이언트 사용
- 브라우저 개발자 도구를 통한 요청 변조
- 별도의 Socket.IO 클라이언트 사용
- 비정상적이거나 악의적인 요청

따라서 DB 조회에 사용하기 전에 서버가 `limit`을 안전한 범위로 정규화한다.

## 5. 변경 내용

### 5.1 조회 정책 상수화

`FetchMessagesRequest`에 다음 정책을 추가했다.

```java
public static final int DEFAULT_LIMIT = 30;
public static final int MAX_LIMIT = 100;
```

기본값과 최대값을 요청 DTO에 명시해 조회 정책을 한곳에서 관리한다.

### 5.2 요청값 정규화

변경된 로직은 다음 규칙을 적용한다.

```java
int normalizedDefault = defaultLimit > 0
        ? Math.min(defaultLimit, MAX_LIMIT)
        : DEFAULT_LIMIT;

if (limit == null || limit <= 0) {
    return normalizedDefault;
}
return Math.min(limit, MAX_LIMIT);
```

| 요청한 `limit` | 변경 후 결과 | 처리 의미 |
|---:|---:|---|
| `null` | 30 | 누락된 값은 기본값 사용 |
| 0 | 30 | 유효하지 않은 값은 기본값 사용 |
| -1 | 30 | 유효하지 않은 값은 기본값 사용 |
| 30 | 30 | 정상 범위 그대로 사용 |
| 100 | 100 | 허용되는 최대값 |
| 101 | 100 | 최대값으로 제한 |
| 100,000 | 100 | 최대값으로 제한 |

최대값 초과 요청에 오류를 반환하는 대신 100으로 제한했다. 이 방식은 기존 Socket 이벤트의 성공·실패 의미와 응답 형식을 바꾸지 않으므로 기존 소비자와의 호환성을 유지한다.

### 5.3 MessageLoader 적용

`MessageLoader`는 정규화된 조회량을 사용한다.

```java
data.limit(FetchMessagesRequest.DEFAULT_LIMIT)
```

정규화된 값은 MongoDB 조회에 사용되는 `PageRequest`의 크기로 전달된다.

```java
PageRequest.of(0, limit, Sort.by("timestamp").descending());
```

따라서 클라이언트가 `limit=100000`을 전송하더라도 실제 조회 크기는 최대 100이 된다.

```text
클라이언트 요청: limit=100000
  -> FetchMessagesRequest 정규화: limit=100
  -> PageRequest 크기: 100
  -> MongoDB 조회량: 최대 100개
```

## 6. 수정 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/ktb/chatapp/dto/FetchMessagesRequest.java` | 기본값 30, 최대값 100 정책과 정규화 로직 추가 |
| `src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java` | DTO에 정의된 기본 조회량을 사용하도록 변경 |
| `src/test/java/com/ktb/chatapp/dto/FetchMessagesRequestTest.java` | 누락, 음수 및 최대값 경계 테스트 추가 |

## 7. 테스트 사례

다음 경계값을 단위 테스트로 작성했다.

```text
null -> 30
0    -> 30
-1   -> 30
30   -> 30
100  -> 100
101  -> 100
```

이 테스트는 다음 회귀를 방지한다.

- 누락된 값에서 예외가 발생하는 문제
- 0 또는 음수가 DB 조회 크기로 전달되는 문제
- 최대값 100이 정상적으로 허용되지 않는 문제
- 100을 초과한 값이 그대로 DB 조회에 전달되는 문제

## 8. 검증 상태

단위 테스트 코드는 작성했으나 현재 로컬 환경에서는 Maven 테스트를 완료하지 못했다.

```text
프로젝트 요구 버전: Java 25
현재 실행 환경: Java 21
컴파일 오류: release version 25 not supported
```

이는 이번 변경 코드의 테스트 실패가 아니라, Maven 컴파일 단계에서 사용하는 JDK가 프로젝트의 `java.version`을 지원하지 않아 발생한 환경 문제다. Java 25 환경에서 다음 집중 테스트를 다시 실행해야 한다.

```powershell
Set-Location apps/backend
./mvnw "-Dtest=FetchMessagesRequestTest,MessageLoaderTest" test
```

## 9. 이번 단계에서 해결된 것

이번 변경으로 메시지 히스토리의 단일 요청 조회량이 다음과 같이 제한된다.

```text
변경 전: 양수이면 사실상 무제한
변경 후: 기본 30, 최대 100
```

이를 통해 비정상적으로 큰 요청 하나가 DB 조회량, 애플리케이션 메모리 및 응답 payload를 무제한으로 증가시키는 문제를 방지한다.

## 10. 아직 남은 작업

조회량 제한만으로 메시지 초기 조회 최적화가 완료되는 것은 아니다. 다음 작업이 후속 단계로 남아 있다.

1. `Page` 사용으로 발생할 수 있는 전체 count 쿼리 제거
2. `limit + 1` 조회를 통한 `hasMore` 계산
3. sender ID를 수집한 뒤 사용자 정보를 한 번에 조회해 N+1 제거
4. file ID를 수집한 뒤 파일 정보를 한 번에 조회해 N+1 제거
5. `{ room: 1, timestamp: -1 }` 복합 인덱스 적용 및 `explain()` 검증
6. 동일 timestamp 및 cursor 경계에서 페이지 간 중복·누락 검증
7. 초기 히스토리 p50/p95/p99, 쿼리 수 및 payload 크기 비교

후속 변경에서도 Socket 이벤트 이름, 메시지 정렬 순서, 응답 형식 및 UI 동작은 유지해야 한다.
