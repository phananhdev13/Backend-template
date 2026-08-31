package com.acme.archtest;

import com.acme.kernel.arch.ArchRole;
import com.acme.kernel.arch.Layer;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Optional;

/**
 * Resolves the architectural role of a class from the annotations it carries.
 *
 * <p>Roles are discovered through the {@link ArchRole} meta-annotation rather than from
 * a hard-coded list, so a role added to {@code libs/kernel} is enforced by every rule
 * here without any of them being edited.
 */
public final class Roles {

    private Roles() {}

    /** The layer this class belongs to, or empty when it declares no role. */
    public static Optional<Layer> layerOf(JavaClass type) {
        return roleAnnotationOf(type)
                .flatMap(role -> Annotations.find(role, ArchRole.class))
                .map(archRole -> Layer.valueOf(Annotations.enumName(archRole, "layer", Layer.DOMAIN.name())));
    }

    /** The principle governing this class's role, or empty when it declares no role. */
    public static Optional<String> principleOf(JavaClass type) {
        return roleAnnotationOf(type)
                .flatMap(role -> Annotations.find(role, ArchRole.class))
                .map(archRole -> Annotations.string(archRole, "principle", ""));
    }

    /** The role annotation's own type, for example {@code UseCase}, or empty when there is none. */
    public static Optional<JavaClass> roleAnnotationOf(JavaClass type) {
        return type.getAnnotations().stream()
                .map(annotation -> annotation.getRawType())
                .filter(raw -> Annotations.has(raw, ArchRole.class))
                .findFirst();
    }

    /** Whether the class declares any architectural role. */
    public static boolean declaresRole(JavaClass type) {
        return roleAnnotationOf(type).isPresent();
    }

    /** Matches classes carrying any role annotation. */
    public static DescribedPredicate<JavaClass> withAnyRole() {
        return new DescribedPredicate<>("annotated with an architectural role") {
            @Override
            public boolean test(JavaClass type) {
                return declaresRole(type);
            }
        };
    }

    /** Matches classes whose role places them in the given layer. */
    public static DescribedPredicate<JavaClass> inLayer(Layer layer) {
        return new DescribedPredicate<>("in layer " + layer) {
            @Override
            public boolean test(JavaClass type) {
                return layerOf(type).filter(layer::equals).isPresent();
            }
        };
    }
}
