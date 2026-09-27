package com.csd.salesforecast.config;

import com.csd.salesforecast.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.net.URI;

@Configuration
public class StorageConfig {
    @Bean
    @ConditionalOnProperty(name = "app.storage.backend", havingValue = "local", matchIfMissing = true)
    ObjectStore localObjectStore() { return new LocalObjectStore(); }

    @Bean
    @ConditionalOnProperty(name = "app.storage.backend", havingValue = "s3")
    S3Client s3Client(@Value("${app.storage.s3.endpoint}") String endpoint, @Value("${app.storage.s3.region}") String region,
        @Value("${app.storage.s3.access-key-id}") String accessKeyId, @Value("${app.storage.s3.secret-access-key}") String secretAccessKey) {
        // Supabase needs path-style URLs and rejects the SDK's default optional checksums.
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region)).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.backend", havingValue = "s3")
    ObjectStore s3ObjectStore(S3Client s3Client, @Value("${app.storage.s3.bucket}") String bucket) {
        return new S3ObjectStore(s3Client, bucket);
    }
}
