# 메시지 히스토리 sender/file 배치 조회 개선

## 1. 문서 목적

이 문서는 채팅방의 초기 메시지와 이전 메시지를 불러올 때 발생하던 sender/file N+1 조회 문제와 이를 배치 조회로 개선한 내용을 설명한다.

이번 변경의 목표는 다음과 같다.

- 메시지마다 반복되던 사용자 조회를 요청당 최대 한 번으로 줄인다.
- 파일이 첨부된 메시지마다 반복되던 파일 조회를 요청당 최대 한 번으로 줄인다.
- 메시지 순서와 Socket 응답 형식을 유지한다.
- 삭제되었거나 존재하지 않는 sender/file 참조를 안전하게 처리한다.

이번 단계에서는 `Page`로 인한 count 쿼리, cursor 경계, 읽음 상태 갱신 및 MongoDB 인덱스는 변경하지 않는다.

## 2. 메시지 응답 생성 흐름

메시지 히스토리를 조회하면 서버는 MongoDB의 `Message` 문서를 바로 반환하지 않는다. 각 메시지에 발신자와 첨부 파일 정보를 결합해 `MessageResponse`를 만든다.

```text
Message 조회
  -> sender ID로 User 조회
  -> file ID로 File 조회
  -> MessageResponse 생성
  -> Socket.IO 응답 전송
```

메시지 문서에는 사용자와 파일 전체 정보가 아니라 참조 ID만 저장된다.

```text
Message.senderId -> User.id
Message.fileId   -> File.id
```

따라서 응답에 발신자 이름, 이메일 및 파일 이름 등을 포함하려면 참조 엔티티를 추가로 조회해야 한다.

## 3. 기존 구현의 문제

### 3.1 sender 조회

기존 `MessageLoader`는 메시지를 응답으로 변환하는 반복문 안에서 사용자 Repository를 호출했다.

```java
List<MessageResponse> messageResponses = sortedMessages.stream()
        .map(message -> {
            var user = findUserById(message.getSenderId());
            return messageResponseMapper.mapToMessageResponse(message, user);
        })
        .collect(Collectors.toList());
```

`findUserById()`는 메시지마다 `userRepository.findById()`를 실행했다.

```java
private User findUserById(String id) {
    if (id == null) {
        return null;
    }
    return userRepository.findById(id).orElse(null);
}
```

같은 사용자가 작성한 메시지 30개를 불러오더라도 동일한 사용자 정보를 30번 조회할 수 있었다.

### 3.2 file 조회

기존 `MessageResponseMapper`는 메시지를 변환하면서 파일 Repository를 직접 호출했다.

```java
Optional.ofNullable(message.getFileId())
        .flatMap(fileRepository::findById)
        .map(file -> FileResponse.builder()
                .id(file.getId())
                .filename(file.getFilename())
                .build())
        .ifPresent(builder::file);
```

파일이 첨부된 메시지가 10개이면 파일 조회도 최대 10번 발생했다.

### 3.3 N+1 문제

N+1은 목록 하나를 조회한 뒤 목록의 각 항목을 완성하기 위해 추가 쿼리를 반복하는 문제다.

메시지 30개 중 파일 메시지가 10개라면 enrichment 조회는 다음처럼 증가할 수 있었다.

```text
메시지 목록 조회: 1회
sender 조회: 최대 30회
file 조회: 최대 10회
```

여기에 현재 `Page`의 count 쿼리와 읽음 상태 처리가 별도로 추가될 수 있다. 이번 문서의 조회 횟수 비교는 sender/file enrichment 부분만 대상으로 한다.

### 3.4 발생 가능한 영향

- 초기 메시지 개수에 비례해 MongoDB 왕복 횟수가 증가한다.
- 동일한 sender/file을 불필요하게 반복 조회한다.
- 동시 입장 사용자가 늘면 MongoDB connection 사용량이 커진다.
- 네트워크 왕복 지연이 누적되어 초기 히스토리 p95가 악화될 수 있다.
- 매퍼가 Repository에 의존하므로 데이터 조회와 DTO 변환 책임이 섞인다.

## 4. 개선 설계

변경 후에는 메시지 목록 전체에서 참조 ID를 먼저 수집한다.

```text
Message 목록 조회
  -> sender ID 중복 제거
  -> file ID 중복 제거
  -> User 일괄 조회 1회
  -> File 일괄 조회 1회
  -> ID 기반 Map 구성
  -> Map에서 sender/file을 찾아 MessageResponse 생성
```

DB 접근은 `MessageLoader`가 담당하고, `MessageResponseMapper`는 이미 조회된 엔티티를 DTO로 변환하는 역할만 담당한다.

## 5. 변경 내용

### 5.1 sender ID 수집 및 일괄 조회

조회한 메시지에서 null과 빈 문자열을 제외한 sender ID를 `Set`으로 수집한다.

```java
Set<String> senderIds = messages.stream()
        .map(Message::getSenderId)
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toSet());
```

`Set`을 사용하므로 같은 사용자가 여러 메시지를 작성해도 ID는 한 번만 포함된다.

ID가 있을 때만 `findAllById()`를 한 번 호출하고, 조회 결과를 Map으로 변환한다.

```java
return userRepository.findAllById(senderIds).stream()
        .collect(Collectors.toMap(User::getId, Function.identity()));
```

### 5.2 file ID 수집 및 일괄 조회

파일도 같은 방식으로 중복 ID를 제거한 뒤 한 번에 조회한다.

```java
Set<String> fileIds = messages.stream()
        .map(Message::getFileId)
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toSet());

return fileRepository.findAllById(fileIds).stream()
        .collect(Collectors.toMap(File::getId, Function.identity()));
```

sender/file ID가 하나도 없으면 Repository를 호출하지 않고 빈 Map을 반환한다.

### 5.3 Map 기반 메시지 변환

응답을 만들 때는 Repository 대신 ID 기반 Map을 사용한다.

```java
messageResponseMapper.mapToMessageResponse(
        message,
        getByNullableId(usersById, message.getSenderId()),
        getByNullableId(filesById, message.getFileId()))
```

Map 조회는 일반적으로 상수 시간에 처리되므로 메시지 수가 늘어도 추가 DB 왕복은 발생하지 않는다.

### 5.4 null ID 처리

`Map.of()`로 만든 빈 Map은 `get(null)` 호출 시 예외가 발생할 수 있다. 파일이 없는 일반 메시지와 sender가 없는 시스템/AI 메시지를 안전하게 처리하기 위해 null ID는 Map 조회를 생략한다.

```java
private static <T> T getByNullableId(Map<String, T> valuesById, String id) {
    return id == null ? null : valuesById.get(id);
}
```

### 5.5 삭제된 참조 처리

메시지에 sender/file ID가 남아 있지만 실제 User 또는 File 문서가 삭제되었을 수 있다.

`findAllById()` 결과에 해당 ID가 없으면 Map 조회 결과가 null이 된다. 이 경우 전체 메시지 조회를 실패시키지 않고 기존 계약대로 sender 또는 file 필드만 생략한다.

```text
Message.senderId = "deleted-user"
User 조회 결과 없음
  -> MessageResponse.sender = null
  -> 다른 메시지는 정상 반환
```

### 5.6 MessageResponseMapper 책임 분리

기존 매퍼가 가지고 있던 `FileRepository` 의존성을 제거했다. 새 3인자 메서드는 조회가 끝난 `User`와 `File`을 전달받는다.

```java
mapToMessageResponse(Message message, User sender, File file)
```

기존 방 입장·퇴장 시스템 메시지 호출부의 호환성을 위해 2인자 메서드는 유지한다.

```java
public MessageResponse mapToMessageResponse(Message message, User sender) {
    return mapToMessageResponse(message, sender, null);
}
```

## 6. 조회 횟수 비교

sender가 모두 동일한 메시지 30개 중 파일 메시지가 10개인 경우를 예로 들면 다음과 같다.

| enrichment 조회 | 변경 전 | 변경 후 |
|---|---:|---:|
| User | 최대 30회 | 1회 |
| File | 최대 10회 | 1회 |
| 합계 | 최대 40회 | 최대 2회 |

서로 다른 sender/file이 여러 개 있어도 `findAllById()`가 ID 목록을 한 번에 처리하므로 Repository 메서드 호출 횟수는 각각 최대 한 번이다.

```text
변경 전: O(메시지 수 + 파일 메시지 수)의 Repository 호출
변경 후: O(1)의 Repository 호출, 최대 2회
```

여기서 O(1)은 Repository 호출 횟수를 의미한다. 실제 MongoDB가 읽는 문서 수와 애플리케이션의 Map 구성 비용은 고유 sender/file 수에 따라 달라진다.

## 7. 외부 계약에 미치는 영향

다음 항목은 변경하지 않았다.

- Socket.IO 이벤트 이름
- `MessageResponse` JSON 필드와 의미
- 메시지의 오름차순 표시 순서
- `hasMore` 계산 방식
- sender의 ID, 이름, 이메일 및 프로필 이미지
- file의 ID, 파일명, 원본 파일명, MIME type 및 크기
- 존재하지 않는 참조를 필드 생략으로 처리하는 동작

따라서 프론트엔드는 별도 변경 없이 기존 응답을 그대로 사용할 수 있다.

## 8. 수정 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java` | sender/file ID 수집, batch 조회, Map 기반 응답 매핑 |
| `src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageResponseMapper.java` | FileRepository 제거 및 조회된 File 매핑 지원 |
| `src/test/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoaderTest.java` | 중복, 다중, 삭제 참조 및 빈 목록 테스트 추가 |
| `src/test/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoaderIntegrationTest.java` | 변경된 생성자 의존성 반영 |

`UserRepository`와 `FileRepository`는 `MongoRepository`에서 상속받은 `findAllById()`를 사용하므로 별도 메서드를 추가하지 않았다.

## 9. 테스트 사례

다음 동작을 검증하는 단위 테스트를 추가했다.

1. 여러 메시지가 같은 sender/file을 참조해도 ID가 중복 제거된다.
2. `findAllById()`가 user/file에 각각 정확히 한 번 호출된다.
3. 여러 sender/file이 각 메시지에 올바르게 매핑된다.
4. 메시지별 `findById()`가 호출되지 않는다.
5. 삭제된 sender/file 참조는 응답에서 해당 필드만 생략된다.
6. 빈 메시지 목록에서는 user/file Repository를 호출하지 않는다.
7. 기존 메시지 정렬과 `hasMore` 동작을 유지한다.

## 10. 검증 상태

Java 25에서 운영 코드와 테스트 코드 컴파일은 성공했다.

첫 집중 테스트에서는 파일 ID가 null인 메시지가 빈 불변 Map을 조회하면서 NPE가 발생하는 경계 문제를 발견했다. 이후 null ID가 Map 조회를 수행하지 않도록 수정했다.

수정 후 Maven 재실행은 사용자 권한 승인에도 불구하고 실행 권한 검토 서비스의 처리 용량 문제로 차단됐다. 따라서 현재 검증 상태는 다음과 같다.

| 검증 항목 | 상태 |
|---|---|
| Java 25 운영·테스트 코드 컴파일 | 성공 |
| 새 batch 테스트 최초 실행 | 실행됨, null ID 문제 발견 |
| null ID 문제 수정 | 완료 |
| 수정 후 집중 테스트 재실행 | 미실행: 실행 권한 검토 차단 |
| `MessageLoaderIntegrationTest` | 미실행 |
| 공식 Playwright 히스토리 E2E | 미실행 |

권한 실행이 가능해지면 다음 명령을 다시 수행해야 한다.

```powershell
Set-Location apps/backend
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
./mvnw.cmd "-Dtest=FetchMessagesRequestTest,MessageLoaderTest,RoomJoinHandlerTest,RoomLeaveHandlerTest" test
./mvnw.cmd "-Dtest=MessageLoaderIntegrationTest" test
```

통합 환경이 준비되면 저장소 루트에서 공식 E2E도 실행한다.

```powershell
pnpm test:e2e
```

채팅 히스토리 전체 시나리오는 메시지 61개를 작성한 뒤 페이지를 새로고침하고, 화면 상단으로 반복 스크롤해 모든 이전 메시지가 로딩되는지 확인한다.

## 11. 남은 작업

이번 batch 개선 이후에도 다음 작업이 남아 있다.

1. `Page`와 전체 count 쿼리 제거
2. `limit + 1` 방식으로 `hasMore` 계산
3. cursor 경계의 중복·누락 검증
4. 동일 timestamp를 위한 안정적인 보조 정렬 검토
5. `{ room: 1, timestamp: -1 }` 인덱스 적용 및 `explain()` 검증
6. 개선 전후 MongoDB 쿼리 수와 초기 히스토리 p95 비교

읽음 상태 bulk update와 추가 인덱스는 실제 측정에서 필요성이 확인될 때만 후순위로 진행한다.
