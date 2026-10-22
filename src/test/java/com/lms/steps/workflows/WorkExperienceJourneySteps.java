package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.WorkExperienceWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

public class WorkExperienceJourneySteps {

    private final ScenarioContext context;
    private final WorkExperienceWorkflow workExperienceWorkflow;

    public WorkExperienceJourneySteps(ScenarioContext context) {
        this.context = context;
        this.workExperienceWorkflow = new WorkExperienceWorkflow();
    }

    @Given("the runtime environment is ready for the work experience journey")
    public void theRuntimeEnvironmentIsReadyForTheWorkExperienceJourney() {
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for work experience")
    public void theRuntimeStudentsProfileIsRetrievedForWorkExperience() {
        workExperienceWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the work experience baseline is captured")
    public void theWorkExperienceBaselineIsCaptured() {
    }

    @When("a new work experience entry is created")
    public void aNewWorkExperienceEntryIsCreated() {
        workExperienceWorkflow.createExperience(context);
    }

    @Then("the created experience is returned with a uuid")
    public void theCreatedExperienceIsReturnedWithAUuid() {
    }

    @When("the profile is retrieved to verify the experience creation")
    public void theProfileIsRetrievedToVerifyTheExperienceCreation() {
        workExperienceWorkflow.verifyCreationViProfileGet(context);
    }

    @Then("the created experience is persisted in experienceData")
    public void theCreatedExperienceIsPersistedInExperienceData() {
    }

    @When("the work experience entry is updated with new company and job profile")
    public void theWorkExperienceEntryIsUpdatedWithNewCompanyAndJobProfile() {
        workExperienceWorkflow.updateExperience(context);
    }

    @Then("the updated experience retains its uuid with modified fields")
    public void theUpdatedExperienceRetainsItsUuidWithModifiedFields() {
    }

    @When("the profile is retrieved to verify the experience update")
    public void theProfileIsRetrievedToVerifyTheExperienceUpdate() {
        workExperienceWorkflow.verifyUpdatePersisted(context);
    }

    @Then("the updated fields are persisted in experienceData")
    public void theUpdatedFieldsArePersistedInExperienceData() {
    }

    @When("the work experience entry is deleted")
    public void theWorkExperienceEntryIsDeleted() {
        workExperienceWorkflow.deleteExperience(context);
    }

    @Then("the deletion is confirmed by the backend")
    public void theDeletionIsConfirmedByTheBackend() {
    }

    @When("the profile is retrieved to verify the experience deletion")
    public void theProfileIsRetrievedToVerifyTheExperienceDeletion() {
        workExperienceWorkflow.verifyDeletionPersisted(context);
    }

    @Then("the deleted experience no longer appears in experienceData")
    public void theDeletedExperienceNoLongerAppearsInExperienceData() {
    }

    @Then("the work experience journey stage is complete")
    public void theWorkExperienceJourneyStageIsComplete() {
        workExperienceWorkflow.completeWorkExperienceJourneyStage(context);
    }
}
