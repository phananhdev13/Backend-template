package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.arch.ReadModel;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
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

    private static final List<String> MUTATING_PREFIXES =
            List.of("save", "delete", "remove", "insert", "update", "persist", "merge", "publish", "send", "store");

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
}
