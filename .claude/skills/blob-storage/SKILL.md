---
name: blob-storage
description: Store and serve files in this repo through S3-compatible object storage - when a presigned URL earns adopting over proxying bytes, wiring blob-storage-support's S3Client/S3Presigner, the PresignedBlobUrls sanctioned factory and its seven-day duration limit, and MinIO-compatible configuration for local dev and tests. Use whenever a change involves S3Client, S3Presigner, a file upload/download/attachment/export, or MinIO.
---

# Blob storage

A file a use case needs to store or serve - an upload, an export, an attachment - goes into an
S3-compatible object store through `libs/blob-storage-support`, never through the service's own
process as a byte-for-byte proxy. See
[P-044](../../../docs/principles/P-044-object-storage-presigned-access.md) for the full reasoning;
this skill is the how.

## Decide: presigned URL, or does the service really need to touch the bytes?

| Need | Use |
|---|---|
| A caller uploads or downloads a file, and can make its own HTTP call | a presigned URL |
| The service must read the file's content to do its job (validate a manifest, transform an image) | fetch the object through `S3Client` inside the use case that needs it, still never as a raw proxy for someone else's transfer |
| A caller genuinely cannot make its own HTTP call (a legacy integration, a fixed webhook target) | proxying bytes through the service, recorded as an `@Adr` - this is the exception, not the default |

Proxying every upload and download by default is the mistake P-044 exists to catch: it spends the
service's own request threads and heap moving bytes that were never the service's concern in the
first place.

## Configuration

```yaml
acme:
  blob-storage:
    region: us-east-1
    endpoint: http://localhost:9000     # omit for real AWS S3
    access-key-id: minioadmin           # omit for real AWS S3 - the SDK's default chain applies
    secret-access-key: minioadmin
```

`endpoint` is the switch between real AWS S3 and any S3-compatible store: set, it also forces
path-style bucket addressing (`http://host/bucket/key`) automatically, which MinIO and most
non-AWS stores require and which works against real AWS S3 too - never set `force-path-style`
by hand alongside a custom endpoint.

## Issue a presigned URL

```java
@OutboundAdapter(port = AttachmentStoragePort.class, kind = AdapterKind.BLOB_STORAGE)
@ImplementsPrinciple(value = "P-051", note = "SDK client timeout 2s connect / 5s read, no retry - PUT/GET are not safe to blindly retry with a partial body")
public class S3AttachmentStorageAdapter implements AttachmentStoragePort {

    private final PresignedBlobUrls presignedUrls;

    S3AttachmentStorageAdapter(PresignedBlobUrls presignedUrls) {
        this.presignedUrls = presignedUrls;
    }

    @Override
    public UploadTicket issueUploadUrl(AttachmentId id) {
        PresignedPutObjectRequest presigned =
                presignedUrls.forUpload("attachments", id.value(), Duration.ofMinutes(10));
        return new UploadTicket(presigned.url(), presigned.expiration());
    }

    @Override
    public URI issueDownloadUrl(AttachmentId id) {
        return presignedUrls.forDownload("attachments", id.value(), Duration.ofMinutes(5)).url();
    }
}
```

Never call `S3Presigner.presignPutObject`/`presignGetObject` directly - `BlobStorageRules`
refuses it. Always go through `PresignedBlobUrls`, injected like any other bean
`blob-storage-support` supplies; it rejects a zero or negative duration and anything past
`PresignedBlobUrls.MAX_DURATION` (seven days - S3's own SigV4 signing limit, not a policy this
platform adds) before the URL is ever built, rather than letting it fail silently days later once
someone is already holding the link.

Like any other remote-call adapter, an `@OutboundAdapter(kind = AdapterKind.BLOB_STORAGE)`
answers to [P-051](../../../docs/principles/P-051-remote-call-resilience.md) - state its timeout
and retry policy in `@ImplementsPrinciple`.

## Read or write an object directly

For the case where the service genuinely needs the bytes - not proxying someone else's transfer,
actually doing something with the content - inject `S3Client` directly:

```java
@Override
public Manifest readManifest(String bucket, String key) {
    try (var stream = s3Client.getObject(b -> b.bucket(bucket).key(key))) {
        return manifestParser.parse(stream);
    }
}
```

`S3Client` and `PresignedBlobUrls` are both supplied by `blob-storage-support`'s autoconfiguration
- add nothing further to a service's `pom.xml` beyond the dependency itself.

## MinIO for local development and tests

MinIO speaks the same S3 API real AWS S3 does - that is the entire point of building on the
plain SDK rather than an AWS-specific wrapper. Point `acme.blob-storage.endpoint` at a running
MinIO instance and everything above works unchanged.

For a real Testcontainers-backed test, `org.testcontainers:testcontainers-minio`'s `MinIOContainer`
needs an explicit image (there is no no-arg constructor) and exposes its own generated
credentials:

```java
@Container
static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");
```

```java
new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BlobStorageSupportAutoConfiguration.class))
        .withPropertyValues(
                "acme.blob-storage.endpoint=" + MINIO.getS3URL(),
                "acme.blob-storage.access-key-id=" + MINIO.getUserName(),
                "acme.blob-storage.secret-access-key=" + MINIO.getPassword());
```

Prove more than the beans wiring up: a presigned URL is only actually useful if an independent
HTTP client can use it, so drive it with a real `java.net.http.HttpClient` `PUT`/`GET` against
`presigned.url()`, not only that `PresignedBlobUrls` returned a non-null request. For a complete,
real example, read
`libs/blob-storage-support/src/test/java/com/acme/blobstorage/BlobStorageSupportAutoConfigurationTest.java`
- a direct upload/download roundtrip, plus both presigned-URL directions actually exercised
against a real MinIO container.

## The path-style pitfall

`S3Client` and `S3Presigner` do not share configuration - each needs path-style addressing set on
it independently. Missing it on the presigner only (the client still uploads and downloads fine)
produces a presigned URL that fails with `UnresolvedAddressException` the moment a caller tries to
use it, because it defaults to virtual-hosted-style addressing (`bucket.host`), an unresolvable
hostname against a non-AWS endpoint. `blob-storage-support`'s own autoconfiguration derives both
beans' addressing mode from the same `BlobStorageProperties.isForcePathStyle()` for exactly this
reason - if you ever hand-build either builder outside it, set both.

## Checklist

- [ ] the transfer is genuinely presigned-URL-shaped - the caller can make its own HTTP call, the
      service is not proxying bytes it has no reason to touch
- [ ] every presigned URL is built through `PresignedBlobUrls`, never `S3Presigner`'s methods
      directly
- [ ] the duration requested is the shortest one that is actually usable, and nowhere near the
      seven-day `MAX_DURATION` ceiling
- [ ] the adapter is `@OutboundAdapter(kind = AdapterKind.BLOB_STORAGE)` with
      `@ImplementsPrinciple("P-051")` stating its timeout and retry policy
- [ ] `acme.blob-storage.endpoint` (and matching credentials) is set for local dev and tests
      against MinIO, unset for real AWS S3
- [ ] a test proves a presigned URL by actually issuing the HTTP request it authorizes, not only
      that the request object was built
