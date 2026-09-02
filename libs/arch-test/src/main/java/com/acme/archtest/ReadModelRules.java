package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.AggregateRoot;
import com.acme.kernel.arch.OutputPort;
import com.acme.kernel.arch.ReadModel;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Locale;

/**
 * Reads change nothing: P-032.
 *
 * <p>Read models are allowed to skip the domain and project straight from the database.
 * That licence is granted on one condition - that they change nothing - because a query
 * that writes is a use case that has escaped every rule use cases are subject to:
 * no transaction boundary, no authorisation check, no event.
 */
public final class ReadModelRules {

    /** Referenced by name: arch-test does not depend on Spring. */
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    private static final List<String> MUTATING_PREFIXES = List.of(
            "save", "delete", "remove", "insert", "update", "persist", "merge", "publish", "send", "store", "record");

    @ArchTest
    public static final ArchRule readModelsHaveNoSideEffects = noClasses()
            .that()
            .areAnnotatedWith(ReadModel.class)
            .should()
            .callMethodWhere(mutatingCall())
            .allowEmptyShould(true)
            .as("read models call nothing that mutates state (P-032)")
            .because("a query that writes has become an unaudited, untransacted use case. "
                    + "See docs/principles/P-032-reads-and-writes-shaped-separately.md");

    /**
     * P-032 has always said a read model holds no mutable state and, if transactional, is
     * {@code readOnly}. Neither was implemented, so both were prose. The second matters at runtime
     * rather than only structurally: {@code readOnly = true} is what lets Hibernate skip dirty
     * checking and a replica route the query away from the primary, and a read model that omits it
     * silently gives up both.
     */
    @ArchTest
    public static final ArchRule readModelsAreReadOnly = classes()
            .that()
            .areAnnotatedWith(ReadModel.class)
            .should(holdNoMutableStateAndReadOnlyTransactions())
            .allowEmptyShould(true)
            .as("read models hold no mutable state and read in read-only transactions (P-032)")
            .because("a field a query can reassign is state two concurrent callers share, and a "
                    + "read-write transaction for a read gives up dirty-check skipping and replica "
                    + "routing. See docs/principles/P-032-reads-and-writes-shaped-separately.md");

    /**
     * The other half P-032 promised: a read model that reaches for the write side's own
     * collaborators has stopped being a projection. Loading through the aggregate's repository
     * re-imports the mapping and the lazy-loading the separate read path existed to avoid, and
     * handing an {@code @AggregateRoot} back to a caller exports the write model as the read
     * contract, so any change to it becomes an API change.
     */
    @ArchTest
    public static final ArchRule readModelsDoNotBorrowTheWriteSide = classes()
            .that()
            .areAnnotatedWith(ReadModel.class)
            .should(useNeitherRepositoryPortNorAggregate())
            .allowEmptyShould(true)
            .as("read models use neither a repository port nor an aggregate type (P-032)")
            .because("a projection that loads through the write side inherits the mapping it exists "
                    + "to avoid, and one that returns an aggregate makes the write model the read "
                    + "contract. See docs/principles/P-032-reads-and-writes-shaped-separately.md");

    private ReadModelRules() {}

    private static DescribedPredicate<JavaMethodCall> mutatingCall() {
        return new DescribedPredicate<>("a call to a method whose name implies mutation") {
            @Override
            public boolean test(JavaMethodCall call) {
                String name = call.getTarget().getName().toLowerCase(Locale.ROOT);
                return MUTATING_PREFIXES.stream().anyMatch(name::startsWith);
            }
        };
    }

    private static ArchCondition<JavaClass> holdNoMutableStateAndReadOnlyTransactions() {
        return new ArchCondition<>("hold no mutable state and read in read-only transactions") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getFields().stream()
                        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                        .filter(field -> !field.getModifiers().contains(JavaModifier.FINAL))
                        .forEach(field -> events.add(SimpleConditionEvent.violated(
                                item,
                                ("%s has a non-final field '%s'. A @ReadModel answers questions; a "
                                                + "field it can reassign is state two concurrent callers "
                                                + "share.")
                                        .formatted(item.getName(), field.getName()))));
                Annotations.findNamed(item, SPRING_TRANSACTIONAL)
                        .ifPresent(found -> reportReadWrite(item, found, item.getName(), events));
                for (JavaMethod method : item.getMethods()) {
                    Annotations.findNamed(method, SPRING_TRANSACTIONAL)
                            .ifPresent(found ->
                                    reportReadWrite(item, found, item.getName() + "#" + method.getName(), events));
                }
            }

            private void reportReadWrite(
                    JavaClass item, JavaAnnotation<?> transactional, String where, ConditionEvents events) {
                if (Annotations.bool(transactional, "readOnly", false)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s is @Transactional without readOnly = true. The read then takes a "
                                        + "read-write transaction, which gives up Hibernate's dirty-check "
                                        + "skipping and any routing to a replica.")
                                .formatted(where)));
            }
        };
    }

    private static ArchCondition<JavaClass> useNeitherRepositoryPortNorAggregate() {
        return new ArchCondition<>("depend on no repository port and expose no aggregate") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaClass dependency : item.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> dependency.getTargetClass())
                        .toList()) {
                    if (Annotations.has(dependency, OutputPort.class)
                            && dependency.getSimpleName().endsWith("Repository")) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                ("%s depends on the repository port %s. A projection queries the "
                                                + "database directly - going through the write side's "
                                                + "repository re-imports the mapping and the lazy loading "
                                                + "this read path exists to avoid.")
                                        .formatted(item.getName(), dependency.getSimpleName())));
                    }
                }
                for (JavaMethod method : item.getMethods()) {
                    aggregateReturned(method)
                            .ifPresent(aggregate -> events.add(SimpleConditionEvent.violated(
                                    item,
                                    ("%s#%s returns the aggregate %s. That makes the write model the "
                                                    + "read contract, so every change to the aggregate "
                                                    + "becomes an API change. Return a projection record "
                                                    + "instead.")
                                            .formatted(item.getName(), method.getName(), aggregate))));
                }
            }

            /** The aggregate a method hands back, named rather than its wrapper. */
            private java.util.Optional<String> aggregateReturned(JavaMethod method) {
                if (Annotations.has(method.getRawReturnType(), AggregateRoot.class)) {
                    return java.util.Optional.of(method.getRawReturnType().getSimpleName());
                }
                // Optional<Order>, List<Order> and friends: the aggregate is the type argument, so
                // checking only the raw return type would miss the shape a query most often uses -
                // and naming the wrapper in the failure would send the reader to the wrong type.
                if (!(method.getReturnType() instanceof JavaParameterizedType parameterized)) {
                    return java.util.Optional.empty();
                }
                return parameterized.getActualTypeArguments().stream()
                        .map(JavaType::toErasure)
                        .filter(argument -> Annotations.has(argument, AggregateRoot.class))
                        .map(argument -> "%s (inside %s)"
                                .formatted(
                                        argument.getSimpleName(),
                                        method.getRawReturnType().getSimpleName()))
                        .findFirst();
            }
        };
    }
}
