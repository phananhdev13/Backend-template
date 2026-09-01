package com.acme.blobstorage;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the object store is, and how to reach it - AWS S3 by default, or a non-AWS
 * S3-compatible store such as MinIO once {@link #endpoint} is set.
 */
@ConfigurationProperties("acme.blob-storage")
public class BlobStorageProperties {

    /** AWS region, meaningless for a self-hosted store but required by the client either way. */
    private String region = "us-east-1";

    /**
     * Overrides the AWS endpoint to point at a non-AWS S3-compatible store, e.g.
     * {@code http://localhost:9000} for MinIO. {@code null} means real AWS S3.
     */
    private @Nullable String endpoint;

    /**
     * Required alongside {@link #endpoint} for a store with no IAM to authenticate against -
     * MinIO's own access key, never a real AWS credential. {@code null} means the SDK's default
     * credential provider chain, which is what real AWS S3 needs.
     */
    private @Nullable String accessKeyId;

    private @Nullable String secretAccessKey;

    /**
     * MinIO, and most other non-AWS S3-compatible stores, only serve
     * {@code http://host/bucket/key} addressing, never AWS's default
     * {@code http://bucket.host/key} virtual-hosted style. Forced on automatically whenever
     * {@link #endpoint} is set, since a path-style request against real AWS S3 works too.
     */
    private boolean forcePathStyle;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public @Nullable String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(@Nullable String endpoint) {
        this.endpoint = endpoint;
    }

    public @Nullable String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(@Nullable String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public @Nullable String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(@Nullable String secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }

    public boolean isForcePathStyle() {
        return forcePathStyle || endpoint != null;
    }

    public void setForcePathStyle(boolean forcePathStyle) {
        this.forcePathStyle = forcePathStyle;
    }
}
