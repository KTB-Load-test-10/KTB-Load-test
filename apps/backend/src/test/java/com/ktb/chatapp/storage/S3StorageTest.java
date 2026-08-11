package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.util.StreamUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3Storage 단위 테스트")
class S3StorageTest {

    private static final String BUCKET = "ktb-chat-test";
    private static final String PREFIX = "competition";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3Storage storage;

    @BeforeEach
    void setUp() {
        storage = new S3Storage(s3Client, s3Presigner, BUCKET, PREFIX);
    }

    @Test
    @DisplayName("put()은 prefix가 적용된 key와 content type으로 객체를 저장한다")
    void put_storesObjectWithPrefixAndContentType() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.put(
                new ByteArrayInputStream(bytes), "chat/photo.jpg", "image/jpeg", bytes.length);

        ArgumentCaptor<PutObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo("competition/chat/photo.jpg");
        assertThat(request.contentType()).isEqualTo("image/jpeg");
        assertThat(stored).isEqualTo(new StoredObject("chat/photo.jpg", bytes.length));
    }

    @Test
    @DisplayName("open()은 S3 응답 스트림을 Resource로 노출한다")
    void open_returnsStreamingResource() throws Exception {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) bytes.length)
                .build();
        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                response,
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        Optional<Resource> result = storage.open("profiles/avatar.jpg");

        ArgumentCaptor<GetObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo("competition/profiles/avatar.jpg");
        assertThat(result).isPresent();
        assertThat(result.get().getFilename()).isEqualTo("avatar.jpg");
        assertThat(result.get().contentLength()).isEqualTo(bytes.length);
        assertThat(StreamUtils.copyToString(result.get().getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo("hello");
    }

    @Test
    @DisplayName("open()은 존재하지 않는 객체에 빈 Optional을 반환한다")
    void open_returnsEmptyForMissingObject() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(
                NoSuchKeyException.builder().statusCode(404).message("missing").build());

        assertThat(storage.open("chat/missing.jpg")).isEmpty();
    }

    @Test
    @DisplayName("delete()는 동일한 physical key를 삭제한다")
    void delete_removesPhysicalObject() {
        storage.delete("chat/to-delete.jpg");

        ArgumentCaptor<DeleteObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo("competition/chat/to-delete.jpg");
    }

    @Test
    @DisplayName("offloadUrl()은 disposition과 TTL이 포함된 presigned GET URL을 만든다")
    void offloadUrl_presignsAuthorizedGet() throws Exception {
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://signed.example.test/object").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
        Duration ttl = Duration.ofMinutes(5);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename("사진.jpg", StandardCharsets.UTF_8)
                .build();

        URI result = storage.offloadUrl("chat/photo.jpg", ttl, disposition).orElseThrow();

        ArgumentCaptor<GetObjectPresignRequest> requestCaptor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(requestCaptor.capture());
        GetObjectPresignRequest request = requestCaptor.getValue();
        assertThat(request.signatureDuration()).isEqualTo(ttl);
        assertThat(request.getObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(request.getObjectRequest().key()).isEqualTo("competition/chat/photo.jpg");
        assertThat(request.getObjectRequest().responseContentDisposition())
                .isEqualTo(disposition.toString());
        assertThat(result).isEqualTo(URI.create("https://signed.example.test/object"));
    }

    @Test
    @DisplayName("경로 순회 및 계약 밖 key는 모든 작업에서 거부한다")
    void operations_rejectUnsafeOrUnknownKeys() {
        assertThatThrownBy(() -> storage.put(
                new ByteArrayInputStream(new byte[0]), "../../secret", "text/plain", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("other/file.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("/chat/file.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("S3 모드는 bucket 누락을 시작 시점에 거부한다")
    void constructor_rejectsMissingBucket() {
        assertThatThrownBy(() -> new S3Storage(s3Client, s3Presigner, " ", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_BUCKET");
    }
}
