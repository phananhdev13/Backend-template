/**
 * The architectural vocabulary of this codebase.
 *
 * <p>Every annotation here answers one question about a class: <em>what job does it
 * do in the architecture?</em> That answer is written once, at the class, and is then
 * read by three different audiences:
 *
 * <ul>
 *   <li><b>People</b> - the annotation names the role, so a reader knows the rules that
 *       apply before reading the body.
 *   <li><b>Tests</b> - {@code libs/arch-test} selects classes by these annotations and
 *       enforces the layering, naming and dependency rules that the role implies.
 *   <li><b>Documentation</b> - the {@code principle} recorded on each role points at the
 *       document in {@code docs/principles} that explains why the rule exists.
 * </ul>
 *
 * <p>A class with no role annotation is not "unclassified", it is a build failure.
 * See {@code docs/principles/P-010-annotated-architecture.md}.
 */
@NullMarked
package com.acme.kernel.arch;

import org.jspecify.annotations.NullMarked;
