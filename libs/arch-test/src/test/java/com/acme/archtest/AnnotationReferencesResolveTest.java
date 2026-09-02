package com.acme.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every {@code @ImplementsPrinciple} and {@code @Adr} identifier in the repository resolves.
 *
 * <p>{@code TraceabilityRules} already checks this, but only for what an
 * {@code ArchitectureTest} imports - and each one is scoped to its own service package
 * ({@code com.acme.order}, {@code com.acme.agentfactory}). Nothing in {@code libs/} was ever
 * examined, so {@code @ImplementsPrinciple("P-130")} on {@code CacheContract} and
 * {@code @ImplementsPrinciple("P-071")} on {@code Idempotent} - claims the generated principle map
 * counts - could dangle after a rename with a green build. That is the exact failure
 * {@code TraceabilityRules} says it prevents: "an identifier that resolves to nothing is worse
 * than no identifier: it looks checked".
 *
 * <p>This reads source rather than bytecode, which is what lets one test cover every module at
 * once instead of requiring an {@code ArchitectureTest} in each library.
 */
class AnnotationReferencesResolveTest {

    private static final Pattern PRINCIPLE = Pattern.compile("@ImplementsPrinciple\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern ADR = Pattern.compile("@Adr\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** Identifier shapes, so a malformed one fails as malformed rather than resolving by luck. */
    private static final Pattern PRINCIPLE_ID = Pattern.compile("P-\\d{3}");

    private static final Pattern ADR_ID = Pattern.compile("ADR-\\d{4}");

    @Test
    void everyPrincipleAndAdrIdentifierNamedInCodeResolvesToADocument() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path source : javaSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            String withoutComments = stripComments(text);

            for (String id : principleIds(withoutComments)) {
                if (!PRINCIPLE_ID.matcher(id).matches()) {
                    problems.add("%s: @ImplementsPrinciple(\"%s\") is not a P-NNN identifier".formatted(source, id));
                } else if (!RepositoryLayout.documentExists("docs/principles", id)) {
                    problems.add("%s: @ImplementsPrinciple(\"%s\") names no document in docs/principles"
                            .formatted(source, id));
                }
            }
            for (String id : adrIds(withoutComments)) {
                if (!ADR_ID.matcher(id).matches()) {
                    problems.add("%s: @Adr(\"%s\") is not an ADR-NNNN identifier".formatted(source, id));
                } else if (!RepositoryLayout.documentExists("docs/adr", id.replaceFirst("^ADR-", ""))) {
                    problems.add("%s: @Adr(\"%s\") names no document in docs/adr".formatted(source, id));
                }
            }
        }
        assertThat(problems)
                .as("an identifier that resolves to nothing is worse than no identifier: it looks checked")
                .isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        Path root = RepositoryLayout.root();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }
    }

    /**
     * {@code @ImplementsPrinciple} takes a {@code String[]}, so one annotation may name several
     * principles: {@code value = {"P-051", "P-120"}}.
     */
    private static List<String> principleIds(String text) {
        List<String> ids = new ArrayList<>();
        Matcher annotation = PRINCIPLE.matcher(text);
        while (annotation.find()) {
            if (quotedSource(annotation.group())) {
                continue;
            }
            Matcher quoted = QUOTED.matcher(annotation.group(1));
            while (quoted.find()) {
                String candidate = quoted.group(1);
                // The `note` member is prose, not an identifier; only P-shaped strings are claims.
                if (candidate.startsWith("P-")) {
                    ids.add(candidate);
                }
            }
        }
        return ids;
    }

    /**
     * Whether a match is annotation <em>text</em> inside a Java string rather than a real
     * annotation.
     *
     * <p>Several rules quote the annotation they want you to add into their own violation message -
     * {@code ResilienceRules} tells you to write
     * {@code @ImplementsPrinciple(value = "P-051", note = "...")}. In source that is
     * {@code \"P-051\"}, and an escaped quote is the reliable tell: a real annotation's value can
     * never contain one. Stripping comments alone was not enough to keep these out.
     */
    private static boolean quotedSource(String match) {
        return match.contains("\\\"");
    }

    private static List<String> adrIds(String text) {
        return ADR.matcher(text)
                .results()
                .filter(result -> !quotedSource(result.group()))
                .map(result -> result.group(1))
                .toList();
    }

    /**
     * Drops comments before matching.
     *
     * <p>Otherwise a Javadoc paragraph explaining a rule - this repository has several, including
     * one in {@code RepositoryLayout} that quotes a deliberately malformed {@code @Adr("ADR-0")} -
     * would be read as a real reference and fail the build for documenting the very bug it fixed.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
