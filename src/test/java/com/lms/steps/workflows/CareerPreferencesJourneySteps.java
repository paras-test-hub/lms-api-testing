package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.CareerPreferencesWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

public class CareerPreferencesJourneySteps {

    private final ScenarioContext context;
    private final CareerPreferencesWorkflow careerPreferencesWorkflow;

    public CareerPreferencesJourneySteps(ScenarioContext context) {
        this.context = context;
        this.careerPreferencesWorkflow = new CareerPreferencesWorkflow();
    }

    @Given("the runtime environment is ready for the career preferences journey")
    public void theRuntimeEnvironmentIsReadyForTheCareerPreferencesJourney() {
        context.execution().requireEnvironmentReady();
        assertThat(context.getStudentUuid()).as("runtime student must be available").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available").isNotBlank();
    }

    @When("the runtime student's profile is retrieved for career preferences")
    public void theRuntimeStudentsProfileIsRetrievedForCareerPreferences() {
        careerPreferencesWorkflow.retrieveRuntimeProfile(context);
    }

    @Then("the career preferences baseline is captured")
    public void theCareerPreferencesBaselineIsCaptured() {
    }

    @When("the career preferences are saved with domain selections and dream companies")
    public void theCareerPreferencesAreSavedWithDomainSelectionsAndDreamCompanies() {
        careerPreferencesWorkflow.saveCareerPreferences(context);
    }

    @Then("the career preferences save is accepted")
    public void theCareerPreferencesSaveIsAccepted() {
    }

    @When("the profile is retrieved to verify the career preferences save")
    public void theProfileIsRetrievedToVerifyTheCareerPreferencesSave() {
        careerPreferencesWorkflow.verifySavePersisted(context);
    }

    @Then("all career preference fields and dream company entries are persisted")
    public void allCareerPreferenceFieldsAndDreamCompanyEntriesArePersisted() {
    }

    @When("the career preferences are updated with modified domain and dream companies")
    public void theCareerPreferencesAreUpdatedWithModifiedDomainAndDreamCompanies() {
        careerPreferencesWorkflow.updateCareerPreferences(context);
    }

    @Then("the career preferences update is accepted")
    public void theCareerPreferencesUpdateIsAccepted() {
    }

    @When("the profile is retrieved to verify the career preferences update")
    public void theProfileIsRetrievedToVerifyTheCareerPreferencesUpdate() {
        careerPreferencesWorkflow.verifyUpdatePersisted(context);
    }

    @Then("the updated career preference fields are persisted")
    public void theUpdatedCareerPreferenceFieldsArePersisted() {
    }

    @Then("the career preferences journey stage is complete")
    public void theCareerPreferencesJourneyStageIsComplete() {
        careerPreferencesWorkflow.completeCareerPreferencesJourneyStage(context);
    }
}
