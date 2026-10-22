package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Builds the "CAPSTONE MINI-SETUP" summary block for the Cucumber HTML report - the Capstone
 * counterpart to {@link AttendanceJourneyReport}. It records the verified stages of the capstone
 * mini-setup - the Capstone Outclass Section created inside the runtime Program Term, the runtime
 * student enrolled and read back from the section roster, the runtime CourseMaster attached and its
 * capstone TermCourse resolved, then gamification enabled and confirmed by two independent reads - so
 * the report reads as one continuous story: Environment Setup -> ... -> Capstone Mini-Setup -> Cleanup.
 *
 * <p>The stage's whole point is that it reuses the runtime environment WITHOUT disturbing it, so the
 * checkpoints deliberately name the capstone entities as distinct from their Environment Setup
 * counterparts (capstone Section vs runtime Section, capstone TermCourse vs runtime TermCourse) rather
 * than reporting a single "capstone ok" line.
 *
 * <p>Purely a reporting aid (no API calls, no assertions); each {@link #checkpoint} follows an
 * ALREADY-passed assertion in {@code CapstoneWorkflow}, so a checkpoint can never claim success for
 * something that merely returned HTTP 2xx. Never logs tokens/credentials.
 */
public final class CapstoneJourneyReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";

    private CapstoneJourneyReport() {
    }

    /** Records one completed, already-verified capstone stage (e.g. "Capstone Outclass Section Created"). */
    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    /** Formats and attaches the accumulated CAPSTONE MINI-SETUP summary to the current scenario. */
    public static void publish(ScenarioContext context) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nCAPSTONE MINI-SETUP\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" Capstone Mini-Setup Completed\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Capstone Mini-Setup Summary");
    }
}
