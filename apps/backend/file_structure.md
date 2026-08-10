# 프로젝트 구조

```text
src/main/java/com/ktb/chatapp
├── annotation     # 커스텀 어노테이션 정의
├── config         # 애플리케이션 주요 설정 (보안, DB, 웹 등)
├── controller     # REST API 엔드포인트
├── dto            # 데이터 전송 객체 (Data Transfer Objects)
├── event          # 애플리케이션 이벤트 리스너
├── exception      # 커스텀 예외 클래스
├── model          # 도메인 모델 및 MongoDB 엔티티
├── repository     # 데이터베이스 접근 계층 (MongoDB Repositories)
├── security       # JWT 처리 등 보안 관련 로직
├── service        # 비즈니스 로직
├── storage        # 파일 저장 관련 로직
├── util           # 유틸리티 클래스
├── validation     # 커스텀 유효성 검증 로직
├── websocket      # Socket.IO 관련 설정 및 핸들러
└── ChatAppApplication.java # Spring Boot 메인 애플리케이션
```

### 주요 패키지 상세 설명

#### `controller`
RESTful API 엔드포인트를 정의합니다. 클라이언트의 HTTP 요청을 받아 처리하고 적절한 응답을 반환합니다.

- `AuthController.java`: 회원가입, 로그인 등 인증 관련 API
- `FileController.java`: 파일 업로드/다운로드 API
- `RoomController.java`: 채팅방 생성, 조회, 참여 등 채팅방 관련 API
- `MessageController.java`: 메시지 조회 등 메시지 관련 API
- `UserController.java`: 사용자 정보 조회 및 수정 API
- `HealthController.java`: 서비스 상태 체크 API
- `ApiInfoController.java`: API 버전 등 정보 제공 API

#### `service`
애플리케이션의 핵심 비즈니스 로직을 담당합니다.

- `UserService.java`: 사용자 관련 비즈니스 로직 처리
- `RoomService.java`: 채팅방 관련 비즈니스 로직 처리
- `MessageReadStatusService.java`: 메시지 읽음 상태 처리
- `JwtService.java`: JWT 토큰 생성 및 검증
- `FileService.java`: 파일 관련 비즈니스 로직 (LocalFileService 구현체)
- `RateLimitService.java`: API 요청 제한 로직

#### `repository`
MongoDB 데이터베이스와 상호작용하는 인터페이스를 정의합니다.

- `UserRepository.java`: `User` 엔티티에 대한 CRUD
- `RoomRepository.java`: `Room` 엔티티에 대한 CRUD
- `MessageRepository.java`: `Message` 엔티티에 대한 CRUD
- `FileRepository.java`: `File` 엔티티에 대한 CRUD
- `SessionRepository.java`: `Session` 엔티티에 대한 CRUD
- `RateLimitRepository.java`: `RateLimit` 엔티티에 대한 CRUD

#### `model`
애플리케이션의 데이터 모델(도메인 객체) 및 MongoDB Document 엔티티를 정의합니다.

- `User.java`: 사용자 정보
- `Room.java`: 채팅방 정보
- `Message.java`: 채팅 메시지 정보
- `File.java`: 업로드된 파일 정보
- `Session.java`: 사용자 세션 정보
- `RateLimit.java`: API 요청 제한 정보

#### `config`
애플리케이션의 주요 설정을 담당하는 클래스들을 포함합니다.

- `SecurityConfig.java`: Spring Security 설정 (HTTP 보안, CORS 등)
- `SocketIOConfig.java`: Netty-Socket.IO 서버 설정
- `MongoConfig.java`: MongoDB 관련 설정
- `OpenApiConfig.java`: Swagger (OpenAPI) 문서 설정
- `JwtConfig.java`: JWT 관련 설정 값 로드
- `WebMvcConfig.java`: 웹 MVC 관련 설정 (인터셉터 등)

#### `security`
인증 및 인가와 관련된 로직을 포함합니다.

- `JwtAuthenticationFilter.java`: 각 요청에 대해 JWT 토큰을 검증하는 필터
- `UserDetailsServiceImpl.java`: Spring Security의 `UserDetailsService` 구현체

#### `websocket`
Socket.IO를 이용한 실시간 통신을 처리합니다.

- `SocketIOManager.java`: Socket.IO 서버를 관리하고 이벤트를 처리하는 핸들러 등록
- `SocketEvents.java`: Socket.IO 이벤트 이름 상수 정의
