package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.acme.kernel.arch.Adr;
import com.acme.kernel.cache.CacheContract;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache contracts must be internally consistent, and must actually be wired: P-130.
 *
 * <p>{@code @CacheContract} is documentation until a real Spring Cache annotation reads
 * the same name - these rules catch the two ways that pairing rots: a contract nothing
 * populates, and two populators that quietly disagree about what the name means.
 */
public final class CacheContractRules {

    private static final List<String> SPRING_CACHE_ANNOTATIONS = List.of(
            "org.springframework.cache.annotation.Cacheable",
            "org.springframework.cache.annotation.CachePut",
            "org.springframework.cache.annotation.CacheEvict");

    @ArchTest
    public static final ArchRule everyCacheContractPairsWithASpringCacheAnnotation = methods()
            .that()
            .areAnnotatedWith(CacheContract.class)
            .should(carryARealSpringCacheAnnotation())
            .allowEmptyShould(true)
            .as("every @CacheContract method is actually cached (P-130)")
            .because("the contract describes a cache; only @Cacheable, @CachePut or @CacheEvict make "
                    + "Spring populate one. Without it the method compiles and declares intent that "
                    + "nothing carries out. See docs/principles/P-130-caching-contracts.md");

    @ArchTest
    public static final ArchRule distributedPersonalDataCachesCarryAnAdr = methods()
            .that()
            .areAnnotatedWith(CacheContract.class)
            .should(justifyDistributedPersonalData())
            .allowEmptyShould(true)
            .as("a distributed cache of personal data carries an ADR (P-130)")
            .because("DISTRIBUTED replicates the entry to every instance and keeps it outside the "
                    + "system of record's own access controls; someone has to own that decision. "
                    + "See docs/principles/P-130-caching-contracts.md");

    /**
     * Two methods can legitimately share a cache name - a query and the eviction that
     * invalidates it - but only if they agree on what the cache actually is. Checked
     * across the whole import because no single method can see the others sharing its name.
     */
    @ArchTest
    public static void cacheNamesAgreeOnBackendAndTtl(JavaClasses classes) {
        Map<String, List<String>> declarationsByName = new HashMap<>();
        for (JavaMethod method :
                classes.stream().flatMap(type -> type.getMethods().stream()).toList()) {
            Annotations.find(method, CacheContract.class).ifPresent(contract -> {
                String name = Annotations.string(contract, "name", "?");
                String backend = Annotations.enumName(contract, "backend", "LOCAL");
                long ttl = Annotations.longValue(contract, "ttlSeconds", 300);
                String declaration = backend + " ttlSeconds=" + ttl + " (" + method.getFullName() + ")";
                declarationsByName
                        .computeIfAbsent(name, key -> new ArrayList<>())
                        .add(declaration);
            });
        }
        List<String> conflicts = declarationsByName.entrySet().stream()
                .filter(entry -> distinctSignatures(entry.getValue()) > 1)
                .map(entry -> "\"" + entry.getKey() + "\" is declared inconsistently:\n    "
                        + String.join("\n    ", entry.getValue()))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new AssertionError("Methods sharing a cache name disagree about what it is, so "
                    + "different code paths would serve different data under the same name. Make "
                    + "every @CacheContract for a name declare the same backend and ttlSeconds.\n  "
                    + String.join("\n  ", conflicts));
        }
    }

    private CacheContractRules() {}

    private static long distinctSignatures(List<String> declarations) {
        return declarations.stream()
                .map(declaration -> declaration.split(" \\(")[0])
                .distinct()
                .count();
    }

    private static ArchCondition<JavaMethod> carryARealSpringCacheAnnotation() {
        return new ArchCondition<>("carry a Spring Cache annotation naming the same cache") {
            @Override
            public void check(JavaMethod item, ConditionEvents events) {
                List<JavaAnnotation<JavaMethod>> springAnnotations = SPRING_CACHE_ANNOTATIONS.stream()
                        .map(name -> Annotations.findNamed(item, name))
                        .flatMap(java.util.Optional::stream)
                        .toList();
                if (springAnnotations.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "%s declares @CacheContract but no @Cacheable, @CachePut or @CacheEvict. "
                                            .formatted(item.getFullName())
                                    + "Add one naming the same cache, or remove the contract."));
                    return;
                }
                // Presence was all this rule used to check, so the two names could disagree. A
                // @Cacheable naming a cache no contract declared gets no TTL and no declared
                // backend - the runtime has to refuse it, and this catches it a build earlier.
                String declared = Annotations.find(item, CacheContract.class)
                        .map(contract -> Annotations.string(contract, "name", ""))
                        .orElse("");
                for (JavaAnnotation<JavaMethod> spring : springAnnotations) {
                    List<String> named = springCacheNames(spring);
                    if (named.isEmpty() || named.contains(declared)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s declares @CacheContract(name = \"%s\") but its @%s names %s. The "
                                            + "contract governs the cache Spring actually uses only when "
                                            + "the two names are the same one.")
                                    .formatted(
                                            item.getFullName(),
                                            declared,
                                            spring.getRawType().getSimpleName(),
                                            named)));
                }
            }
        };
    }

    /** The cache names a Spring cache annotation asks for, from either spelling of the member. */
    private static List<String> springCacheNames(JavaAnnotation<JavaMethod> annotation) {
        List<String> names = new java.util.ArrayList<>(Annotations.strings(annotation, "cacheNames"));
        names.addAll(Annotations.strings(annotation, "value"));
        return names;
    }

    private static ArchCondition<JavaMethod> justifyDistributedPersonalData() {
        return new ArchCondition<>("justify a distributed cache of personal data with an ADR") {
            @Override
            public void check(JavaMethod item, ConditionEvents events) {
                java.util.Optional<JavaAnnotation<JavaMethod>> contract = Annotations.find(item, CacheContract.class);
                if (contract.isEmpty()) {
                    return;
                }
                boolean personalData = Annotations.bool(contract.get(), "containsPersonalData", false);
                boolean distributed = "DISTRIBUTED".equals(Annotations.enumName(contract.get(), "backend", "LOCAL"));
                if (!personalData || !distributed) {
                    return;
                }
                boolean justified = Annotations.find(item, Adr.class).isPresent()
                        || Annotations.find(item.getOwner(), Adr.class).isPresent();
                if (justified) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s caches personal data on a DISTRIBUTED backend. Record the decision in "
                                        + "docs/adr and reference it with @Adr.")
                                .formatted(item.getFullName())));
            }
        };
    }
}
