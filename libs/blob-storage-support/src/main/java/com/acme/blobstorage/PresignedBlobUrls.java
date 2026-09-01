package com.acme.blobstorage;

import java.time.Duration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The one sanctioned way to build a presigned S3 URL in this platform - never
 * {@code S3Presigner.presignPutObject}/{@code presignGetObject} directly, which
 * {@code BlobStorageRules} in {@code libs/arch-test} refuses.
 *
 * <p>S3's SigV4 signing scheme hard-caps a presigned URL's validity at seven days - a longer
 * duration is silently unenforceable by the store itself, not merely inadvisable, so requesting
 * one is very likely a sign the actual requirement is a different mechanism (a service account
 * credential, a proxy endpoint) rather than a link a caller holds onto. This class is where that
 * limit is checked before a caller discovers it only once the link stops working three days
 * before whoever holds it expected.
 */
public final class PresignedBlobUrls {

    /** S3's own hard limit for SigV4-signed URLs, not a policy choice this platform adds. */
    public static final Duration MAX_DURATION = Duration.ofDays(7);

    private final S3Presigner presigner;

    PresignedBlobUrls(S3Presigner presigner) {
        this.presigner = presigner;
    }

    /** A URL a caller can {@code PUT} an object to, valid for {@code duration}. */
    public PresignedPutObjectRequest forUpload(String bucket, String key, Duration duration) {
        validate(duration);
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(putObjectRequest)
                .build());
    }

    /** A URL a caller can {@code GET} an object from, valid for {@code duration}. */
    public PresignedGetObjectRequest forDownload(String bucket, String key, Duration duration) {
        validate(duration);
        GetObjectRequest getObjectRequest =
                GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(getObjectRequest)
                .build());
    }

    private static void validate(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("A presigned URL needs a positive duration, got " + duration);
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException(
                    ("A presigned URL cannot be valid for longer than %s - that is S3's own SigV4 limit, "
                                    + "not a policy this platform adds. Requested %s. If a caller genuinely "
                                    + "needs access for longer than a week, a presigned URL is the wrong "
                                    + "mechanism; look at a service credential or a proxying endpoint instead.")
                            .formatted(MAX_DURATION, duration));
        }
    }
}
