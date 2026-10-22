package com.lms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architectural regression guard (executable, not just documented): the framework has exactly
 * ONE concept of "a valid student" - the one Environment Setup creates and authenticates at
 * runtime (BootstrapWorkflow#createAndVerifyStudent + StudentLoginWorkflow), with its session
 * stored in ScenarioContext (studentToken / studentRefreshToken / studentUserId). A second,
 * static/.env-sourced "valid student" concept (STUDENT_TEST_EMAIL / STUDENT_TEST_PASSWORD /
 * TestConfig#getStudentTestEmail / TestConfig#getStudentTestPassword) used to exist for
 * Authentication.feature's own login-endpoint scenario and has been fully retired - there is no
 * remaining legitimate caller anywhere in the codebase.
 *
 * This test walks the entire test-source tree and fails if ANY file (including this rule's
 * former allowlist - TestConfig.java, AuthenticationSteps.java) reintroduces those env keys or
 * accessors in CODE. If this test fails, someone has resurrected the retired static-student
 * concept: student credentials must always come from ScenarioContext (populated once, by
 * Environment Setup), never from a static .env account.
 *
 * Comments are stripped before scanning, so documentation may freely mention the rule (and the
 * token names) without tripping it - only real code references are counted.
 */
class StudentCredentialIsolationTest {

    /** Any occurrence of these strings signals a reintroduced static debug-only student credential. */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "STUDENT_TEST_EMAIL",
            "STUDENT_TEST_PASSWORD",
            "getStudentTestEmail",
            "getStudentTestPassword");

    /** Only this test itself may name the forbidden tokens (to scan for them). No other file may. */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "StudentCredentialIsolationTest.java");

    @Test
    void staticStudentCredentialConceptMustNeverBeReintroduced() throws IOException {
        Path testSourceRoot = locateTestSourceRoot();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(testSourceRoot)) {
            List<Path> javaFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            for (Path file : javaFiles) {
                String code = stripComments(Files.readString(file));
                boolean referencesStaticCreds = FORBIDDEN_TOKENS.stream().anyMatch(code::contains);
                if (referencesStaticCreds && !ALLOWED_FILES.contains(file.getFileName().toString())) {
                    offenders.add(testSourceRoot.relativize(file).toString());
                }
            }
        }

        assertThat(offenders)
                .as("STUDENT_TEST_EMAIL/STUDENT_TEST_PASSWORD is a retired concept - the framework has "
                        + "exactly one 'valid student', the one Environment Setup creates and authenticates "
                        + "at runtime. Business workflows must consume the Environment-Setup-authenticated "
                        + "session from ScenarioContext (context.getStudentToken(), etc.), never a static "
                        + ".env account. Offending file(s) that reference the retired static credentials: %s",
                        offenders)
                .isEmpty();
    }

    /**
     * Removes block comments (incl. Javadoc) and line comments so the token scan sees only code.
     * A test-only heuristic: it does not account for the tokens appearing inside a string literal
     * that also contains comment markers, which does not occur in this codebase.
     */
    private static String stripComments(String source) {
        String withoutBlock = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("//[^\\n]*", " ");
    }

    /**
     * Resolves the test-source root regardless of whether the working directory is the module
     * root (the {@code mvn test} default) or the repository root (some IDE run configs).
     */
    private static Path locateTestSourceRoot() {
        Path moduleRelative = Path.of("src", "test", "java");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path repoRelative = Path.of("lms-api-testing-restassured-cucumber", "src", "test", "java");
        if (Files.isDirectory(repoRelative)) {
            return repoRelative;
        }
        throw new IllegalStateException(
                "Could not locate the test-source root (tried " + moduleRelative.toAbsolutePath()
                        + " and " + repoRelative.toAbsolutePath() + "). Run this from the module or repo root.");
    }
}
