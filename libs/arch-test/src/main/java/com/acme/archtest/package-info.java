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
 */
@NullMarked
package com.acme.archtest;

import org.jspecify.annotations.NullMarked;
