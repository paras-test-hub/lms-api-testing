package com.lms.steps.workflows;

import com.lms.cleanup.CreatedResource;
import com.lms.cleanup.ResourceType;
import com.lms.context.ScenarioContext;
import com.lms.workflows.SessionWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Session journey ({@code 10_journeys/SessionJourney.feature}) - the second business
 * journey, consuming the suite-scoped runtime environment. Thin by design: all logic lives in
 * {@link SessionWorkflow}. This covers the HAR-verified Phase 1 (admin creates an Offline Session on
 * the runtime course); the feature is designed to be extended in place as further HARs arrive.
 */
public class SessionJourneySteps {

    private final ScenarioContext context;
    private final SessionWorkflow sessionWorkflow;

    public SessionJourneySteps(ScenarioContext context) {
        this.context = context;
        this.sessionWorkflow = new SessionWorkflow();
    }

    @Given("the runtime environment is ready for the session journey")
    public void theRuntimeEnvironmentIsReadyForTheSessionJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getCourseId()).as("runtime courseId must be available from Environment Setup").isNotBlank();
        assertThat(context.getSectionId()).as("runtime sectionId must be available from Environment Setup").isNotBlank();
        assertThat(context.getAdminToken()).as("admin session must be available from Environment Setup").isNotBlank();
    }

    @When("the admin verifies the runtime course the session will belong to")
    public void theAdminVerifiesTheRuntimeCourseTheSessionWillBelongTo() {
        sessionWorkflow.verifyRuntimeCourse(context);
    }

    @Then("the runtime course is attached to the runtime section")
    public void theRuntimeCourseIsAttachedToTheRuntimeSection() {
        assertThat(context.getTermCourseId())
                .as("the runtime course's termCourse id must have been located")
                .isNotBlank();
    }

    @When("the admin retrieves the runtime locations and selects a test location")
    public void theAdminRetrievesTheRuntimeLocationsAndSelectsATestLocation() {
        sessionWorkflow.selectRuntimeTestLocation(context);
    }

    @Then("a runtime location is selected from the location lookup")
    public void aRuntimeLocationIsSelectedFromTheLocationLookup() {
        assertThat(context.getLocationUuid()).as("locationUuid must be stored").isNotBlank();
        assertThat(context.getLocationName()).as("locationName must be stored").isNotBlank();
    }

    @When("the admin creates an offline session on the runtime course")
    public void theAdminCreatesAnOfflineSessionOnTheRuntimeCourse() {
        sessionWorkflow.createOfflineSessionOnRuntimeCourse(context);
    }

    @Then("the offline session exists on the runtime course with a unique runtime name")
    public void theOfflineSessionExistsOnTheRuntimeCourseWithAUniqueRuntimeName() {
        assertThat(context.getSessionUuid())
                .as("session must have been created on the runtime course")
                .isNotBlank();
        assertThat(context.getSessionName())
                .as("a unique runtime session name must have been generated")
                .isNotBlank();
    }

    @When("the session is verified against the runtime course session list")
    public void theSessionIsVerifiedAgainstTheRuntimeCourseSessionList() {
        sessionWorkflow.verifyRuntimeSession(context);
    }

    @Then("the session details match what the admin submitted")
    public void theSessionDetailsMatchWhatTheAdminSubmitted() {
        // verifyRuntimeSession() already asserts name/mode/date/times/timezone/draft state/location
        // against the exact payload sent, before this step runs; reaching here IS the confirmation.
    }

    @When("the session is verified using the course session lookup for the runtime termCourseId")
    public void theSessionIsVerifiedUsingTheCourseSessionLookupForTheRuntimeTermCourseId() {
        sessionWorkflow.verifySessionViaCourseSessionLookup(context);
    }

    @Then("the session details match across both verification endpoints")
    public void theSessionDetailsMatchAcrossBothVerificationEndpoints() {
        // verifySessionViaCourseSessionLookup() already re-diffs every field against the SAME create
        // payload verifyRuntimeSession() diffed against, via a completely different endpoint, before
        // this step runs; reaching here IS the confirmation that both independent read paths agree.
    }

    @When("the session is cross-verified against the runtime program, cohort, section, and course chain")
    public void theSessionIsCrossVerifiedAgainstTheRuntimeProgramCohortSectionAndCourseChain() {
        sessionWorkflow.verifySessionRelationChain(context);
    }

    @Then("the session's full relation chain matches the runtime environment")
    public void theSessionsFullRelationChainMatchesTheRuntimeEnvironment() {
        // verifySessionRelationChain() already asserts every link (Program/Cohort/ProgramTerm/Section/
        // Course) and that the session appears in the course's SessionDetails; reaching here IS the
        // confirmation.
    }

    @Then("the session satisfies the session journey business rules")
    public void theSessionSatisfiesTheSessionJourneyBusinessRules() {
        sessionWorkflow.verifySessionBusinessRules(context);
    }

    @Then("the session is stored in the runtime context for later journey phases")
    public void theSessionIsStoredInTheRuntimeContextForLaterJourneyPhases() {
        assertThat(context.getSessionUuid()).as("sessionUuid must be stored").isNotBlank();
        assertThat(context.getSessionName()).as("sessionName must be stored").isNotBlank();
        assertThat(context.getSessionMode()).as("sessionMode must be stored").isEqualTo("offline");
        assertThat(context.getLocationUuid()).as("locationUuid must be stored").isNotBlank();
        assertThat(context.getLocationName()).as("locationName must be stored").isNotBlank();
    }

    @Then("the session is registered for suite cleanup")
    public void theSessionIsRegisteredForSuiteCleanup() {
        String sessionUuid = context.getSessionUuid();
        boolean registered = context.getCreatedResources().stream()
                .anyMatch(r -> r.getType() == ResourceType.SESSION && sessionUuid.equals(r.getId()));
        assertThat(registered)
                .as("the created session %s must be registered as ResourceType.SESSION for suite cleanup", sessionUuid)
                .isTrue();

        // The future delete handler will need the termCourse + section path ids - confirm they were captured.
        CreatedResource session = context.getCreatedResources().stream()
                .filter(r -> r.getType() == ResourceType.SESSION && sessionUuid.equals(r.getId()))
                .findFirst().orElseThrow();
        assertThat(session.getDeletionContext())
                .as("session cleanup registration must carry termCourseId + sectionId for a future delete handler")
                .containsKeys("termCourseId", "sectionId");
    }

    @Then("the session journey stage is complete")
    public void theSessionJourneyStageIsComplete() {
        sessionWorkflow.completeSessionJourneyStage(context);
    }
}
