package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.PersonalDetailsWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Student Profile journey's Personal Details sub-workflow
 * ({@code 10_journeys/StudentProfilePersonalDetails.feature}) - the fourth business journey,
 * consuming the runtime student created during Environment Setup. Thin by design: all logic lives in
 * {@link PersonalDetailsWorkflow}. Future profile sections (Guardian, Address, Work Experience,
 * Education, Documents) get their own workflow + steps class the same way, all reading/writing the
 * same {@code /users/{studentUuid}/userProfile} resource.
 */
public class PersonalDetailsJourneySteps {

    private final ScenarioContext context;
    private final PersonalDetailsWorkflow personalDetailsWorkflow;

    public PersonalDetailsJourneySteps(ScenarioContext context) {
        this.context = context;
        this.personalDetailsWorkflow = new PersonalDetailsWorkflow();
    }

    @Given("the runtime environment is ready for the student profile journey")
    public void theRuntimeEnvironmentIsReadyForTheStudentProfileJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available from Environment Setup").isNotBlank();
    }

    @When("the runtime student's profile is retrieved")
    public void theRuntimeStudentsProfileIsRetrieved() {
        personalDetailsWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the profile belongs to the runtime student")
    public void theProfileBelongsToTheRuntimeStudent() {
        // retrieveRuntimeProfile() already asserts the returned profile's uuid matches the runtime
        // student before this step runs; reaching here IS the confirmation.
    }

    @When("the immutable name fields are captured from the profile")
    public void theImmutableNameFieldsAreCapturedFromTheProfile() {
        personalDetailsWorkflow.verifyImmutableFieldsProtected(context);
    }

    @Then("first name and last name are present and captured as the baseline")
    public void firstNameAndLastNameArePresentAndCapturedAsTheBaseline() {
        // verifyImmutableFieldsProtected() already asserts both are non-blank before this step runs;
        // reaching here IS the confirmation.
    }

    @When("each non-immutable mandatory field is probed blank against the backend")
    public void eachNonImmutableMandatoryFieldIsProbedBlankAgainstTheBackend() {
        personalDetailsWorkflow.verifyMandatoryFieldEnforcement(context);
    }

    @Then("the backend's mandatory field behaviour is recorded, not assumed")
    public void theBackendsMandatoryFieldBehaviourIsRecordedNotAssumed() {
        // verifyMandatoryFieldEnforcement() already probed each field and asserted no 5xx before this
        // step runs; reaching here IS the confirmation.
    }

    @When("the admin submits updated personal details with a unique runtime mobile number")
    public void theAdminSubmitsUpdatedPersonalDetailsWithAUniqueRuntimeMobileNumber() {
        personalDetailsWorkflow.updatePersonalDetails(context);
    }

    @Then("personal details are saved successfully")
    public void personalDetailsAreSavedSuccessfully() {
        // updatePersonalDetails() already asserts the save returned 2xx before this step runs;
        // reaching here IS the confirmation.
    }

    @When("the profile is retrieved again")
    public void theProfileIsRetrievedAgain() {
        personalDetailsWorkflow.verifyBackendPersistence(context);
    }

    @Then("every submitted field matches the backend, and immutable fields are unchanged")
    public void everySubmittedFieldMatchesTheBackendAndImmutableFieldsAreUnchanged() {
        // verifyBackendPersistence() already diffed every field and re-asserted firstName/lastName
        // before this step runs; reaching here IS the confirmation.
    }

    @When("the profile is read independently a second time")
    public void theProfileIsReadIndependentlyASecondTime() {
        personalDetailsWorkflow.verifySecondIndependentRead(context);
    }

    @Then("the second read still matches what was submitted")
    public void theSecondReadStillMatchesWhatWasSubmitted() {
        // verifySecondIndependentRead() already asserted the key fields match before this step runs;
        // reaching here IS the confirmation.
    }

    @Then("the personal details journey stage is complete")
    public void thePersonalDetailsJourneyStageIsComplete() {
        personalDetailsWorkflow.completePersonalDetailsJourneyStage(context);
    }
}
