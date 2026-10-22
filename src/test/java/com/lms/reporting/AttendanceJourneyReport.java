package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Builds the "ATTENDANCE JOURNEY - PHASE 1" summary block for the Cucumber HTML report - the
 * Attendance counterpart to {@link SessionJourneyReport}. It records the verified stages of the
 * attendance lifecycle - session created and seen by BOTH actors, attendance marked Present and
 * confirmed from the admin AND the student side, then flipped to Absent and re-confirmed from both -
 * so the report reads as one continuous story: Environment Setup -> Session Journey -> Attendance
 * Journey -> Cleanup.
 *
 * <p>The journey's whole point is Admin/Student SYNCHRONIZATION, so the checkpoints deliberately
 * pair each admin verification with the matching student verification rather than reporting a single
 * "attendance ok" line.
 *
 * <p>Purely a reporting aid (no API calls, no assertions); each {@link #checkpoint} follows an
 * ALREADY-passed assertion in {@code AttendanceWorkflow}, so a checkpoint can never claim success for
 * something that merely returned HTTP 2xx. Never logs tokens/credentials.
 */
public final class AttendanceJourneyReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";

    private AttendanceJourneyReport() {
    }

    /** Records one completed, already-verified attendance stage (e.g. "Attendance Marked Present"). */
    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    /** Formats and attaches the accumulated ATTENDANCE JOURNEY summary to the current scenario. */
    public static void publish(ScenarioContext context) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nATTENDANCE JOURNEY - PHASE 1\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" Attendance Journey Phase 1 Completed\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Attendance Journey Summary");
    }
}
