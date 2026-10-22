package com.lms.reporting;

import com.lms.context.ScenarioContext;
import io.cucumber.java.Scenario;

/**
 * Consolidated report for the entire Student Profile business journey. Each profile section
 * (Personal Details, Family & Background, Guardian Details, Address Details, Work Experience,
 * Education Qualifications, Accomplishments, Career Preferences) is a separate Scenario within
 * the single StudentProfile.feature — each Scenario gets its own ScenarioContext and therefore
 * its own report line accumulator.
 *
 * <p>The {@link #publish(ScenarioContext, String)} method takes the section name so each
 * Scenario's report is clearly labeled while sharing the same report format.
 *
 * <p>Purely a reporting aid (no API calls, no assertions); each {@link #checkpoint} follows an
 * ALREADY-passed assertion in the corresponding workflow, so a checkpoint can never claim success
 * for something that merely returned HTTP 2xx. Never logs tokens/credentials.
 */
public final class StudentProfileJourneyReport {

    private static final String BANNER = "=====================================================";
    private static final String CHECK = "✓";

    private StudentProfileJourneyReport() {
    }

    public static void checkpoint(ScenarioContext context, String label, String... details) {
        StringBuilder line = new StringBuilder("\n").append(CHECK).append(" ").append(label);
        for (String detail : details) {
            line.append("\n    ").append(detail);
        }
        context.appendEnvironmentSetupReportLine(line.toString());
    }

    public static void publish(ScenarioContext context, String sectionName) {
        Scenario scenario = context.getCucumberScenario();
        if (scenario == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(BANNER).append("\n\nSTUDENT PROFILE JOURNEY - ").append(sectionName).append("\n\n").append(BANNER);
        for (String line : context.getEnvironmentSetupReportLines()) {
            report.append(line);
        }
        report.append("\n\n").append(CHECK).append(" ").append(sectionName).append(" Completed\n\n").append(BANNER);

        scenario.attach(report.toString(), "text/plain", "Student Profile Journey Summary");
    }
}
