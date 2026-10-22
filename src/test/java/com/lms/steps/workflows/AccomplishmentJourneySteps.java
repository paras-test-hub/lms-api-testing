package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.AccomplishmentWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

public class AccomplishmentJourneySteps {

    private final ScenarioContext context;
    private final AccomplishmentWorkflow accomplishmentWorkflow;

    public AccomplishmentJourneySteps(ScenarioContext context) {
        this.context = context;
        this.accomplishmentWorkflow = new AccomplishmentWorkflow();
    }

    @Given("the runtime environment is ready for the accomplishments journey")
    public void theRuntimeEnvironmentIsReadyForTheAccomplishmentsJourney() {
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for accomplishments")
    public void theRuntimeStudentsProfileIsRetrievedForAccomplishments() {
        accomplishmentWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the accomplishments baseline is captured")
    public void theAccomplishmentsBaselineIsCaptured() {
    }

    @When("the first accomplishment is created")
    public void theFirstAccomplishmentIsCreated() {
        accomplishmentWorkflow.createFirstAccomplishment(context);
    }

    @Then("the first accomplishment is returned with a uuid")
    public void theFirstAccomplishmentIsReturnedWithAUuid() {
    }

    @When("the second accomplishment is created")
    public void theSecondAccomplishmentIsCreated() {
        accomplishmentWorkflow.createSecondAccomplishment(context);
    }

    @Then("both accomplishments exist in the response")
    public void bothAccomplishmentsExistInTheResponse() {
    }

    @When("the profile is retrieved to verify both accomplishments")
    public void theProfileIsRetrievedToVerifyBothAccomplishments() {
        accomplishmentWorkflow.verifyBothInProfileGet(context);
    }

    @Then("both accomplishments are persisted in the profile")
    public void bothAccomplishmentsArePersistedInTheProfile() {
    }

    @When("the first accomplishment is updated with new title and description")
    public void theFirstAccomplishmentIsUpdatedWithNewTitleAndDescription() {
        accomplishmentWorkflow.updateFirstAccomplishment(context);
    }

    @Then("the first accomplishment retains its uuid with modified fields and the second is unaffected")
    public void theFirstAccomplishmentRetainsItsUuidWithModifiedFieldsAndTheSecondIsUnaffected() {
    }

    @When("the profile is retrieved to verify the accomplishment update")
    public void theProfileIsRetrievedToVerifyTheAccomplishmentUpdate() {
        accomplishmentWorkflow.verifyUpdatePersisted(context);
    }

    @Then("the updated accomplishment fields are persisted")
    public void theUpdatedAccomplishmentFieldsArePersisted() {
    }

    @When("the second accomplishment is deleted")
    public void theSecondAccomplishmentIsDeleted() {
        accomplishmentWorkflow.deleteSecondAccomplishment(context);
    }

    @Then("the second accomplishment is removed and the first survives")
    public void theSecondAccomplishmentIsRemovedAndTheFirstSurvives() {
    }

    @When("the profile is retrieved to verify the accomplishment deletion")
    public void theProfileIsRetrievedToVerifyTheAccomplishmentDeletion() {
        accomplishmentWorkflow.verifyDeletionPersisted(context);
    }

    @Then("only the first accomplishment remains in the profile")
    public void onlyTheFirstAccomplishmentRemainsInTheProfile() {
    }

    @Then("the accomplishments journey stage is complete")
    public void theAccomplishmentsJourneyStageIsComplete() {
        accomplishmentWorkflow.completeAccomplishmentJourneyStage(context);
    }
}
