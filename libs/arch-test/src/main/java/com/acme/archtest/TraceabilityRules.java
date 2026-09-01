package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.Adr;
import com.acme.kernel.arch.ImplementsPrinciple;
import com.acme.kernel.arch.ReadModel;
import com.acme.kernel.arch.UseCase;
import com.acme.kernel.workflow.WorkflowDefinition;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * Documentation references resolve: P-000.
 *
 * <p>These rules are what stop the documentation tree from decaying into fiction. A
 * principle identifier in code that points at a file nobody wrote, or a use case whose
 * specification was deleted in a tidy-up, both fail the build here rather than being
 * discovered by the next person who follows the pointer.
 *
 * <p>The link is checked in one direction only - from code to document. A document with
 * no implementation is reported by {@code tools/principle-map.sh}, not failed, because
 * writing the principle before the code is the intended order.
 */
public final class TraceabilityRules {

    @ArchTest
    public static final ArchRule everyUseCaseIsDocumented = classes()
            .that()
            .areAnnotatedWith(UseCase.class)
            .should(resolveTheirDocument(UseCase.class, "id", "docs/use-cases"))
            .allowEmptyShould(true)
            .as("every use case resolves to a specification (P-000)")
            .because("the identifier is the thread from a log line back to what was asked for. "
                    + "See docs/principles/P-000-repository-is-the-only-context.md");

    @ArchTest
    public static final ArchRule everyReadModelIsDocumented = classes()
            .that()
            .areAnnotatedWith(ReadModel.class)
            .should(resolveTheirDocument(ReadModel.class, "id", "docs/use-cases"))
            .allowEmptyShould(true)
            .as("every read model resolves to a specification (P-000)")
            .because("queries are contracts with a caller too. "
                    + "See docs/principles/P-000-repository-is-the-only-context.md");

    @ArchTest
    public static final ArchRule everyWorkflowDefinitionIsDocumented = classes()
            .that()
            .areAnnotatedWith(WorkflowDefinition.class)
            .should(resolveTheirDocument(WorkflowDefinition.class, "id", "docs/use-cases"))
            .allowEmptyShould(true)
            .as("every workflow definition resolves to a specification (P-000)")
            .because("a durable process spanning days is worth writing down at least as much as a "
                    + "use case that commits in one transaction. "
                    + "See docs/principles/P-000-repository-is-the-only-context.md");

    @ArchTest
    public static final ArchRule principleReferencesResolve = classes()
            .that()
            .areAnnotatedWith(ImplementsPrinciple.class)
            .should(resolveEveryReferenceIn(ImplementsPrinciple.class, "docs/principles"))
            .allowEmptyShould(true)
            .as("@ImplementsPrinciple references resolve to a principle document (P-000)")
            .because("an identifier that resolves to nothing is worse than no identifier: it looks "
                    + "checked. See docs/principles/P-000-repository-is-the-only-context.md");

    @ArchTest
    public static final ArchRule adrReferencesResolve = classes()
            .that()
            .areAnnotatedWith(Adr.class)
            .should(resolveEveryReferenceIn(Adr.class, "docs/adr"))
            .allowEmptyShould(true)
            .as("@Adr references resolve to a decision record (P-000)")
            .because("the pointer exists so the next reader does not delete the code and rediscover "
                    + "the reason. See docs/principles/P-000-repository-is-the-only-context.md");

    private TraceabilityRules() {}

    /** Strips an identifier down to the filename prefix used on disk: ADR-0007 becomes 0007. */
    private static String filePrefix(String identifier, String directory) {
        return directory.endsWith("adr") ? identifier.replaceFirst("^ADR-", "") : identifier;
    }

    private static ArchCondition<JavaClass> resolveTheirDocument(
            Class<? extends java.lang.annotation.Annotation> annotation, String member, String directory) {
        return new ArchCondition<>("resolve to a document in " + directory) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Annotations.find(item, annotation).ifPresent(found -> {
                    String id = Annotations.string(found, member, "");
                    if (!id.isBlank() && RepositoryLayout.documentExists(directory, id)) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "%s declares %s = \"%s\", but no %s/%s*.md exists."
                                    .formatted(item.getName(), member, id, directory, id)));
                });
            }
        };
    }

    private static ArchCondition<JavaClass> resolveEveryReferenceIn(
            Class<? extends java.lang.annotation.Annotation> annotation, String directory) {
        return new ArchCondition<>("resolve every reference to a document in " + directory) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Annotations.find(item, annotation).ifPresent(found -> {
                    List<String> identifiers = Annotations.strings(found, "value");
                    for (String identifier : identifiers) {
                        String prefix = filePrefix(identifier, directory);
                        if (RepositoryLayout.documentExists(directory, prefix)) {
                            continue;
                        }
                        events.add(SimpleConditionEvent.violated(
                                item,
                                "%s references \"%s\", but no %s/%s*.md exists."
                                        .formatted(item.getName(), identifier, directory, prefix)));
                    }
                });
            }
        };
    }
}
