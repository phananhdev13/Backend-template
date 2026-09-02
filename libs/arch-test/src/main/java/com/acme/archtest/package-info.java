/**
 * The rules that make the annotations in {@code libs/kernel} load-bearing.
 *
 * <p>An annotation nobody checks is a comment with syntax. Everything here exists so
 * that a role, a principle reference or an event contract is a claim the build can
 * falsify.
 *
 * <p>Rules are grouped into classes by subject and consumed by a service as ArchUnit
 * rule libraries:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.acme.order", importOptions = DoNotIncludeTests.class)
 * class ArchitectureTest {
 *     @ArchTest
 *     static final ArchTests roles = ArchTests.in(RoleRules.class);
 *     @ArchTest
 *     static final ArchTests layering = ArchTests.in(LayeringRules.class);
 * }
 * }</pre>
 *
 * <p>Every failure message names the principle document that explains the rule. A
 * developer or an agent that hits one should be able to read why without asking anyone.
 *
 * <h2>On {@code allowEmptyShould}</h2>
 *
 * <p>ArchUnit fails a rule that matched no class at all, on the reasoning that a rule
 * checking nothing is usually a rule whose subject was renamed away. Most rules here
 * disable that, and must: a service with no cache, no task queue, no workflow and no
 * object storage is perfectly ordinary, and those rules legitimately match nothing.
 *
 * <p>It is a decision per rule, not a default to copy. Where an empty match would itself
 * be a defect - {@link RoleRules#everyClassDeclaresARole} and its neighbours, which apply
 * to every class a service has - leave it on, so that a misconfigured
 * {@code @AnalyzeClasses} package fails loudly instead of turning the whole suite green
 * for the emptiest possible reason. When you add a rule, ask which kind it is and say so.
 */
@NullMarked
package com.acme.archtest;

import org.jspecify.annotations.NullMarked;
