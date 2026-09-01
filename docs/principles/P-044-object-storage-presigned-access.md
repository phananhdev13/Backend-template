# P-044 — Object storage is reached through the S3 API, and access is presigned, never proxied

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `DomainRules.domainDependsOnlyOnDomain()`, `ErrorRules.domainNeverThrowsWebExceptions()`, `ResilienceRules.remoteCallsDeclareTimeouts()`, `BlobStorageRules.presignedUrlsGoThroughTheSanctionedFactory()` in `libs/arch-test` |
| **Annotations** | `@OutboundAdapter(kind = AdapterKind.BLOB_STORAGE)`, `@ImplementsPrinciple` |
| **Guide** | [skill: blob-storage](../../.claude/skills/blob-storage/SKILL.md) |

## Rule

A file a use case needs to store or serve - an upload, an export, an attachment - is put in an
S3-compatible object store through `libs/blob-storage-support`, never streamed through the
service's own process as a byte-for-byte proxy. A caller that needs to upload or download talks
to the store directly, using a URL this platform presigns and hands back; the service itself
never sits in the data path. Every presigned URL is built through `PresignedBlobUrls`, never by
calling `S3Presigner.presignPutObject`/`presignGetObject` directly, and every duration is bounded
by S3's own seven-day SigV4 signature limit - a limit `PresignedBlobUrls` enforces before the
link is issued, not one the store enforces after it has already been handed to a caller.

## Why

**A service that proxies file bytes pays for bandwidth and memory it does not need to spend.**
Streaming a multi-gigabyte upload or export through the service's own heap-and-thread budget
means one large file competes with every other request for the same request thread and the same
memory the JVM has, for work that is really between the caller and the store. A presigned URL
lets the caller's HTTP client talk to the object store's own infrastructure directly - the
service's job is to decide whether the caller may have that URL at all, not to move the bytes.

**The S3 API is the one surface every object store this platform is likely to sit in front of
already speaks.** AWS S3, MinIO, and most self-hosted or on-prem object stores implement the same
request signing scheme and the same verb shape (`PUT`/`GET` against `bucket/key`) precisely so
that client code written against one works against the others unchanged. Building
`blob-storage-support` on the plain `software.amazon.awssdk:s3` client, with an endpoint override
and path-style addressing as configuration rather than a different code path
([ADR-0017](../adr/0017-aws-sdk-v2-direct-minio-compatible.md)), is what makes "point this at
MinIO instead of AWS" a property change, not a rewrite - the same reasoning
[ADR-0016](../adr/0016-temporal-sdk-direct-not-the-spring-starter.md) already applied to Temporal:
depend on the capability directly, not a narrower wrapper around it.

**`S3Presigner.presignPutObject` and `presignGetObject` accept any `Duration`, including one
S3 will never actually honour.** SigV4 signatures are capped at seven days by the signing scheme
itself - a presigned URL requested for thirty days is accepted by the SDK, built successfully,
handed to a caller, and then simply stops working days before anyone holding it expects, for a
reason that looks nothing like "the code that issued this asked for too long." Checking the
duration where the URL is built, in the one class every presigned URL goes through, is what turns
that into a failure at the call site instead of a support ticket days later.

**Generated request/response types from the AWS SDK are framework types the same way a
generated gRPC stub or a JPA entity is.** `PutObjectRequest`, `S3Client`, `S3Presigner` all carry
SDK machinery a domain type must not depend on to stay testable without a real store in the loop -
`DomainRules.domainDependsOnlyOnDomain` already forbids the domain depending on Hibernate, Kafka
and `io.grpc` for exactly this reason, and `software.amazon.awssdk..` earns the same place in
that list rather than a rule of its own.

**A failure signing a URL or reaching the store is still a remote-call failure.** The store can
be unreachable, the bucket can not exist, credentials can be wrong - none of that is different in
kind from an HTTP client's connection refused, so an `@OutboundAdapter(kind =
AdapterKind.BLOB_STORAGE)` answers to [P-051](P-051-remote-call-resilience.md) exactly as an
`HTTP_CLIENT` or `CACHE` adapter does: a timeout and a stated retry policy, not a call that can
hang forever because "it's just object storage."

## In code

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
        return presignedUrls.forDownload("attachments", id.value(), Duration.ofMinutes(5)).url()
                .toString();
    }
}
```

Wrong - hand-building the presign request, bypassing the duration check `PresignedBlobUrls`
exists to make:

```java
@Override
public UploadTicket issueUploadUrl(AttachmentId id) {
    // Compiles, builds, and works right up until this URL is 30 days old and quietly stops.
    PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofDays(30))
            .putObjectRequest(b -> b.bucket("attachments").key(id.value()))
            .build());
    return new UploadTicket(presigned.url(), presigned.expiration());
}
```

## Enforcement

`DomainRules.domainDependsOnlyOnDomain()` fails a domain or application class depending on
`software.amazon.awssdk..`, exactly as it already fails one depending on Hibernate or `io.grpc`.
`ErrorRules.domainNeverThrowsWebExceptions()` fails the same classes depending on
`software.amazon.awssdk..` specifically for exception types.
`ResilienceRules.remoteCallsDeclareTimeouts()` fails an `@OutboundAdapter(kind =
AdapterKind.BLOB_STORAGE)` with no `@ImplementsPrinciple("P-051")`.
`BlobStorageRules.presignedUrlsGoThroughTheSanctionedFactory()` fails any class other than
`PresignedBlobUrls` itself calling `S3Presigner.presignPutObject` or `presignGetObject`.

## Deviating

A service with no files to store or serve has no reason to add `blob-storage-support` at all -
its AWS SDK dependency is not on the classpath unless the module is declared, the same as
`grpc-support`'s and `temporal-support`'s dependencies. A genuinely small, always-in-memory
payload a use case already has to read for validation (a manifest, a signature) does not need
object storage at all; reaching for a presigned URL for that case is unnecessary indirection, not
a deviation to record.

A caller that cannot make its own HTTP call to a presigned URL (a legacy integration, a webhook
target with no notion of one) is a real reason to proxy bytes through the service after all -
record it as an `@Adr`, and still put a timeout and a size limit on the proxying adapter, since
it is now exactly the failure mode this principle exists to avoid.

Related: [P-051](P-051-remote-call-resilience.md) for the timeout and retry policy every
`BLOB_STORAGE` adapter states; [ADR-0017](../adr/0017-aws-sdk-v2-direct-minio-compatible.md) for
why the plain AWS SDK v2, not Spring Cloud AWS.
