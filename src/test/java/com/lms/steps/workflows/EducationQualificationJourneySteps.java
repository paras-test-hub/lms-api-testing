package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.EducationQualificationWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

public class EducationQualificationJourneySteps {

    private final ScenarioContext context;
    private final EducationQualificationWorkflow educationWorkflow;

    public EducationQualificationJourneySteps(ScenarioContext context) {
        this.context = context;
        this.educationWorkflow = new EducationQualificationWorkflow();
    }

    @Given("the runtime environment is ready for the education qualification journey")
    public void theRuntimeEnvironmentIsReadyForTheEducationQualificationJourney() {
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for education qualifications")
    public void theRuntimeStudentsProfileIsRetrievedForEducationQualifications() {
        educationWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the education qualification baseline is captured")
    public void theEducationQualificationBaselineIsCaptured() {
    }

    @When("a new education qualification is created with minimal fields")
    public void aNewEducationQualificationIsCreatedWithMinimalFields() {
        educationWorkflow.createQualification(context);
    }

    @Then("the created qualification is returned with a uuid")
    public void theCreatedQualificationIsReturnedWithAUuid() {
    }

    @When("the profile is retrieved to verify the qualification creation")
    public void theProfileIsRetrievedToVerifyTheQualificationCreation() {
        educationWorkflow.verifyCreationViaProfileGet(context);
    }

    @Then("the created qualification is persisted in educationData")
    public void theCreatedQualificationIsPersistedInEducationData() {
    }

    @When("the qualification is updated with subjects marks and school details")
    public void theQualificationIsUpdatedWithSubjectsMarksAndSchoolDetails() {
        educationWorkflow.updateQualificationWithDetails(context);
    }

    @Then("the updated qualification retains its uuid with enriched fields")
    public void theUpdatedQualificationRetainsItsUuidWithEnrichedFields() {
    }

    @When("the profile is retrieved to verify the qualification update")
    public void theProfileIsRetrievedToVerifyTheQualificationUpdate() {
        educationWorkflow.verifyUpdatePersisted(context);
    }

    @Then("the updated subjects and marks are persisted in educationData")
    public void theUpdatedSubjectsAndMarksArePersistedInEducationData() {
    }

    @When("the qualification is deleted")
    public void theQualificationIsDeleted() {
        educationWorkflow.deleteQualification(context);
    }

    @Then("the deletion of the qualification is confirmed by the backend")
    public void theDeletionOfTheQualificationIsConfirmedByTheBackend() {
    }

    @When("the profile is retrieved to verify the qualification deletion")
    public void theProfileIsRetrievedToVerifyTheQualificationDeletion() {
        educationWorkflow.verifyDeletionPersisted(context);
    }

    @Then("the deleted qualification no longer appears in educationData")
    public void theDeletedQualificationNoLongerAppearsInEducationData() {
    }

    @Then("the education qualification journey stage is complete")
    public void theEducationQualificationJourneyStageIsComplete() {
        educationWorkflow.completeEducationQualificationJourneyStage(context);
    }
}
