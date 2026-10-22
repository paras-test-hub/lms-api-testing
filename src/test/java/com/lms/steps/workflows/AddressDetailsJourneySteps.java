package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.AddressDetailsWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Student Profile journey's Address Details sub-workflow
 * ({@code 10_journeys/StudentProfileAddressDetails.feature}) - a sibling of
 * {@link PersonalDetailsJourneySteps} / {@link FamilyBackgroundJourneySteps} /
 * {@link GuardianDetailsJourneySteps}. Thin by design: all logic lives in
 * {@link AddressDetailsWorkflow}.
 */
public class AddressDetailsJourneySteps {

    private final ScenarioContext context;
    private final AddressDetailsWorkflow addressDetailsWorkflow;

    public AddressDetailsJourneySteps(ScenarioContext context) {
        this.context = context;
        this.addressDetailsWorkflow = new AddressDetailsWorkflow();
    }

    @Given("the runtime environment is ready for the address details journey")
    public void theRuntimeEnvironmentIsReadyForTheAddressDetailsJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available from Environment Setup").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for address details")
    public void theRuntimeStudentsProfileIsRetrievedForAddressDetails() {
        addressDetailsWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the address details baseline is captured")
    public void theAddressDetailsBaselineIsCaptured() {
        // retrieveRuntimeProfile() already asserts the profile belongs to the runtime student before
        // this step runs; reaching here IS the confirmation.
    }

    @When("the first address is added")
    public void theFirstAddressIsAdded() {
        addressDetailsWorkflow.addFirstAddress(context);
    }

    @Then("the first address is saved and persisted")
    public void theFirstAddressIsSavedAndPersisted() {
        // addFirstAddress() already asserts the save succeeded and re-GET matches before this step
        // runs; reaching here IS the confirmation.
    }

    @When("the first address is marked as permanent")
    public void theFirstAddressIsMarkedAsPermanent() {
        addressDetailsWorkflow.markFirstAddressPermanent(context);
    }

    @Then("the first address's permanent flag is persisted as true")
    public void theFirstAddressesPermanentFlagIsPersistedAsTrue() {
        // markFirstAddressPermanent() already asserts the save succeeded and re-GET matches before
        // this step runs; reaching here IS the confirmation.
    }

    @When("the first address is unmarked as permanent")
    public void theFirstAddressIsUnmarkedAsPermanent() {
        addressDetailsWorkflow.unmarkFirstAddressPermanent(context);
    }

    @Then("the first address's permanent flag is persisted as false")
    public void theFirstAddressesPermanentFlagIsPersistedAsFalse() {
        // unmarkFirstAddressPermanent() already asserts the save succeeded and re-GET matches before
        // this step runs; reaching here IS the confirmation.
    }

    @When("a second address is added")
    public void aSecondAddressIsAdded() {
        addressDetailsWorkflow.addSecondAddress(context);
    }

    @Then("both addresses are present and the first is unaffected")
    public void bothAddressesArePresentAndTheFirstIsUnaffected() {
        // addSecondAddress() already asserts both addresses match via re-GET before this step runs;
        // reaching here IS the confirmation.
    }

    @When("the second address is marked as permanent")
    public void theSecondAddressIsMarkedAsPermanent() {
        addressDetailsWorkflow.markSecondAddressPermanent(context);
    }

    @Then("the second address's permanent flag is persisted as true and the first is unaffected")
    public void theSecondAddressesPermanentFlagIsPersistedAsTrueAndTheFirstIsUnaffected() {
        // markSecondAddressPermanent() already asserts both addresses match via re-GET before this
        // step runs; reaching here IS the confirmation.
    }

    @When("each mandatory address field is probed blank against the backend")
    public void eachMandatoryAddressFieldIsProbedBlankAgainstTheBackend() {
        addressDetailsWorkflow.probeMandatoryFieldEnforcement(context);
    }

    @Then("the backend's address field validation behaviour is recorded, not assumed, and the real state is restored")
    public void theBackendsAddressFieldValidationBehaviourIsRecordedNotAssumedAndTheRealStateIsRestored() {
        // probeMandatoryFieldEnforcement() already asserted no 5xx, restored the real state, and
        // re-verified it before this step runs; reaching here IS the confirmation.
    }

    @When("deleting the first address is attempted while a second address exists")
    public void deletingTheFirstAddressIsAttemptedWhileASecondAddressExists() {
        addressDetailsWorkflow.probeFirstAddressDeletionProtection(context);
    }

    @Then("the backend's first-address deletion protection is recorded, not assumed, and the real state is restored")
    public void theBackendsFirstAddressDeletionProtectionIsRecordedNotAssumedAndTheRealStateIsRestored() {
        // probeFirstAddressDeletionProtection() already asserted no 5xx, restored the real state, and
        // re-verified it before this step runs; reaching here IS the confirmation.
    }

    @When("the second address is deleted")
    public void theSecondAddressIsDeleted() {
        addressDetailsWorkflow.deleteSecondAddress(context);
    }

    @Then("only the first address remains")
    public void onlyTheFirstAddressRemains() {
        // deleteSecondAddress() already asserts the save returned 2xx before this step runs; reaching
        // here IS the confirmation - the final persistence step diffs the remaining state.
    }

    @When("the profile is retrieved again for address details")
    public void theProfileIsRetrievedAgainForAddressDetails() {
        addressDetailsWorkflow.verifyBackendPersistence(context);
    }

    @Then("the remaining address matches the backend exactly")
    public void theRemainingAddressMatchesTheBackendExactly() {
        // verifyBackendPersistence() already diffed the remaining address before this step runs;
        // reaching here IS the confirmation.
    }

    @Then("the address details journey stage is complete")
    public void theAddressDetailsJourneyStageIsComplete() {
        addressDetailsWorkflow.completeAddressDetailsJourneyStage(context);
    }
}
