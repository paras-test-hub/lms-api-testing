package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.FamilyBackgroundWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Student Profile journey's Family & Background sub-workflow
 * ({@code 10_journeys/StudentProfileFamilyBackground.feature}) - a sibling of
 * {@link PersonalDetailsJourneySteps}, both driving the same runtime student's profile. Thin by
 * design: all logic lives in {@link FamilyBackgroundWorkflow}.
 */
public class FamilyBackgroundJourneySteps {

    private final ScenarioContext context;
    private final FamilyBackgroundWorkflow familyBackgroundWorkflow;

    public FamilyBackgroundJourneySteps(ScenarioContext context) {
        this.context = context;
        this.familyBackgroundWorkflow = new FamilyBackgroundWorkflow();
    }

    @Given("the runtime environment is ready for the family background journey")
    public void theRuntimeEnvironmentIsReadyForTheFamilyBackgroundJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available from Environment Setup").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for family background")
    public void theRuntimeStudentsProfileIsRetrievedForFamilyBackground() {
        familyBackgroundWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the family background baseline is captured")
    public void theFamilyBackgroundBaselineIsCaptured() {
        // retrieveRuntimeProfile() already asserts the profile belongs to the runtime student before
        // this step runs; reaching here IS the confirmation.
    }

    @When("a non-numeric family income is probed against the backend")
    public void aNonNumericFamilyIncomeIsProbedAgainstTheBackend() {
        familyBackgroundWorkflow.probeNonNumericFamilyIncome(context);
    }

    @Then("the backend's numeric-only behaviour is recorded, not assumed")
    public void theBackendsNumericOnlyBehaviourIsRecordedNotAssumed() {
        // probeNonNumericFamilyIncome() already asserted no 5xx before this step runs; reaching here
        // IS the confirmation.
    }

    @When("the admin submits family background with poverty line, disability, and a runtime numeric family income")
    public void theAdminSubmitsFamilyBackgroundWithPovertyLineDisabilityAndARuntimeNumericFamilyIncome() {
        familyBackgroundWorkflow.updateFamilyBackground(context);
    }

    @Then("family background is saved successfully")
    public void familyBackgroundIsSavedSuccessfully() {
        // updateFamilyBackground() already asserts the save returned 2xx before this step runs;
        // reaching here IS the confirmation.
    }

    @When("the profile is retrieved again for family background")
    public void theProfileIsRetrievedAgainForFamilyBackground() {
        familyBackgroundWorkflow.verifyBackendPersistence(context);
    }

    @Then("poverty line, disability, and family income all match the backend")
    public void povertyLineDisabilityAndFamilyIncomeAllMatchTheBackend() {
        // verifyBackendPersistence() already diffed every field before this step runs; reaching here
        // IS the confirmation.
    }

    @Then("the family background journey stage is complete")
    public void theFamilyBackgroundJourneyStageIsComplete() {
        familyBackgroundWorkflow.completeFamilyBackgroundJourneyStage(context);
    }
}
