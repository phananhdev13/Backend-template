# ADR-0017 — Plain AWS SDK v2, not Spring Cloud AWS, wired for MinIO compatibility

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-09-01 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

A service in this platform needs to store and serve files - uploads, exports, attachments -
without paying for that traffic in its own request threads and heap. The established pattern in
this repository, going back to gRPC ([ADR-0015](0015-spring-boot-native-grpc-for-internal-rpc.md))
and Temporal ([ADR-0016](0016-temporal-sdk-direct-not-the-spring-starter.md)), is: prefer a
first-party Spring Boot autoconfiguration when one genuinely targets this repository's Boot
version; otherwise depend on the plain SDK directly and wire the small amount of glue by hand,
rather than adopt a narrower or version-lagging wrapper around it.

There is no first-party Spring Boot autoconfiguration for AWS S3 - Boot ships starters for its
own supported services (JDBC, Kafka, Redis, and now gRPC), but S3 is not among them. Two real
candidates were evaluated:

**Spring Cloud AWS** (`io.awspring.cloud`) provides `S3Template` and autoconfigured `S3Client`
beans. It is a different project, with different governance, from `org.springframework.cloud` -
this repository's existing refusal of Spring Cloud proper
([ADR-0004](0004-do-not-adopt-spring-cloud.md), whose 2025.1.x release train targets Boot 4.0.x,
not 4.1) does not automatically extend to it. Checked directly: Spring Cloud AWS's 4.0.0+ line
does declare support for Spring Boot 4.x. It was still not adopted, for the same reason the
Temporal Spring starter was not: this platform's own pattern, once a capability's plain SDK is
usable directly with only a small amount of glue code, is to own that glue rather than add a
wrapper dependency whose release cadence the platform does not control - `grpc-support` and
`temporal-support` are both built this way, and `blob-storage-support` follows the same shape for
consistency, not because Spring Cloud AWS itself was found unsound.

**Plain `software.amazon.awssdk:s3`** (via `software.amazon.awssdk:bom`, both `2.54.10`) has no
opinion about Spring at all - it is the same SDK Spring Cloud AWS itself builds on. Verified
directly: `S3Client` and `S3Presigner` (the latter in package
`software.amazon.awssdk.services.s3.presigner`, not `services.s3` itself - confirmed by
inspecting the downloaded jar after an initial wrong import) both build from a builder that takes
a region, a credentials provider, and an optional endpoint override - exactly the shape
`grpc-support`'s and `temporal-support`'s own hand-wired autoconfigurations already take for their
respective SDKs.

**MinIO compatibility** was an explicit requirement, not an assumption: this platform's own
Testcontainers-based integration tests, and any self-hosted deployment, need to run against MinIO
- not only real AWS S3. Verified by running real Testcontainers tests
(`org.testcontainers:testcontainers-minio`) against `minio/minio:RELEASE.2025-09-07T16-13-09Z`:
an `S3Client` built with `forcePathStyle(true)` and an endpoint override correctly uploads and
downloads objects, but a presigned URL issued by an `S3Presigner` built *without* the matching
`serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())` fails with
`UnresolvedAddressException` - the presigner defaults to virtual-hosted-style addressing
(`bucket.host`), an unresolvable hostname against a non-AWS endpoint, independently of whatever
the `S3Client` bean was configured with. `S3Client` and `S3Presigner` do not share configuration
state; each needs the path-style flag set explicitly. This is the one real, non-obvious pitfall
this decision surfaced, and the reason `BlobStorageSupportAutoConfiguration`'s two bean methods
both derive their addressing mode from the same `BlobStorageProperties.isForcePathStyle()`, rather
than only the client being configured and the presigner assumed to follow.

## Decision

**Object storage is wired through plain `software.amazon.awssdk:s3`, in `libs/blob-storage-support`,
never Spring Cloud AWS.**

- `BlobStorageSupportAutoConfiguration` builds an `S3Client` and an `S3Presigner` from
  `acme.blob-storage` configuration properties - region, optional endpoint override, optional
  static credentials, and a `forcePathStyle` flag that is forced on automatically whenever a
  custom endpoint is set (a path-style request against real AWS S3 also works, so there is no
  reason path style would ever need to be off with a custom endpoint).
- Path-style addressing is applied to **both** beans independently: `S3ClientBuilder.forcePathStyle(boolean)`
  for the client, `S3Presigner.Builder.serviceConfiguration(S3Configuration)` for the presigner -
  the fix for the `UnresolvedAddressException` found during this investigation.
- `PresignedBlobUrls` is the one sanctioned way to build a presigned URL - `BlobStorageRules` in
  `libs/arch-test` refuses any other class calling `S3Presigner.presignPutObject`/
  `presignGetObject` directly, because that call accepts (and the store will not enforce until
  the link is already handed out) a duration longer than S3's own seven-day SigV4 limit.
- Credentials come from `StaticCredentialsProvider` when `acme.blob-storage.access-key-id` is
  set (MinIO's own key, or any non-AWS store with no IAM to authenticate against), and from
  `DefaultCredentialsProvider` otherwise (real AWS S3's instance role, environment variable, or
  profile chain).
- Verified end to end against a real `MinIOContainer`: bucket creation, a direct `putObject`/
  `getObject` roundtrip, and - separately - an independent `java.net.http.HttpClient` actually
  performing the `PUT`/`GET` a presigned URL authorizes, not merely that the URL-building code
  compiles.

## Consequences

**Good** — No dependency on a Spring wrapper project's own release cadence tracking Boot's; the
platform owns a small, already-familiar shape of glue (an `@AutoConfiguration` with a couple of
`@Bean` methods) instead. The same configuration properties point the client at real AWS S3 or at
MinIO - switching store is a property change (`acme.blob-storage.endpoint`), never a code change,
which is exactly what the explicit MinIO-compatibility requirement asked for.

**Bad** — This platform's own `BlobStorageSupportAutoConfiguration` re-implements the small amount
of Spring wiring `S3Template`-based autoconfiguration would otherwise give for free (bean
lifecycle, configuration property binding) - a difference of maybe forty lines of code kept in
sync with any future AWS SDK v2 API change by hand, rather than by a wrapper library's maintainers.

**Neutral** — `PresignedBlobUrls` deliberately does not wrap every S3 operation, only presigning -
a service that needs `ListObjectsV2` or multipart upload orchestration calls the injected
`S3Client` directly, the same way a service needing an unusual gRPC call pattern still has the raw
generated stub available beside `grpc-support`'s cross-cutting wiring.

## Alternatives considered

### Spring Cloud AWS (`io.awspring.cloud`)

Gives `S3Template` and Boot-property-driven `S3Client` autoconfiguration out of the box, and its
4.0.0+ line does target Boot 4.x, unlike the Temporal Spring starter this repository already
rejected. Rejected anyway, for consistency with `grpc-support`'s and `temporal-support`'s own
precedent: once a plain SDK is directly usable with only a small, owned amount of glue, adding a
wrapper dependency trades a small amount of code for a dependency on a project whose release
cadence this platform does not control, for no compatibility problem that dependency was actually
needed to solve here.

### Proxy file bytes through the service instead of presigned URLs

Would avoid the presigned-URL duration and path-style pitfalls entirely, at the cost of every
upload and download competing with interactive request traffic for the same thread pool and heap
- exactly the problem [P-044](../principles/P-044-object-storage-presigned-access.md) exists to
avoid. Rejected as the default; recorded there as the one case (a caller that genuinely cannot
make its own HTTP call) where proxying is a legitimate, ADR-recorded deviation.

## Revisit when

Spring Cloud AWS or a first-party Boot S3 starter reaches a release this platform would otherwise
prefer to depend on directly for reasons beyond configuration convenience - re-evaluate
`BlobStorageSupportAutoConfiguration` against it at that point, the same open question ADR-0016
leaves for Temporal's own Spring starter. Re-verify the path-style addressing behaviour whenever
`software.amazon.awssdk:bom`'s managed version changes; this ADR's finding is a snapshot of
`2.54.10`'s behaviour, not a guarantee either the SDK or MinIO's own S3 compatibility keeps.
