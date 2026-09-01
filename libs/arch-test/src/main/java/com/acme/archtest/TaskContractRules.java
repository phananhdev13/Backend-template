package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.Adr;
import com.acme.kernel.event.Idempotent;
import com.acme.kernel.task.Task;
import com.acme.kernel.task.TaskContract;
import com.acme.kernel.task.TaskHandler;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task queue contracts must be internally consistent: P-131.
 *
 * <p>A classic queue has no delivery-guarantee choice the way an event stream does -
 * every failure path a broker has (a crash, a nack, a container restart mid-handling)
 * redelivers - so the one thing worth checking beyond the event rules is that every
 * handler accepts that unconditionally, and that the queue a task claims is unambiguous.
 */
public final class TaskContractRules {

    @ArchTest
    public static final ArchRule everyTaskDeclaresAContract = classes()
            .that()
            .areAssignableTo(Task.class)
            .and()
            .areNotInterfaces()
            .should(beAnnotatedWithTaskContract())
            .allowEmptyShould(true)
            .as("every task declares its queue contract (P-131)")
            .because("a task with no declared queue, retry budget or dead-letter destination leaves "
                    + "those decisions to whoever provisions the broker. "
                    + "See docs/principles/P-131-task-queues.md");

    @ArchTest
    public static final ArchRule everyTaskHandlerIsIdempotent = classes()
            .that()
            .areAnnotatedWith(TaskHandler.class)
            .should(beAnnotatedWithIdempotent())
            .allowEmptyShould(true)
            .as("every task handler is idempotent (P-131)")
            .because("a task queue redelivers on every failure path a broker has, with no "
                    + "at-most-once escape hatch. A handler that is not safe to repeat will repeat "
                    + "anyway. See docs/principles/P-071-idempotency.md");

    @ArchTest
    public static final ArchRule handledTaskDeclaresAContract = classes()
            .that()
            .areAnnotatedWith(TaskHandler.class)
            .should(handleATaskThatDeclaresAContract())
            .allowEmptyShould(true)
            .as("a task handler's task declares a queue contract (P-131)")
            .because("the handler and the provisioner both need the queue name from the task's own "
                    + "@TaskContract; a task with none cannot be provisioned or consumed. "
                    + "See docs/principles/P-131-task-queues.md");

    @ArchTest
    public static final ArchRule personalDataTasksCarryAnAdr = classes()
            .that()
            .areAnnotatedWith(TaskContract.class)
            .should(justifyPersonalData())
            .allowEmptyShould(true)
            .as("a task carrying personal data carries an ADR (P-131)")
            .because("a task that exhausts its retries lands on the dead-letter queue and sits there "
                    + "until someone looks at it; personal data surviving past the retry budget is a "
                    + "retention decision someone has to own. "
                    + "See docs/principles/P-131-task-queues.md");

    /** No two task types may provision the same queue - the provisioner has nothing to disambiguate them with. */
    @ArchTest
    public static void queueNamesAreUnique(JavaClasses classes) {
        Map<String, List<String>> byQueue = new HashMap<>();
        for (JavaClass type : classes) {
            Annotations.find(type, TaskContract.class).ifPresent(contract -> {
                String queue = Annotations.string(contract, "queue", "?");
                byQueue.computeIfAbsent(queue, key -> new ArrayList<>()).add(type.getName());
            });
        }
        List<String> collisions = byQueue.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "\"" + entry.getKey() + "\" is claimed by " + entry.getValue())
                .toList();
        if (!collisions.isEmpty()) {
            throw new AssertionError("Two task types claim the same queue, so a consumer cannot tell "
                    + "which contract governs it. Give one of them its own queue.\n  "
                    + String.join("\n  ", collisions));
        }
    }

    private TaskContractRules() {}

    private static ArchCondition<JavaClass> beAnnotatedWithTaskContract() {
        return new ArchCondition<>("declare a @TaskContract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Annotations.has(item, TaskContract.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s implements Task but declares no @TaskContract. Add one naming the queue, "
                                        .formatted(item.getName())
                                + "the retry budget and the backoff."));
            }
        };
    }

    private static ArchCondition<JavaClass> beAnnotatedWithIdempotent() {
        return new ArchCondition<>("declare @Idempotent") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Annotations.has(item, Idempotent.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s is a @TaskHandler but is not @Idempotent. Name the field that identifies "
                                        + "a logical delivery, and make the handler safe to repeat.")
                                .formatted(item.getName())));
            }
        };
    }

    private static ArchCondition<JavaClass> handleATaskThatDeclaresAContract() {
        return new ArchCondition<>("handle a task that declares a @TaskContract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaClass> handled =
                        Annotations.find(item, TaskHandler.class).flatMap(a -> Annotations.type(a, "handles"));
                if (handled.isEmpty() || Annotations.has(handled.get(), TaskContract.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s handles %s, which declares no @TaskContract."
                                .formatted(item.getName(), handled.get().getSimpleName())));
            }
        };
    }

    private static ArchCondition<JavaClass> justifyPersonalData() {
        return new ArchCondition<>("justify personal data with an ADR") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> contract = Annotations.find(item, TaskContract.class);
                if (contract.isEmpty() || !Annotations.bool(contract.get(), "containsPersonalData", false)) {
                    return;
                }
                if (Annotations.has(item, Adr.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s carries personal data. Record the decision in docs/adr and reference it with @Adr."
                                .formatted(item.getName())));
            }
        };
    }
}
