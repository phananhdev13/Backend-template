package com.acme.archtest;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

/**
 * Reads annotation members through ArchUnit's own model rather than through reflection.
 *
 * <p>{@code JavaClass.getAnnotationOfType(Class)} builds a proxy, which has to load
 * every type an annotation member mentions. For members typed {@code Class<?>} that
 * couples the rules to the analysed classpath and fails in ways that look like rule bugs.
 * The untyped {@link JavaAnnotation} API stays inside the imported model, so these
 * helpers work whatever the analysed code depends on.
 */
public final class Annotations {

    private Annotations() {}

    /** The annotation of the given type on {@code type}, if present. */
    public static Optional<JavaAnnotation<JavaClass>> find(JavaClass type, Class<? extends Annotation> annotation) {
        return type.getAnnotations().stream()
                .filter(a -> a.getRawType().getFullName().equals(annotation.getName()))
                .findFirst();
    }

    /** Whether {@code type} carries the given annotation. */
    public static boolean has(JavaClass type, Class<? extends Annotation> annotation) {
        return find(type, annotation).isPresent();
    }

    /** The annotation of the given type on {@code method}, if present. */
    public static Optional<JavaAnnotation<JavaMethod>> find(JavaMethod method, Class<? extends Annotation> annotation) {
        return method.getAnnotations().stream()
                .filter(a -> a.getRawType().getFullName().equals(annotation.getName()))
                .findFirst();
    }

    /** Whether {@code method} carries the given annotation, addressed by its fully qualified name. */
    public static boolean hasNamed(JavaMethod method, String annotationFullName) {
        return findNamed(method, annotationFullName).isPresent();
    }

    /**
     * The annotation on {@code method} addressed by its fully qualified name.
     *
     * <p>Addressing by name rather than by {@code Class} is what lets a rule inspect a framework
     * annotation - {@code @Cacheable}, {@code @PreAuthorize} - that {@code libs/arch-test} must
     * not put on its own compile classpath.
     */
    public static Optional<JavaAnnotation<JavaMethod>> findNamed(JavaMethod method, String annotationFullName) {
        return method.getAnnotations().stream()
                .filter(a -> a.getRawType().getFullName().equals(annotationFullName))
                .findFirst();
    }

    /** A string member, falling back to {@code fallback} when absent. */
    public static String string(JavaAnnotation<?> annotation, String member, String fallback) {
        return annotation.get(member).map(Object::toString).orElse(fallback);
    }

    /** A string-array member, empty when absent. */
    public static List<String> strings(JavaAnnotation<?> annotation, String member) {
        return annotation
                .get(member)
                .filter(Object[].class::isInstance)
                .map(Object[].class::cast)
                .map(values -> List.of(values).stream().map(Object::toString).toList())
                .orElseGet(List::of);
    }

    /** The name of an enum member, falling back to {@code fallback} when absent. */
    public static String enumName(JavaAnnotation<?> annotation, String member, String fallback) {
        return annotation
                .get(member)
                .filter(JavaEnumConstant.class::isInstance)
                .map(JavaEnumConstant.class::cast)
                .map(JavaEnumConstant::name)
                .orElse(fallback);
    }

    /** A boolean member, falling back to {@code fallback} when absent. */
    public static boolean bool(JavaAnnotation<?> annotation, String member, boolean fallback) {
        return annotation
                .get(member)
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(fallback);
    }

    /** An int member, falling back to {@code fallback} when absent. */
    public static int integer(JavaAnnotation<?> annotation, String member, int fallback) {
        return annotation
                .get(member)
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .orElse(fallback);
    }

    /** A long member, falling back to {@code fallback} when absent. */
    public static long longValue(JavaAnnotation<?> annotation, String member, long fallback) {
        return annotation
                .get(member)
                .filter(Long.class::isInstance)
                .map(Long.class::cast)
                .orElse(fallback);
    }

    /** A {@code Class<?>} member, as the imported model's view of that type. */
    public static Optional<JavaClass> type(JavaAnnotation<?> annotation, String member) {
        return annotation.get(member).filter(JavaClass.class::isInstance).map(JavaClass.class::cast);
    }
}
