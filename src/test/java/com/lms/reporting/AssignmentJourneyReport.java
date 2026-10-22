package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Builds the "ASSIGNMENT JOURNEY" summary block for the Cucumber HTML report - the business-journey
 * counterpart to {@link EnvironmentSetupReport}. It records the verified stages of the assignment
 * lifecycle - admin create/verify/cross-verify, then student retrieve/details/submit/verify - so the
 * report reads as one continuous story: Environment Setup -> Assignment Journey -> ... -> Cleanup,
 * with each stage clearly belonging to the runtime-created course and runtime student.
 *
 * <p>Purely a reporting aid (no API calls, no assertions); each {@link #checkpoint} follows an
 * ALREADY-passed assertion in {@code AssignmentWorkflow}. It accumulates in the per-scenario line
 * buffer (fresh for the journey scenario, distinct from Environment Setup's own scenario) and is
 * attached ONCE via {@link #publish} at the end of the journey stage. Never logs tokens/credentials.
 */
public final class AssignmentJourneyReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";

    private AssignmentJourneyReport() {
    }

    /** Records one completed, already-verified assignment stage (e.g. "Assignment Created"). */
    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    /** Formats and attaches the accumulated ASSIGNMENT JOURNEY summary to the current scenario. */
    public static void publish(ScenarioContext context) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nASSIGNMENT JOURNEY\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" Assignment Journey Phase 2 Completed\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Assignment Journey Summary");
    }
}
