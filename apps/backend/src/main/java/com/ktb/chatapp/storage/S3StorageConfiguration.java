package com.ktb.chatapp.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트는 SDK 기본 자격 증명 체인을 사용한다.
 *
 * <p>프로덕션에서는 EC2 instance profile, ECS task role, EKS workload identity처럼
 * 수명이 짧은 IAM role 자격 증명을 제공하고 access key를 애플리케이션 설정에 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3StorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${file.storage.s3.region:}") String configuredRegion) {
        return S3Client.builder()
                .region(region(configuredRegion))
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(@Value("${file.storage.s3.region:}") String configuredRegion) {
        return S3Presigner.builder()
                .region(region(configuredRegion))
                .build();
    }

    private Region region(String configuredRegion) {
        if (!StringUtils.hasText(configuredRegion)) {
            throw new IllegalStateException(
                    "S3 storage requires file.storage.s3.region (AWS_REGION)");
        }
        return Region.of(configuredRegion.trim());
    }
}
