package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Builds the "SESSION JOURNEY - PHASE 1" summary block for the Cucumber HTML report - the Session
 * counterpart to {@link AssignmentJourneyReport}. It records the verified stages of the offline
 * session lifecycle - runtime course verified, room lookup + test-location selection, session name
 * generation, create, read-back verification, relation-chain cross-verification, context storage,
 * cleanup registration - so the report reads as one continuous story: Environment Setup -> Assignment
 * Journey -> Session Journey -> Cleanup, with every stage traceable to the same runtime course.
 *
 * <p>Purely a reporting aid (no API calls, no assertions); each {@link #checkpoint} follows an
 * ALREADY-passed assertion in {@code SessionWorkflow}, so a checkpoint can never claim success for
 * something that only returned HTTP 2xx. It accumulates in the per-scenario line buffer and is
 * attached ONCE via {@link #publish} at the end of the journey. Never logs tokens/credentials.
 */
public final class SessionJourneyReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";

    private SessionJourneyReport() {
    }

    /** Records one completed, already-verified session stage (e.g. "Offline Session Created"). */
    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    /** Formats and attaches the accumulated SESSION JOURNEY summary to the current scenario. */
    public static void publish(ScenarioContext context) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nSESSION JOURNEY - PHASE 1\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" Session Journey Phase 1 Completed\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Session Journey Summary");
    }
}
