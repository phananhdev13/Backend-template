package com.acme.caching;

import com.acme.kernel.cache.CacheContract;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Every {@link CacheContract} this application can see, found by scanning.
 *
 * <p>{@code @CacheContract} lives on a method, not a type, so the discovery this module needs is
 * a level below what {@code ClassPathScanningCandidateComponentProvider} does out of the box: it
 * matches classes, not their members. The {@link TypeFilter} below reads each class's annotation
 * metadata directly to ask "does this class declare any method with the annotation", and only the
 * classes that answer yes are loaded and reflected on.
 *
 * <p>Scanning rather than a hand-maintained list is the same trade {@code messaging-support} makes
 * for {@code @EventContract}: a cache someone declares but forgets to register in a list is a cache
 * whose backend and TTL {@code CachingSupportAutoConfiguration} never hears about, and Spring Cache
 * would fall back to whatever default the backend happens to have.
 */
public final class CacheContracts {

    private static final Logger log = LoggerFactory.getLogger(CacheContracts.class);

    private CacheContracts() {}

    /**
     * Scans the given packages and parses every contract found.
     *
     * @param basePackages packages to scan; empty means this application declares no cache
     *     contracts, which is a legitimate state and not worth forcing a service to configure
     *     around
     * @return one descriptor per distinct cache name, in scan order
     */
    public static List<CacheDescriptor> scan(List<String> basePackages) {
        Map<String, CacheDescriptor> byName = new LinkedHashMap<>();
        if (basePackages != null && !basePackages.isEmpty()) {
            ClassPathScanningCandidateComponentProvider provider =
                    new ClassPathScanningCandidateComponentProvider(false);
            provider.addIncludeFilter(declaresACacheContractMethod());
            ClassLoader classLoader = CacheContracts.class.getClassLoader();
            for (String basePackage : basePackages) {
                for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
                    describeCandidate(candidate.getBeanClassName(), classLoader, byName);
                }
            }
        }
        log.info("Discovered {} cache contract(s) in {}", byName.size(), basePackages);
        return List.copyOf(new ArrayList<>(byName.values()));
    }

    private static TypeFilter declaresACacheContractMethod() {
        return (metadataReader, metadataReaderFactory) -> !metadataReader
                .getAnnotationMetadata()
                .getAnnotatedMethods(CacheContract.class.getName())
                .isEmpty();
    }

    private static void describeCandidate(
            String className, ClassLoader classLoader, Map<String, CacheDescriptor> byName) {
        if (className == null) {
            return;
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            // A type on the scan path that will not load is not ours to report on; whatever
            // actually needs it will fail with a better message than we could produce here.
            log.debug("Skipping {} while scanning for cache contracts", className, ex);
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            CacheContract contract = method.getAnnotation(CacheContract.class);
            if (contract == null) {
                continue;
            }
            // Two methods may legitimately declare the same cache name - a query and the
            // eviction that invalidates it. CacheContractRules already requires every method
            // sharing a name to agree on backend and ttlSeconds, so keeping the first one found
            // is a simplification of an already-enforced invariant, not a guess.
            byName.putIfAbsent(contract.name(), CacheDescriptor.from(contract.name(), contract));
        }
    }
}
