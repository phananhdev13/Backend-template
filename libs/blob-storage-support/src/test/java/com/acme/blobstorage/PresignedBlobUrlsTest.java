package com.acme.blobstorage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class PresignedBlobUrlsTest {

    private final PresignedBlobUrls urls = new PresignedBlobUrls(mock(S3Presigner.class));

    @Test
    void refusesADurationLongerThanSevenDays() {
        assertThatThrownBy(() -> urls.forUpload("bucket", "key", Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S3's own SigV4 limit");
    }

    @Test
    void refusesAZeroDuration() {
        assertThatThrownBy(() -> urls.forDownload("bucket", "key", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");
    }

    @Test
    void refusesANegativeDuration() {
        assertThatThrownBy(() -> urls.forUpload("bucket", "key", Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive duration");
    }
}
