# 메시지 히스토리 전체 count 쿼리 제거

## 1. 변경 목적

초기 메시지 응답에는 전체 메시지 수가 필요하지 않고, 다음 메시지가 더 있는지를 나타내는 `hasMore`만 필요하다.
기존 `Page<Message>` 조회는 전체 결과 수를 계산하기 위한 count 쿼리가 실행될 수 있으므로, 메시지가 많은 방과 동시 입장 요청에서 불필요한 MongoDB 비용이 발생할 수 있었다.

이번 변경은 외부 Socket 응답과 메시지 순서를 유지하면서 전체 count 없이 `hasMore`를 계산하도록 조회 방식을 변경한다.

## 2. 기존 동작

`MessageRepository.findByRoomIdAndTimestampBefore()`가 `Page<Message>`를 반환했다.
`MessageLoader`는 `Page.getContent()`로 메시지를 얻고 `Page.hasNext()`로 다음 페이지 존재 여부를 계산했다.

개념적인 쿼리 흐름은 다음과 같다.

```text
메시지 limit개 조회
전체 조건 결과 count 가능
Page가 count 결과로 hasMore 계산
```

전체 메시지 개수는 응답에 포함되지 않기 때문에 count 결과는 실제 계약에 필요하지 않았다.

## 3. 개선 동작

Repository 반환형을 `List<Message>`로 변경해 Spring Data가 `Page`를 만들기 위한 count를 수행하지 않게 했다.
요청 limit보다 하나 많은 `limit + 1`개를 DB에서 조회한다.

```text
요청 limit이 30이면 DB에서는 최대 31개 조회

0~30개 조회  → 모두 응답, hasMore=false
31개 조회     → 앞의 30개만 응답, hasMore=true
```

31번째 메시지는 다음 데이터가 존재하는지 확인하는 표식으로만 사용한다. 다음 처리에서는 제외된다.

- Socket 응답
- 읽음 상태 업데이트
- sender 일괄 조회
- file 일괄 조회

따라서 사용자가 요청한 개수보다 많은 메시지가 외부로 노출되거나, 확인용 메시지 때문에 추가 enrichment 비용이 발생하지 않는다.

## 4. 수정 파일

### `MessageRepository.java`

- 반환형을 `Page<Message>`에서 `List<Message>`로 변경했다.
- 필터, timestamp 내림차순 정렬과 DB limit 적용 방식은 유지한다.
- `List` 반환 조회이므로 페이지 전체 크기를 계산하는 count가 필요하지 않다.

### `MessageLoader.java`

- DB 조회 크기를 `limit + 1`로 설정했다.
- 조회 결과가 limit보다 크면 `hasMore=true`로 계산한다.
- 응답 대상은 최대 limit개로 먼저 자른다.
- 응답 대상만 시간 오름차순으로 바꾸고 읽음 처리와 sender/file 배치 조회를 수행한다.

### `MessageLoaderTest.java`

- 정확히 limit개이면 `hasMore=false`인지 검증한다.
- limit보다 하나 많은 결과이면 최대 limit개만 응답하고 `hasMore=true`인지 검증한다.
- Repository에 전달한 조회 크기가 31이고 timestamp 내림차순인지 검증한다.
- 31번째 확인용 메시지가 읽음 처리 대상에서 제외되는지 검증한다.

### `MessageLoaderIntegrationTest.java`

- 실제 MongoDB 프로파일러 기록으로 메시지 조회 명령을 확인한다.
- 요청 limit 30일 때 `find`의 limit이 31인지 검증한다.
- 해당 히스토리 조회에 `count` 또는 count용 `aggregate`가 발생하지 않는지 검증한다.
- 기존 100개 메시지의 30/30/30/10 페이지 조회와 정렬·`hasMore` 동작을 계속 검증한다.

## 5. 변경 전후 비교

```text
변경 전
메시지 목록 조회 1회
전체 count 조회 최대 1회
sender 배치 조회 최대 1회
file 배치 조회 최대 1회

변경 후
메시지 limit+1 목록 조회 1회
전체 count 조회 0회
sender 배치 조회 최대 1회
file 배치 조회 최대 1회
```

sender 또는 file ID가 없다면 해당 배치 조회도 실행하지 않는다.

## 6. 호환성

다음 외부 계약은 변경하지 않았다.

- Socket 이벤트 이름
- 응답 JSON 구조
- 요청 limit만큼의 최대 응답 개수
- 시간 오름차순 응답 순서
- `hasMore`의 의미
- sender/file 필드 매핑
- 읽음 처리 대상의 의미

## 7. 검증 결과

Java 25 환경에서 다음 테스트를 실행했다.

```powershell
./mvnw.cmd -Dtest=MessageLoaderTest test
./mvnw.cmd -Dtest=MessageLoaderIntegrationTest test
```

- `MessageLoaderTest`: 7개 통과
- `MessageLoaderIntegrationTest`: 5개 통과
- MongoDB 프로파일 결과: 메시지 `find` 1회, limit 31, count/aggregate 없음
- 전체 단위 회귀: 173개 중 172개 통과, 1개 오류
  - 오류는 이번 변경과 무관한 기존 `LocalStorageTest`의 Windows 임시 `photo.jpg` 파일 정리 실패이다.
  - 테스트 assertion 실패는 없었고 count 제거 관련 테스트는 모두 통과했다.

Artillery 부하 테스트는 대회 단계에서 수행하기로 한 현재 범위에 따라 이번 검증에서 제외한다.
