package com.acme.blobstorage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * Proves the autoconfiguration's endpoint override and path-style addressing actually reach a
 * real, non-AWS S3-compatible store, and that a presigned URL this platform issues is honoured
 * by a real, independent HTTP client - not merely that the beans wire up against a loading
 * context.
 *
 * <p>MinIO, not LocalStack: no account or auth token needed to run the container at all, which
 * matters for a template meant to run in any CI without registration.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class BlobStorageSupportAutoConfigurationTest {

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BlobStorageSupportAutoConfiguration.class))
                .withPropertyValues(
                        "acme.blob-storage.endpoint=" + MINIO.getS3URL(),
                        "acme.blob-storage.access-key-id=" + MINIO.getUserName(),
                        "acme.blob-storage.secret-access-key=" + MINIO.getPassword(),
                        "acme.blob-storage.region=us-east-1");
    }

    @Test
    void uploadsAndDownloadsARealObjectThroughARealMinioServer() {
        contextRunner().run(context -> {
            S3Client s3 = context.getBean(S3Client.class);
            s3.createBucket(b -> b.bucket("roundtrip"));

            s3.putObject(b -> b.bucket("roundtrip").key("hello.txt"), RequestBody.fromString("hello world"));

            String content = new String(
                    s3.getObject(b -> b.bucket("roundtrip").key("hello.txt")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("hello world");
        });
    }

    @Test
    void aPresignedUploadUrlActuallyAcceptsARealHttpPut() throws Exception {
        contextRunner().run(context -> {
            S3Client s3 = context.getBean(S3Client.class);
            s3.createBucket(b -> b.bucket("presigned-put"));
            PresignedBlobUrls urls = context.getBean(PresignedBlobUrls.class);

            PresignedPutObjectRequest presignedPut =
                    urls.forUpload("presigned-put", "signed.txt", Duration.ofMinutes(5));

            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(presignedPut.url().toURI())
                    .PUT(HttpRequest.BodyPublishers.ofString("signed upload"))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(200);
            String stored = new String(
                    s3.getObject(b -> b.bucket("presigned-put").key("signed.txt"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(stored).isEqualTo("signed upload");
        });
    }

    @Test
    void aPresignedDownloadUrlActuallyServesARealHttpGet() throws Exception {
        contextRunner().run(context -> {
            S3Client s3 = context.getBean(S3Client.class);
            s3.createBucket(b -> b.bucket("presigned-get"));
            s3.putObject(b -> b.bucket("presigned-get").key("signed.txt"), RequestBody.fromString("download me"));
            PresignedBlobUrls urls = context.getBean(PresignedBlobUrls.class);

            PresignedGetObjectRequest presignedGet =
                    urls.forDownload("presigned-get", "signed.txt", Duration.ofMinutes(5));

            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request =
                    HttpRequest.newBuilder(presignedGet.url().toURI()).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("download me");
        });
    }
}
