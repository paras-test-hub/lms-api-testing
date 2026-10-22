package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.CapstoneWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * Steps for the Capstone journey ({@code 10_journeys/Capstone.feature}), which now covers BOTH capstone
 * stages:
 * <ul>
 *   <li><b>S1 - Capstone Mini-Setup</b>: reuses the suite-scoped runtime environment and adds a Capstone
 *       Outclass Section beside the runtime section, inside the SAME Program Term, attaches the existing
 *       runtime course to it and gamifies the resulting capstone TermCourse;</li>
 *   <li><b>S2 - Capstone Configuration</b>: configures that gamified capstone course - metrics, the
 *       metrics-builder grid, the pinned dashboard metrics, the analytics display, the leaderboard
 *       formula, the resources and the rewards - and verifies the whole configuration was persisted.</li>
 * </ul>
 * S2 lives in its own scenario and CONSUMES the suite-scoped runtime state S1 stored (capstone section,
 * capstone TermCourse); it recreates nothing.
 *
 * <p>Thin by design: every assertion, request and report checkpoint lives in {@link CapstoneWorkflow},
 * so each method body here is a single delegation.
 *
 * <p>Unlike {@code AttendanceJourneySteps}, even the readiness guards are delegated
 * ({@code requireRuntimeEnvironmentReady} for S1, {@code validateCapstoneReadyForConfiguration} for S2) -
 * the workflow is the component that owns readiness assertions for these stages, which keeps this class
 * free of any assertion at all.
 *
 * <p>{@link ScenarioContext} is constructor-injected by PicoContainer, matching every other steps
 * class in the framework.
 *
 * <p>Both scenarios were built one verified business step at a time and are now complete: every step of
 * the capstone journey - from section creation through gamification confirmed by two independent reads,
 * on to the full configuration and its read-back - is bound here.
 */
public class CapstoneSteps {

    private final ScenarioContext context;
    private final CapstoneWorkflow capstoneWorkflow;

    public CapstoneSteps(ScenarioContext context) {
        this.context = context;
        this.capstoneWorkflow = new CapstoneWorkflow();
    }

    @Given("the runtime environment is ready for the capstone journey")
    public void theRuntimeEnvironmentIsReadyForTheCapstoneJourney() {
        capstoneWorkflow.requireRuntimeEnvironmentReady(context);
    }

    @When("the admin creates a Capstone Outclass section")
    public void theAdminCreatesACapstoneOutclassSection() {
        capstoneWorkflow.createCapstoneOutclassSection(context);
    }

    @When("the runtime student is assigned to the Capstone section")
    public void theRuntimeStudentIsAssignedToTheCapstoneSection() {
        capstoneWorkflow.assignRuntimeStudentToCapstoneSection(context);
    }

    @Then("the Capstone section contains the runtime student")
    public void theCapstoneSectionContainsTheRuntimeStudent() {
        capstoneWorkflow.verifyRuntimeStudentInCapstoneSection(context);
    }

    @When("the existing runtime course is added to the Capstone section")
    public void theExistingRuntimeCourseIsAddedToTheCapstoneSection() {
        capstoneWorkflow.addRuntimeCourseToCapstoneSection(context);
    }

    @Then("the Capstone section contains the runtime course")
    public void theCapstoneSectionContainsTheRuntimeCourse() {
        capstoneWorkflow.verifyRuntimeCourseInCapstoneSection(context);
    }

    @When("the admin enables gamification for the Capstone course")
    public void theAdminEnablesGamificationForTheCapstoneCourse() {
        capstoneWorkflow.enableCapstoneCourseGamification(context);
    }

    @Then("the Capstone course is verified as gamified")
    public void theCapstoneCourseIsVerifiedAsGamified() {
        capstoneWorkflow.verifyCapstoneCourseGamified(context);
    }

    // ================= S2: configuring the gamified capstone course =================

    @Given("the Capstone course is ready for configuration")
    public void theCapstoneCourseIsReadyForConfiguration() {
        capstoneWorkflow.validateCapstoneReadyForConfiguration(context);
    }

    @When("the admin configures the Capstone metrics")
    public void theAdminConfiguresTheCapstoneMetrics() {
        capstoneWorkflow.configureCapstoneMetrics(context);
    }

    @When("the admin configures the Capstone metrics builder")
    public void theAdminConfiguresTheCapstoneMetricsBuilder() {
        capstoneWorkflow.configureCapstoneMetricsBuilder(context);
    }

    @When("the admin configures the Capstone pinned metrics")
    public void theAdminConfiguresTheCapstonePinnedMetrics() {
        capstoneWorkflow.configureCapstoneDashboard(context);
    }

    @When("the admin configures the Capstone analytics display")
    public void theAdminConfiguresTheCapstoneAnalyticsDisplay() {
        capstoneWorkflow.configureCapstoneAnalytics(context);
    }

    @When("the admin configures the Capstone leaderboard")
    public void theAdminConfiguresTheCapstoneLeaderboard() {
        capstoneWorkflow.configureCapstoneLeaderboard(context);
    }

    @When("the admin configures Capstone resources")
    public void theAdminConfiguresCapstoneResources() {
        capstoneWorkflow.configureCapstoneResources(context);
    }

    @When("the admin configures Capstone rewards")
    public void theAdminConfiguresCapstoneRewards() {
        capstoneWorkflow.configureCapstoneRewards(context);
    }

    @Then("the Capstone configuration should be persisted correctly")
    public void theCapstoneConfigurationShouldBePersistedCorrectly() {
        capstoneWorkflow.verifyCapstoneConfiguration(context);
    }

    // ================= S3: creating the Capstone team =================

    @Given("the Capstone course is configured and ready for team formation")
    public void theCapstoneCourseIsConfiguredAndReadyForTeamFormation() {
        capstoneWorkflow.validateCapstoneReadyForTeamFormation(context);
    }

    @When("the admin creates a Capstone team with the runtime student")
    public void theAdminCreatesACapstoneTeamWithTheRuntimeStudent() {
        capstoneWorkflow.createCapstoneTeamWithRuntimeStudent(context);
    }

    @Then("the Capstone team should be created successfully")
    public void theCapstoneTeamShouldBeCreatedSuccessfully() {
        capstoneWorkflow.verifyCapstoneTeamCreated(context);
    }

    @Then("the admin should see the runtime student as a member of the Capstone team")
    public void theAdminShouldSeeTheRuntimeStudentAsAMemberOfTheCapstoneTeam() {
        capstoneWorkflow.verifyAdminSeesRuntimeStudentInTeam(context);
    }

    @Then("the student should see the Capstone team with the runtime student as a member")
    public void theStudentShouldSeeTheCapstoneTeamWithTheRuntimeStudentAsAMember() {
        capstoneWorkflow.verifyStudentSeesCapstoneTeamMembership(context);
    }

    @Then("the Capstone team should inherit the configured Capstone metrics")
    public void theCapstoneTeamShouldInheritTheConfiguredCapstoneMetrics() {
        capstoneWorkflow.verifyCapstoneTeamInheritsMetrics(context);
    }

    @Then("the Capstone team should retain the configured Capstone configuration")
    public void theCapstoneTeamShouldRetainTheConfiguredCapstoneConfiguration() {
        capstoneWorkflow.verifyCapstoneTeamRetainsConfiguration(context);
    }
}
