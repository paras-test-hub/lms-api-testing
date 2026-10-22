package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.reporting.ConcernJourneyReport;
import com.lms.workflows.ConcernConfigurationWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thin delegates for the Concern journey. All endpoint knowledge and assertions live in the
 * workflow classes; these steps only wire Gherkin to workflow methods.
 */
public class ConcernJourneySteps {

    private final ScenarioContext context;
    private final ConcernConfigurationWorkflow configurationWorkflow;

    public ConcernJourneySteps(ScenarioContext context) {
        this.context = context;
        this.configurationWorkflow = new ConcernConfigurationWorkflow();
    }

    // ================= Configuration =================

    @Given("the runtime environment is ready for the concern journey")
    public void theRuntimeEnvironmentIsReadyForTheConcernJourney() {
        context.execution().requireEnvironmentReady();
        assertThat(context.getAdminToken()).as("runtime admin session must be available").isNotBlank();
        assertThat(context.getAdminUserId()).as("runtime admin uuid must be available").isNotBlank();
        assertThat(context.getAdminOrgId()).as("runtime organization must be available").isNotBlank();
        assertThat(context.getCohortId()).as("runtime cohort must be available").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available").isNotBlank();
        assertThat(context.getStudentUuid()).as("runtime student uuid must be available").isNotBlank();
    }

    @When("the concern taxonomy is resolved for technical support")
    public void theConcernTaxonomyIsResolvedForTechnicalSupport() {
        configurationWorkflow.resolveTargetSubCategory(context);
    }

    @Then("the target concern sub-category is identified")
    public void theTargetConcernSubCategoryIsIdentified() {
        assertThat(context.getConcernSubCategoryUuid())
                .as("the resolved sub-category uuid must be stored on the runtime context").isNotBlank();
    }

    @When("the runtime admin is confirmed as an assignable concern handler")
    public void theRuntimeAdminIsConfirmedAsAnAssignableConcernHandler() {
        configurationWorkflow.verifyRuntimeAdminIsAssignable(context);
    }

    @When("the escalation flow is configured to the runtime admin for every level")
    public void theEscalationFlowIsConfiguredToTheRuntimeAdminForEveryLevel() {
        configurationWorkflow.configureEscalationToRuntimeAdmin(context);
    }

    @Then("every escalation level routes back to the runtime admin")
    public void everyEscalationLevelRoutesBackToTheRuntimeAdmin() {
        configurationWorkflow.verifyEscalationConfiguration(context);
        assertThat(context.isConcernEscalationConfigured())
                .as("escalation configuration must be marked complete on the runtime context").isTrue();
    }

    @Then("the concern configuration stage is complete")
    public void theConcernConfigurationStageIsComplete() {
        configurationWorkflow.completeConfigurationStage(context);
    }

    @Then("the concern configuration report is published")
    public void theConcernConfigurationReportIsPublished() {
        ConcernJourneyReport.publish(context, "ESCALATION CONFIGURATION");
    }
}
