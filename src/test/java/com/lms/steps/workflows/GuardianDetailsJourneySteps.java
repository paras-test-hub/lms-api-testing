package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.GuardianDetailsWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Student Profile journey's Guardian Details sub-workflow
 * ({@code 10_journeys/StudentProfileGuardianDetails.feature}) - a sibling of
 * {@link PersonalDetailsJourneySteps} / {@link FamilyBackgroundJourneySteps}, all driving the same
 * runtime student's profile. Thin by design: all logic lives in {@link GuardianDetailsWorkflow}.
 */
public class GuardianDetailsJourneySteps {

    private final ScenarioContext context;
    private final GuardianDetailsWorkflow guardianDetailsWorkflow;

    public GuardianDetailsJourneySteps(ScenarioContext context) {
        this.context = context;
        this.guardianDetailsWorkflow = new GuardianDetailsWorkflow();
    }

    @Given("the runtime environment is ready for the guardian details journey")
    public void theRuntimeEnvironmentIsReadyForTheGuardianDetailsJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available from Environment Setup").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for guardian details")
    public void theRuntimeStudentsProfileIsRetrievedForGuardianDetails() {
        guardianDetailsWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the guardian details baseline is captured")
    public void theGuardianDetailsBaselineIsCaptured() {
        // retrieveRuntimeProfile() already asserts the profile belongs to the runtime student before
        // this step runs; reaching here IS the confirmation.
    }

    @When("the first guardian, the father, is added")
    public void theFirstGuardianTheFatherIsAdded() {
        guardianDetailsWorkflow.addFirstGuardianFather(context);
    }

    @Then("the father's details are saved and persisted")
    public void theFathersDetailsAreSavedAndPersisted() {
        // addFirstGuardianFather() already asserts the save succeeded and re-GET matches before this
        // step runs; reaching here IS the confirmation.
    }

    @When("the second guardian, the mother, is added with a malformed mobile number")
    public void theSecondGuardianTheMotherIsAddedWithAMalformedMobileNumber() {
        guardianDetailsWorkflow.addSecondGuardianMother(context);
    }

    @Then("the mother's details are saved, the father is unaffected, and the mobile format behaviour is recorded")
    public void theMothersDetailsAreSavedTheFatherIsUnaffectedAndTheMobileFormatBehaviourIsRecorded() {
        // addSecondGuardianMother() already asserts the save didn't 5xx, re-GET matches, and the
        // father is unchanged before this step runs; reaching here IS the confirmation.
    }

    @When("the mother's mobile number is updated to a valid runtime number")
    public void theMothersMobileNumberIsUpdatedToAValidRuntimeNumber() {
        guardianDetailsWorkflow.updateGuardianMobileNumber(context);
    }

    @Then("the guardian info update is saved successfully")
    public void theGuardianInfoUpdateIsSavedSuccessfully() {
        // updateGuardianMobileNumber() already asserts the save returned 2xx before this step runs;
        // reaching here IS the confirmation.
    }

    @When("the profile is retrieved again for guardian details")
    public void theProfileIsRetrievedAgainForGuardianDetails() {
        guardianDetailsWorkflow.verifyBackendPersistence(context);
    }

    @Then("both guardians match the backend and the update did not corrupt either")
    public void bothGuardiansMatchTheBackendAndTheUpdateDidNotCorruptEither() {
        // verifyBackendPersistence() already diffed every field for both guardians before this step
        // runs; reaching here IS the confirmation.
    }

    @Then("the guardian details journey stage is complete")
    public void theGuardianDetailsJourneyStageIsComplete() {
        guardianDetailsWorkflow.completeGuardianDetailsJourneyStage(context);
    }
}
