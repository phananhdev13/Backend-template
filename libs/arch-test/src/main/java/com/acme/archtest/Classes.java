package com.acme.archtest;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Set;

/**
 * Predicates for the classes structural rules should ignore.
 *
 * <p>Rules that apply to "every class" are only useful if the exemptions are few,
 * named, and justified. Each one here is a category that genuinely carries no
 * architectural role, not a category that was awkward to annotate.
 */
public final class Classes {

    /**
     * Suffixes reserved for data carriers at the edge of the system.
     *
     * <p>These types exist to be serialised. They hold no behaviour, so a role
     * annotation would say nothing - but they are also not allowed to appear outside an
     * adapter package, which {@code NamingRules} checks separately.
     */
    public static final Set<String> EDGE_DATA_SUFFIXES =
            Set.of("Request", "Response", "Dto", "View", "Row", "Projection", "Payload");

    private Classes() {}

    /** Nested, anonymous, synthetic and generated types, which are never annotated directly. */
    public static DescribedPredicate<JavaClass> structural() {
        return new DescribedPredicate<>("a nested, anonymous or synthetic type") {
            @Override
            public boolean test(JavaClass type) {
                return type.isNestedClass()
                        || type.isAnonymousClass()
                        || type.isLocalClass()
                        || type.getSimpleName().contains("$")
                        || type.isArray()
                        || type.getSimpleName().isEmpty()
                        || type.getSimpleName().equals("package-info");
            }
        };
    }

    /**
     * Classes declaring a method annotated with the named annotation.
     *
     * <p>Addressed by fully qualified name so a rule can select on a framework annotation -
     * {@code @Scheduled}, {@code @Transactional} - that {@code libs/arch-test} deliberately does
     * not put on its own compile classpath.
     */
    public static DescribedPredicate<JavaClass> haveAMethodAnnotatedWith(String annotationFullName) {
        return new DescribedPredicate<>("declaring a method annotated with " + annotationFullName) {
            @Override
            public boolean test(JavaClass type) {
                return type.getMethods().stream().anyMatch(method -> Annotations.hasNamed(method, annotationFullName));
            }
        };
    }

    /** Types whose category, rather than their annotation, already fixes their meaning. */
    public static DescribedPredicate<JavaClass> exemptFromRoleAnnotation() {
        return new DescribedPredicate<>("exempt from carrying an architectural role") {
            @Override
            public boolean test(JavaClass type) {
                return structural().test(type)
                        || type.isEnum()
                        || type.isInterface() && type.getSimpleName().endsWith("Repository")
                        || type.isAssignableTo(Throwable.class)
                        || type.isAnnotation()
                        || isEdgeData(type)
                        || isSpringBootApplication(type)
                        || isPersistenceType(type);
            }
        };
    }

    /** Whether the simple name marks this as a serialisation-only type. */
    public static boolean isEdgeData(JavaClass type) {
        return EDGE_DATA_SUFFIXES.stream()
                .anyMatch(suffix -> type.getSimpleName().endsWith(suffix));
    }

    /** JPA mapping types, whose role is fixed by the persistence annotations they carry. */
    public static boolean isPersistenceType(JavaClass type) {
        return type.getAnnotations().stream()
                .map(annotation -> annotation.getRawType().getFullName())
                .anyMatch(name -> name.startsWith("jakarta.persistence."));
    }

    /** The Spring Boot entry point, which is wiring by definition. */
    public static boolean isSpringBootApplication(JavaClass type) {
        return type.getAnnotations().stream()
                .map(annotation -> annotation.getRawType().getFullName())
                .anyMatch("org.springframework.boot.autoconfigure.SpringBootApplication"::equals);
    }
}
