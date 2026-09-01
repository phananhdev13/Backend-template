package com.acme.blobstorage;

import java.net.URI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires an {@code S3Client} and {@code S3Presigner} from {@code acme.blob-storage}
 * configuration - the S3 API, which AWS S3, MinIO and every other S3-compatible store speak
 * alike, so a service switches which store it talks to by configuration, never by code.
 */
@AutoConfiguration
@EnableConfigurationProperties(BlobStorageProperties.class)
public class BlobStorageSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    S3Client s3Client(BlobStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .forcePathStyle(properties.isForcePathStyle());
        if (properties.getEndpoint() != null) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    S3Presigner s3Presigner(BlobStorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isForcePathStyle())
                        .build());
        if (properties.getEndpoint() != null) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    PresignedBlobUrls presignedBlobUrls(S3Presigner presigner) {
        return new PresignedBlobUrls(presigner);
    }

    /**
     * Real AWS S3 authenticates through whatever the SDK's default provider chain finds - an
     * instance role, an environment variable, a profile. A non-AWS store has none of that to
     * find, so an explicit access key configured alongside {@link BlobStorageProperties#getEndpoint()}
     * is the signal to use static credentials instead.
     */
    private static AwsCredentialsProvider credentialsProvider(BlobStorageProperties properties) {
        if (properties.getAccessKeyId() != null) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
