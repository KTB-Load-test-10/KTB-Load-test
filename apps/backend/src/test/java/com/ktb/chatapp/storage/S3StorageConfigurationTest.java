package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@DisplayName("S3 클라이언트 설정 단위 테스트")
class S3StorageConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(S3StorageConfiguration.class);

    @Test
    @DisplayName("S3 모드와 region이 설정되면 client와 presigner가 생성된다")
    void createsClientsForConfiguredRegion() {
        contextRunner
                .withPropertyValues(
                        "file.storage.type=s3",
                        "file.storage.s3.region=ap-northeast-2")
                .run(context -> {
                    assertThat(context).hasSingleBean(S3Client.class);
                    assertThat(context).hasSingleBean(S3Presigner.class);
                });
    }

    @Test
    @DisplayName("S3 모드에서 region이 없으면 시작에 실패한다")
    void failsFastWithoutRegion() {
        contextRunner
                .withPropertyValues("file.storage.type=s3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "S3 storage requires file.storage.s3.region (AWS_REGION)");
                });
    }
}
