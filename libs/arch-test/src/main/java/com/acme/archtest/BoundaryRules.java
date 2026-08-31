package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.Internal;
import com.acme.kernel.arch.PublicApi;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;

/**
 * Feature modules keep their internals: P-100.
 *
 * <p>A vertical slice is only a boundary while something enforces it. Java's
 * {@code public} cannot express "public within this feature", so the intent is written
 * with {@code @Internal} and enforced here. Without this rule, the first cross-module
 * shortcut is invisible, and by the time it is not, the slice has become a package name.
 */
public final class BoundaryRules {

    /** Package segments that mark the inside of a feature module rather than the module itself. */
    private static final List<String> LAYER_SEGMENTS = List.of(".domain", ".application", ".adapter", ".config");

    @ArchTest
    public static final ArchRule internalTypesStayInTheirModule = classes()
            .that()
            .areAnnotatedWith(Internal.class)
            .should(onlyBeUsedInsideTheirOwnModule())
            .allowEmptyShould(true)
            .as("@Internal types are used only by their own feature module (P-100)")
            .because("a type marked internal is one the owning module expects to change without "
                    + "consulting anyone. See docs/principles/P-100-vertical-slice-modules.md");

    @ArchTest
    public static final ArchRule crossModuleTypesArePublicApi = classes()
            .that()
            .resideInAPackage("..")
            .should(beMarkedPublicApiWhenUsedAcrossModules())
            .allowEmptyShould(true)
            .as("types used by another feature module are marked @PublicApi (P-100)")
            .because("Java's `public` means reachable, not supported. Without the marker the owning "
                    + "module cannot tell which of its types it is free to change. "
                    + "See docs/principles/P-100-vertical-slice-modules.md");

    @ArchTest
    public static final ArchRule noCyclesBetweenModules = SlicesRuleDefinition.slices()
            .matching("com.acme.(*).(*)..")
            .should()
            .beFreeOfCycles()
            .as("feature modules form no dependency cycles (P-100)")
            .because("two modules that depend on each other are one module with a package boundary "
                    + "drawn through the middle, and neither can be changed or deployed alone. "
                    + "See docs/principles/P-100-vertical-slice-modules.md");

    private BoundaryRules() {}

    private static ArchCondition<JavaClass> beMarkedPublicApiWhenUsedAcrossModules() {
        return new ArchCondition<>("be marked @PublicApi when another module depends on it") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Classes.structural().test(item)
                        || Annotations.has(item, PublicApi.class)
                        || Annotations.has(item, Internal.class)) {
                    return;
                }
                String owner = moduleOf(item);
                for (Dependency dependency : item.getDirectDependenciesToSelf()) {
                    JavaClass origin = dependency.getOriginClass().getBaseComponentType();
                    if (origin.equals(item) || moduleOf(origin).equals(owner)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s is used by %s in another feature module but is marked neither "
                                            + "@PublicApi nor @Internal. Decide which it is: a supported "
                                            + "surface you will keep stable, or something that module "
                                            + "should not be reaching for.")
                                    .formatted(item.getName(), origin.getName())));
                    return;
                }
            }
        };
    }

    /**
     * The feature module a class belongs to: everything up to the layer segment.
     *
     * <p>{@code com.acme.order.ordering.domain.Order} belongs to {@code com.acme.order.ordering}.
     */
    static String moduleOf(JavaClass type) {
        String packageName = type.getPackageName();
        for (String segment : LAYER_SEGMENTS) {
            int index = packageName.indexOf(segment);
            if (index > 0) {
                return packageName.substring(0, index);
            }
        }
        return packageName;
    }

    private static ArchCondition<JavaClass> onlyBeUsedInsideTheirOwnModule() {
        return new ArchCondition<>("only be used inside their own feature module") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String owner = moduleOf(item);
                for (Dependency dependency : item.getDirectDependenciesToSelf()) {
                    JavaClass origin = dependency.getOriginClass().getBaseComponentType();
                    if (origin.equals(item) || moduleOf(origin).equals(owner)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s is @Internal to %s but is used by %s. Either promote it to the module's "
                                            + "@PublicApi surface and accept the compatibility obligation, "
                                            + "or communicate through an event instead. %s")
                                    .formatted(item.getName(), owner, origin.getName(), dependency.getDescription())));
                }
            }
        };
    }
}
