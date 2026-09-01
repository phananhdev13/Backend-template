package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/**
 * A presigned S3 URL is built through {@code PresignedBlobUrls}, never by hand: P-044.
 *
 * <p>{@code S3Presigner.presignPutObject}/{@code presignGetObject} accept any
 * {@code Duration} - including one longer than S3's own seven-day SigV4 limit, which is not
 * rejected until the link is actually used and fails for a reason that looks nothing like
 * "your code asked for too long." Routing every presigned URL through one class is what makes
 * that check happen where the mistake is made, not where it is discovered.
 */
public final class BlobStorageRules {

    private static final String SANCTIONED_FACTORY = "com.acme.blobstorage.PresignedBlobUrls";

    @ArchTest
    public static final ArchRule presignedUrlsGoThroughTheSanctionedFactory = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(SANCTIONED_FACTORY)
            .should()
            .callMethodWhere(presignerCall())
            .allowEmptyShould(true)
            .as("presigned S3 URLs are built through PresignedBlobUrls, not by hand (P-044)")
            .because("S3Presigner.presignPutObject/presignGetObject accept any Duration, including "
                    + "one past S3's own seven-day SigV4 limit - PresignedBlobUrls is the one place "
                    + "that is checked before the link fails on whoever holds it instead. "
                    + "See docs/principles/P-044-object-storage-presigned-access.md");

    private BlobStorageRules() {}

    private static DescribedPredicate<JavaMethodCall> presignerCall() {
        Set<String> methods = Set.of("presignPutObject", "presignGetObject");
        return new DescribedPredicate<>("a call to S3Presigner.presignPutObject or presignGetObject") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner()
                                .getFullName()
                                .equals("software.amazon.awssdk.services.s3.presigner.S3Presigner")
                        && methods.contains(call.getTarget().getName());
            }
        };
    }
}
