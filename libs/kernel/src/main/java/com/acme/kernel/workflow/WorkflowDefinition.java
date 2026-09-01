package com.acme.kernel.workflow;

import com.acme.kernel.arch.ArchRole;
import com.acme.kernel.arch.Layer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A durable, long-running orchestration - Temporal's {@code @WorkflowInterface}
 * implementation, one step further from a use case than a use case is from the domain.
 *
 * <p>A use case commits, once, inside one transaction. A workflow definition coordinates
 * a process that can span minutes, days or months, surviving a worker restart partway
 * through - "the deployment pipeline for this agent version," not "register this agent."
 * That durability is bought by determinism: Temporal replays a workflow's history to
 * reconstruct its state, so a workflow definition can hold no dependency Spring would
 * inject, read no ambient clock or random source, and call out to the world only through
 * an activity stub - never directly. See the {@code temporal} skill for what that rules
 * out in practice.
 *
 * <p>Framework-free like the domain, but not the domain: a workflow definition orchestrates
 * across use cases and aggregates the way a use case orchestrates within one, and depends
 * on Temporal's own workflow API to do it - {@code io.temporal.workflow.Workflow} static
 * methods for a timer, a signal wait, or an activity stub. That single, deliberate
 * dependency is the one exception {@code WorkflowRules.workflowDefinitionsStayFrameworkFree}
 * carves out of an otherwise domain-strict "no framework" rule.
 *
 * <p>This annotation carries no Spring meaning on purpose, the same reason
 * {@link com.acme.kernel.arch.UseCase} does not - a workflow definition is never a Spring
 * bean. Temporal constructs one instance per execution itself; see
 * {@code Worker.registerWorkflowImplementationTypes}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ArchRole(layer = Layer.APPLICATION, principle = "P-033")
public @interface WorkflowDefinition {

    /**
     * Stable identifier, {@code WF-<CONTEXT>-<NNN>}, resolving to a file under
     * {@code docs/use-cases}. Checked for existence by
     * {@code TraceabilityRules.everyWorkflowDefinitionIsDocumented()}.
     */
    String id();

    /** What the workflow accomplishes, phrased as the business would phrase it. */
    String value() default "";
}
