package com.acme.caching;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.kernel.cache.CacheBackend;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheContractsTest {

    @Test
    void discoversEveryDistinctCacheNameUnderTheGivenPackages() {
        List<CacheDescriptor> descriptors = CacheContracts.scan(List.of("com.acme.caching.fixture"));

        assertThat(descriptors)
                .extracting(CacheDescriptor::name)
                .containsExactlyInAnyOrder("caching.sample-local", "caching.sample-distributed");
    }

    @Test
    void parsesTheDeclaredBackendAndTtl() {
        List<CacheDescriptor> descriptors = CacheContracts.scan(List.of("com.acme.caching.fixture"));

        assertThat(descriptors)
                .filteredOn(descriptor -> descriptor.name().equals("caching.sample-local"))
                .singleElement()
                .satisfies(descriptor -> {
                    assertThat(descriptor.backend()).isEqualTo(CacheBackend.LOCAL);
                    assertThat(descriptor.ttl()).isEqualTo(Duration.ofSeconds(60));
                    assertThat(descriptor.containsPersonalData()).isFalse();
                });
    }

    @Test
    void twoMethodsSharingANameYieldOneDescriptor() {
        List<CacheDescriptor> descriptors = CacheContracts.scan(List.of("com.acme.caching.fixture"));

        assertThat(descriptors)
                .filteredOn(descriptor -> descriptor.name().equals("caching.sample-distributed"))
                .hasSize(1);
    }

    @Test
    void emptyBasePackagesDiscoverNothing() {
        assertThat(CacheContracts.scan(List.of())).isEmpty();
        assertThat(CacheContracts.scan(null)).isEmpty();
    }
}
