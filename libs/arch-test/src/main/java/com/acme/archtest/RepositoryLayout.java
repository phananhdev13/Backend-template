package com.acme.archtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Locates the repository root from inside a module's test run.
 *
 * <p>Maven runs each module with its own working directory, so rules that check a file
 * checked in at the repository root - an event schema, a principle document - have to
 * walk up to find it. The marker is {@code docs/principles}: it exists only at the root
 * and its absence means the documentation tree has been moved, which those rules should
 * report rather than silently pass.
 */
public final class RepositoryLayout {

    private static final String MARKER = "docs/principles";

    private RepositoryLayout() {}

    /** The repository root. */
    public static Path root() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root: no ancestor of "
                + Path.of("").toAbsolutePath() + " contains " + MARKER
                + ". Structural rules that resolve documentation and schema paths cannot run.");
    }

    /** Whether a repository-relative path exists. */
    public static boolean exists(String relativePath) {
        return Files.exists(root().resolve(relativePath));
    }

    /**
     * Whether a document named {@code <directory>/<id>-*.md} exists.
     *
     * <p>The separator is required, and that is the whole point: a bare {@code startsWith} let a
     * truncated identifier resolve against its neighbour, so {@code @UseCase(id = "U")} was
     * satisfied by {@code UC-AGT-001-….md}, {@code @Adr("ADR-0")} by {@code 0001-….md}, and
     * {@code @ImplementsPrinciple("P")} by {@code P-000-….md}. Every traceability rule reads
     * through here, so a half-deleted identifier passed all of them - and {@code @Adr} is the
     * sanctioned suppression for several other rules, which made a suppression pointing at no
     * real decision record acceptable too.
     */
    public static boolean documentExists(String directory, String id) {
        Path dir = root().resolve(directory);
        if (!Files.isDirectory(dir) || id.isBlank()) {
            return false;
        }
        String idPrefix = id + "-";
        try (var entries = Files.list(dir)) {
            // Path.getFileName() is declared to return null for a zero-element path (a root),
            // which a listing of a real directory's entries never produces - but the type says
            // otherwise, and SpotBugs is right to ask for the check.
            return entries.map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .anyMatch(name -> name.startsWith(idPrefix) && name.endsWith(".md"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read " + dir, e);
        }
    }
}
