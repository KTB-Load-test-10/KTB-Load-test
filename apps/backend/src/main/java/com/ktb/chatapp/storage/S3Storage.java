package com.ktb.chatapp.storage;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * 모든 백엔드 인스턴스가 공유하는 private S3 저장소.
 *
 * <p>논리 key({@code profiles/...}, {@code chat/...})는 DB 계약으로 유지하고, 선택적인
 * 배포 prefix는 S3에 요청할 때만 앞에 붙인다. 채팅 첨부는 애플리케이션의 참가자 인가를
 * 먼저 거친 뒤 짧은 presigned GET URL로 오프로딩된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;
    private final String prefix;

    public S3Storage(
            S3Client s3Client,
            S3Presigner s3Presigner,
            @Value("${file.storage.s3.bucket:}") String bucket,
            @Value("${file.storage.s3.prefix:}") String prefix) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = requiredBucket(bucket);
        this.prefix = normalizePrefix(prefix);
    }

    @PostConstruct
    void logConfiguration() {
        String configuredPrefix = prefix.isEmpty() ? "<none>" : prefix;
        log.info("S3 storage selected - bucket: {}, prefix: {}", bucket, configuredPrefix);
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        if (content == null) {
            throw new IllegalArgumentException("저장할 파일 내용이 없습니다.");
        }
        if (size < 0) {
            throw new IllegalArgumentException("파일 크기는 음수일 수 없습니다.");
        }

        String objectKey = objectKey(key);
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey);
        if (StringUtils.hasText(contentType)) {
            request.contentType(contentType);
        }

        try {
            s3Client.putObject(request.build(), RequestBody.fromInputStream(content, size));
            return new StoredObject(key, size);
        } catch (S3Exception ex) {
            throw storageFailure("파일 저장", objectKey, ex);
        }
    }

    @Override
    public Optional<Resource> open(String key) {
        String objectKey = objectKey(key);
        try {
            ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return Optional.of(new S3ObjectResource(stream, StorageKey.nameOf(key)));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw storageFailure("파일 조회", objectKey, ex);
        }
    }

    @Override
    public void delete(String key) {
        String objectKey = objectKey(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (S3Exception ex) {
            throw storageFailure("파일 삭제", objectKey, ex);
        }
    }

    @Override
    public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("presigned URL TTL은 양수여야 합니다.");
        }
        if (disposition == null) {
            throw new IllegalArgumentException("Content-Disposition이 필요합니다.");
        }

        String objectKey = objectKey(key);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .responseContentDisposition(disposition.toString())
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(request)
                .build();

        try {
            return Optional.of(s3Presigner.presignGetObject(presignRequest).url().toURI());
        } catch (Exception ex) {
            throw new RuntimeException("파일 접근 URL 생성에 실패했습니다: " + objectKey, ex);
        }
    }

    private String objectKey(String logicalKey) {
        validateLogicalKey(logicalKey);
        return prefix.isEmpty() ? logicalKey : prefix + "/" + logicalKey;
    }

    private void validateLogicalKey(String key) {
        if (!StringUtils.hasText(key)
                || key.startsWith("/")
                || key.contains("\\")
                || key.contains("//")
                || Arrays.stream(key.split("/", -1)).anyMatch(part -> part.equals(".") || part.equals(".."))
                || (!StorageKey.isProfile(key) && !StorageKey.isChat(key))
                || !StringUtils.hasText(StorageKey.nameOf(key))
                || StorageKey.nameOf(key).contains("/")) {
            throw new IllegalArgumentException("허용되지 않은 스토리지 key입니다.");
        }
    }

    private String requiredBucket(String configuredBucket) {
        if (!StringUtils.hasText(configuredBucket)) {
            throw new IllegalStateException(
                    "S3 storage requires file.storage.s3.bucket (S3_BUCKET)");
        }
        return configuredBucket.trim();
    }

    private String normalizePrefix(String configuredPrefix) {
        if (!StringUtils.hasText(configuredPrefix)) {
            return "";
        }
        String normalized = configuredPrefix.trim().replace('\\', '/');
        normalized = normalized.replaceAll("^/+|/+$", "");
        if (normalized.isEmpty()
                || normalized.contains("//")
                || Arrays.stream(normalized.split("/", -1))
                        .anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("S3 prefix가 올바르지 않습니다.");
        }
        return normalized;
    }

    private RuntimeException storageFailure(String operation, String objectKey, S3Exception ex) {
        String requestId = ex.requestId() == null ? "unknown" : ex.requestId();
        return new RuntimeException(
                operation + "에 실패했습니다: key=" + objectKey + ", requestId=" + requestId,
                ex);
    }

    private static final class S3ObjectResource extends InputStreamResource {

        private final String filename;
        private final long contentLength;

        private S3ObjectResource(ResponseInputStream<GetObjectResponse> stream, String filename) {
            super(stream);
            this.filename = filename;
            this.contentLength = stream.response().contentLength() == null
                    ? -1L
                    : stream.response().contentLength();
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }
    }
}
