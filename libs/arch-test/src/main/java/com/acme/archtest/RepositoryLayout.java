package com.acme.archtest;

import java.nio.file.Files;
import java.nio.file.Path;

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

    /** Whether a document matching {@code <directory>/<idPrefix>*.md} exists. */
    public static boolean documentExists(String directory, String idPrefix) {
        Path dir = root().resolve(directory);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith(idPrefix) && name.endsWith(".md"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read " + dir, e);
        }
    }
}
