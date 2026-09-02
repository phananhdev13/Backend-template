package com.acme.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code platform/bom} re-publishes this repository's modules for services extracted from it, so a
 * library missing from it is a library nobody outside the reactor can resolve.
 *
 * <p>The root {@code pom.xml} has claimed since the beginning that "ArchUnit test
 * PlatformBomCoversAllLibrariesTest fails the build if they drift". It did not exist. Nothing kept
 * the two lists in step, and the failure would have surfaced only in a downstream consumer's build
 * - the furthest possible place from the edit that caused it. This is that test.
 */
class PlatformBomCoversAllLibrariesTest {

    private static final Pattern MODULE = Pattern.compile("<module>libs/([^<]+)</module>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    @Test
    void everyLibraryModuleIsPublishedByThePlatformBom() throws IOException {
        Path root = RepositoryLayout.root();
        List<String> libraries = matches(MODULE, read(root.resolve("pom.xml")));
        List<String> published = matches(ARTIFACT, read(root.resolve("platform/bom/pom.xml")));

        assertThat(libraries).as("libs/* modules declared in the root pom").isNotEmpty();
        assertThat(published)
                .as("every libs/* module is in platform/bom, or a service extracted from this "
                        + "template cannot resolve it")
                .containsAll(libraries);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static List<String> matches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results().map(result -> result.group(1)).toList();
    }
}
