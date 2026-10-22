package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Builds the human-readable "ENVIRONMENT SETUP" summary block shown in the Cucumber HTML
 * report, so a reader immediately sees which academic entities were created and verified
 * before any business workflow began - without needing to know how the hooks or setup code
 * work internally.
 *
 * Purely a reporting aid: it does NOT change execution order, add API calls, or introduce any
 * new verification logic. Every {@link #checkpoint} call is placed immediately after an
 * ALREADY-EXISTING assertion in the workflow code has passed - if that assertion had failed,
 * the scenario would already have failed before this class is ever reached, so a checkpoint
 * line only ever appears for a genuinely verified operation.
 *
 * The full block is attached ONCE, at the end of Environment Setup (right before Business
 * Workflow 1 begins), via {@link #publish}. Cucumber's built-in HTML formatter renders the
 * attachment nested under the step that was executing at the time - i.e. the last Environment
 * Setup step - so it reads as the natural "setup is complete, here is the recap" boundary
 * marker the existing report otherwise lacks.
 */
public final class EnvironmentSetupReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓"; // ✓

    private EnvironmentSetupReport() {
    }

    /**
     * Records one completed, already-verified business action (e.g. "Program Created").
     * {@code details} are optional supporting lines (Name/UUID/etc.) shown indented beneath it.
     */
    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    /**
     * Formats and attaches the full accumulated summary to the Cucumber report. Safe to call
     * even if no Scenario is available (e.g. the workflow classes are exercised outside a
     * Cucumber run) - reporting is always best-effort and never affects the test result.
     */
    public static void publish(ScenarioContext context) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nENVIRONMENT SETUP\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" Environment Ready\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Environment Setup Summary");
    }
}
