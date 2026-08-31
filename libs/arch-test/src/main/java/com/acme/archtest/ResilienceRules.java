package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.ImplementsPrinciple;
import com.acme.kernel.arch.OutboundAdapter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * Every call that can hang has an answer for what happens when it does: P-051.
 *
 * <p>The default in most HTTP and database clients is to wait indefinitely. One slow
 * dependency then consumes every request thread, and a service that is merely degraded
 * upstream becomes unavailable here. The failure is not the timeout; the failure is
 * having none.
 *
 * <p>This cannot be checked by reading bytecode - the timeout usually lives in
 * configuration. What is checked is that the decision was taken deliberately and is
 * findable: an adapter that talks to something remote must claim P-051, and the claim
 * points a reviewer at where the budget is set.
 */
public final class ResilienceRules {

    private static final List<String> REMOTE_KINDS =
            List.of(AdapterKind.HTTP_CLIENT.name(), AdapterKind.MESSAGING.name(), AdapterKind.CACHE.name());

    @ArchTest
    public static final ArchRule remoteCallsDeclareTimeouts = classes()
            .that()
            .areAnnotatedWith(OutboundAdapter.class)
            .should(claimTheResiliencePrincipleWhenRemote())
            .allowEmptyShould(true)
            .as("adapters that call out declare their failure behaviour (P-051)")
            .because("an unbounded wait turns a dependency's bad day into an outage here. "
                    + "See docs/principles/P-051-remote-call-resilience.md");

    private ResilienceRules() {}

    private static ArchCondition<JavaClass> claimTheResiliencePrincipleWhenRemote() {
        return new ArchCondition<>("claim P-051 when the adapter talks to a remote system") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Annotations.find(item, OutboundAdapter.class).ifPresent(adapter -> {
                    String kind = Annotations.enumName(adapter, "kind", "");
                    if (!REMOTE_KINDS.contains(kind)) {
                        return;
                    }
                    boolean claimed = Annotations.find(item, ImplementsPrinciple.class)
                            .map(principle ->
                                    Annotations.strings(principle, "value").contains("P-051"))
                            .orElse(false);
                    if (claimed) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            ("%s is an outbound adapter of kind %s. Set a connect and read timeout, decide "
                                            + "what happens on failure, and record it with "
                                            + "@ImplementsPrinciple(value = \"P-051\", note = \"...\").")
                                    .formatted(item.getName(), kind)));
                });
            }
        };
    }
}
