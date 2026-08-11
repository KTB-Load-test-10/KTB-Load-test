package com.ktb.chatapp.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("file.storage.type 스위치 단위 테스트")
class StoragePortSelectionTest {

    @TempDir
    private Path uploadDir;

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalStorage.class, S3Storage.class)
                    .withBean(S3Client.class, () -> mock(S3Client.class))
                    .withBean(S3Presigner.class, () -> mock(S3Presigner.class));

    @Test
    @DisplayName("프로퍼티 미설정 시 LocalStorage 빈이 등록된다")
    void localStorageIsRegisteredWhenPropertyMissing() {
        contextRunner
                .withPropertyValues("file.upload-dir=" + uploadDir)
                .run(context -> assertThat(context).hasSingleBean(LocalStorage.class));
    }

    @Test
    @DisplayName("file.storage.type=local이면 LocalStorage 빈이 등록된다")
    void localStorageIsRegisteredWhenPropertyIsLocal() {
        contextRunner
                .withPropertyValues("file.storage.type=local", "file.upload-dir=" + uploadDir)
                .run(context -> assertThat(context).hasSingleBean(LocalStorage.class));
    }

    @Test
    @DisplayName("file.storage.type=s3이면 S3Storage만 등록된다")
    void s3StorageIsRegisteredWhenPropertyIsS3() {
        contextRunner
                .withPropertyValues(
                        "file.storage.type=s3",
                        "file.storage.s3.bucket=test-bucket",
                        "file.storage.s3.prefix=competition")
                .run(context -> {
                    assertThat(context).hasSingleBean(StoragePort.class);
                    assertThat(context).hasSingleBean(S3Storage.class);
                    assertThat(context).doesNotHaveBean(LocalStorage.class);
                });
    }

    @Test
    @DisplayName("S3 모드에서 bucket이 없으면 시작에 실패한다")
    void s3StorageFailsFastWhenBucketIsMissing() {
        contextRunner
                .withPropertyValues("file.storage.type=s3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "S3 storage requires file.storage.s3.bucket (S3_BUCKET)");
                });
    }

}
