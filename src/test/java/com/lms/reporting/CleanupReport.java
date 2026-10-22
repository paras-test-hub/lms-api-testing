package com.lms.reporting;

import com.lms.cleanup.CleanupSummary;
import com.lms.cleanup.ResourceType;
import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Builds the human-readable "ENVIRONMENT CLEANUP" block shown in the Cucumber HTML report, the
 * teardown counterpart to {@link EnvironmentSetupReport}. It renders the outcome of the single
 * suite-end {@link CleanupSummary} - which registered resources were deleted, which were skipped
 * (no delete handler yet), and any failures - so a reader sees the environment being torn down as
 * the closing bookend of the suite's one continuous story.
 *
 * <p>Purely a reporting aid: it introduces no cleanup logic (that stays centralized in
 * CleanupManager) and never affects a scenario's pass/fail. It is attached ONCE, from
 * {@code 99_cleanup/Cleanup.feature}'s step, using that scenario's handle.
 */
public final class CleanupReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";
    private static final String SKIP = "-";
    private static final String FAIL = "✗";

    private CleanupReport() {
    }

    /**
     * Formats and attaches the cleanup summary to the current (Cleanup.feature) scenario. Safe to
     * call with no active Scenario (best-effort reporting), and safe when nothing was cleaned.
     */
    public static void publish(ScenarioContext context, CleanupSummary summary) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nENVIRONMENT CLEANUP (reverse creation order)\n\n").append(BANNER);

        for (ResourceType type : summary.getDeleted()) {
            report.append("\n").append(CHECK).append(" ").append(label(type)).append(" Deleted");
        }
        for (ResourceType type : summary.getSkipped()) {
            report.append("\n").append(SKIP).append(" ").append(label(type))
                    .append(" Skipped (no delete handler yet / already removed)");
        }
        for (CleanupSummary.Failure failure : summary.getFailures()) {
            report.append("\n").append(FAIL).append(" ").append(label(failure.getType()))
                    .append(" FAILED: ").append(failure.getReason());
        }

        if (summary.getDeleted().isEmpty() && summary.getSkipped().isEmpty() && summary.getFailures().isEmpty()) {
            report.append("\nNothing to clean up (no runtime resources were registered this suite).");
        }

        String closing = summary.hasFailures() ? "Cleanup Completed WITH FAILURES" : "Cleanup Complete";
        report.append("\n\n").append(summary.hasFailures() ? FAIL : CHECK).append(" ").append(closing)
                .append("\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Environment Cleanup Summary");
    }

    /** Title-cases a ResourceType enum name (PROGRAM_TERM -> "Program Term") for readable report lines. */
    private static String label(ResourceType type) {
        String[] words = type.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
