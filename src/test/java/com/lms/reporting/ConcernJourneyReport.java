package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Consolidated report for the Concern business journey. Purely a reporting aid (no API calls, no
 * assertions); each {@link #checkpoint} follows an ALREADY-passed assertion in the corresponding
 * workflow, so a checkpoint can never claim success for something that merely returned HTTP 2xx.
 * Never logs tokens or credentials.
 */
public final class ConcernJourneyReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";

    private ConcernJourneyReport() {
    }

    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    public static void publish(ScenarioContext context, String scenarioName) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nCONCERN JOURNEY - ").append(scenarioName).append("\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" ").append(scenarioName).append(" Completed\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Concern Journey Summary");
    }
}
