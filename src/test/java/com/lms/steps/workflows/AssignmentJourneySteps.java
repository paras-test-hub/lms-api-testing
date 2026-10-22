package com.lms.steps.workflows;

import com.lms.cleanup.CreatedResource;
import com.lms.cleanup.ResourceType;
import com.lms.context.ScenarioContext;
import com.lms.workflows.AssignmentWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Assignment journey ({@code 10_journeys/AssignmentJourney.feature}) - the first
 * business journey, consuming the suite-scoped runtime environment. Thin by design: all logic lives
 * in {@link AssignmentWorkflow} (and, in later stages, the student/faculty workflow classes). This
 * currently covers the HAR-verified Admin Create Assignment stage; the feature is designed to be
 * extended in place as further HARs arrive.
 */
public class AssignmentJourneySteps {

    private final ScenarioContext context;
    private final AssignmentWorkflow assignmentWorkflow;

    public AssignmentJourneySteps(ScenarioContext context) {
        this.context = context;
        this.assignmentWorkflow = new AssignmentWorkflow();
    }

    @Given("the runtime environment is ready for the assignment journey")
    public void theRuntimeEnvironmentIsReadyForTheAssignmentJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getCourseId()).as("runtime courseId must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available from Environment Setup").isNotBlank();
    }

    @When("the admin creates and publishes an assignment on the runtime course")
    public void theAdminCreatesAndPublishesAnAssignmentOnTheRuntimeCourse() {
        assignmentWorkflow.createAssignmentOnRuntimeCourse(context);
    }

    @Then("the assignment exists on the runtime course with the submitted details")
    public void theAssignmentExistsOnTheRuntimeCourseWithTheSubmittedDetails() {
        // The workflow only stores the uuid AFTER a passing read-back verification, so a non-blank
        // stored uuid means "created AND verified against the runtime course".
        assertThat(context.getAssignmentUuid())
                .as("assignment must have been created and verified on the runtime course")
                .isNotBlank();
        assertThat(context.getTermCourseId())
                .as("the runtime course's termCourse id must have been located")
                .isNotBlank();
    }

    @Then("the assignment is stored in the runtime context for later journey stages")
    public void theAssignmentIsStoredInTheRuntimeContextForLaterJourneyStages() {
        assertThat(context.getAssignmentUuid()).as("assignmentUuid must be stored").isNotBlank();
        assertThat(context.getAssignmentName()).as("assignmentName must be stored").isNotBlank();
        assertThat(context.getAssignmentPublishStatus()).as("assignment publish status must be stored").isEqualTo("Published");
        assertThat(context.getAssignmentDueDate()).as("assignment due date must be stored").isNotBlank();
    }

    @Then("the assignment is registered for suite cleanup")
    public void theAssignmentIsRegisteredForSuiteCleanup() {
        String assignmentUuid = context.getAssignmentUuid();
        boolean registered = context.getCreatedResources().stream()
                .anyMatch(r -> r.getType() == ResourceType.ASSIGNMENT && assignmentUuid.equals(r.getId()));
        assertThat(registered)
                .as("the created assignment %s must be registered as ResourceType.ASSIGNMENT for suite cleanup",
                        assignmentUuid)
                .isTrue();

        // The future delete handler will need the termCourse + section path ids - confirm they were captured.
        CreatedResource assignment = context.getCreatedResources().stream()
                .filter(r -> r.getType() == ResourceType.ASSIGNMENT && assignmentUuid.equals(r.getId()))
                .findFirst().orElseThrow();
        assertThat(assignment.getDeletionContext())
                .as("assignment cleanup registration must carry termCourseId + sectionId for a future delete handler")
                .containsKeys("termCourseId", "sectionId");
    }

    // ================= Phase 2: admin cross-verification + student retrieve/submit =================

    @When("the assignment is cross-verified against the runtime program, cohort, and course chain")
    public void theAssignmentIsCrossVerifiedAgainstTheRuntimeProgramCohortAndCourseChain() {
        assignmentWorkflow.verifyRuntimeRelationChain(context);
    }

    @Then("the assignment's full relation chain matches the runtime environment")
    public void theAssignmentsFullRelationChainMatchesTheRuntimeEnvironment() {
        // verifyRuntimeRelationChain() already asserts every link (Course/ProgramTerm/Cohort/Program)
        // before this step runs; reaching here without failure IS the confirmation.
    }

    @When("the runtime student retrieves their assignment list")
    public void theRuntimeStudentRetrievesTheirAssignmentList() {
        assignmentWorkflow.studentRetrievesAssignmentList(context);
    }

    @Then("the runtime assignment appears in the student's own assignment list")
    public void theRuntimeAssignmentAppearsInTheStudentsOwnAssignmentList() {
        assertThat(context.getAssignmentId())
                .as("assignment's numeric id must have been captured from the student's own list")
                .isNotBlank();
    }

    @When("the runtime student retrieves the assignment details")
    public void theRuntimeStudentRetrievesTheAssignmentDetails() {
        assignmentWorkflow.studentRetrievesAssignmentDetails(context);
    }

    @Then("the assignment details match the assignment the runtime admin created")
    public void theAssignmentDetailsMatchTheAssignmentTheRuntimeAdminCreated() {
        // studentRetrievesAssignmentDetails() already asserts uuid/name/dueDate/publishStatus/relation
        // before this step runs; reaching here without failure IS the confirmation.
    }

    @When("the runtime student submits the assignment")
    public void theRuntimeStudentSubmitsTheAssignment() {
        assignmentWorkflow.studentSubmitsAssignment(context);
    }

    @Then("the submission is linked to the runtime assignment and stored in the runtime context")
    public void theSubmissionIsLinkedToTheRuntimeAssignmentAndStoredInTheRuntimeContext() {
        String submissionUuid = context.getSubmissionUuid();
        assertThat(submissionUuid).as("submissionUuid must be stored").isNotBlank();
        assertThat(context.getSubmissionStatus()).as("submissionStatus must be stored").isNotBlank();
        assertThat(context.getSubmissionTimestamp()).as("submissionTimestamp must be stored").isNotBlank();

        boolean registered = context.getCreatedResources().stream()
                .anyMatch(r -> r.getType() == ResourceType.ASSIGNMENT_SUBMISSION && submissionUuid.equals(r.getId()));
        assertThat(registered)
                .as("the created submission %s must be registered as ResourceType.ASSIGNMENT_SUBMISSION for suite cleanup",
                        submissionUuid)
                .isTrue();
    }

    @When("the submission is verified via the student's assignment list")
    public void theSubmissionIsVerifiedViaTheStudentsAssignmentList() {
        assignmentWorkflow.verifyStudentSubmission(context);
    }

    @Then("the runtime assignment shows a verified submission for the runtime student")
    public void theRuntimeAssignmentShowsAVerifiedSubmissionForTheRuntimeStudent() {
        // verifyStudentSubmission() already asserts latestSubmissionExists=true before this step runs;
        // reaching here without failure IS the confirmation.
    }

    // ================= Draft assignment: "Draft != Visible" =================

    @When("the admin creates a draft assignment on the runtime course")
    public void theAdminCreatesADraftAssignmentOnTheRuntimeCourse() {
        assignmentWorkflow.createDraftAssignmentOnRuntimeCourse(context);
    }

    @Then("the draft assignment exists on the runtime course and is verified as a draft")
    public void theDraftAssignmentExistsOnTheRuntimeCourseAndIsVerifiedAsADraft() {
        // createDraftAssignmentOnRuntimeCourse() already verifies isDraft=true via admin GET before
        // storing the uuid - a non-blank stored uuid means "created AND verified as a draft".
        assertThat(context.getDraftAssignmentUuid()).as("draftAssignmentUuid must be stored").isNotBlank();
        assertThat(context.getDraftAssignmentPublishStatus()).as("draft publish status must be 'Draft'").isEqualTo("Draft");
    }

    @When("the draft assignment's visibility to the student is checked")
    public void theDraftAssignmentsVisibilityToTheStudentIsChecked() {
        assignmentWorkflow.verifyDraftAssignmentNotVisibleToStudent(context);
    }

    @Then("the draft assignment does not appear in the student's own assignment list")
    public void theDraftAssignmentDoesNotAppearInTheStudentsOwnAssignmentList() {
        // verifyDraftAssignmentNotVisibleToStudent() already asserts absence before this step runs;
        // reaching here without failure IS the confirmation.
    }

    @When("the draft assignment is retrieved by the admin")
    public void theDraftAssignmentIsRetrievedByTheAdmin() {
        assignmentWorkflow.verifyDraftAssignmentVisibleToAdmin(context);
    }

    @Then("the draft assignment is still retrievable by the admin")
    public void theDraftAssignmentIsStillRetrievableByTheAdmin() {
        // verifyDraftAssignmentVisibleToAdmin() already asserts presence + isDraft=true before this
        // step runs; reaching here without failure IS the confirmation.
    }

    // ================= Draft assignment: "Draft -> Published" transition =================

    @When("the admin publishes the draft assignment")
    public void theAdminPublishesTheDraftAssignment() {
        assignmentWorkflow.publishDraftAssignment(context);
    }

    @Then("the draft assignment is now published")
    public void theDraftAssignmentIsNowPublished() {
        assertThat(context.getDraftAssignmentPublishStatus()).as("draft publish status must be 'Published'").isEqualTo("Published");
    }

    @When("the draft assignment's visibility to the student is re-checked")
    public void theDraftAssignmentsVisibilityToTheStudentIsRechecked() {
        assignmentWorkflow.verifyDraftAssignmentNowVisibleToStudent(context);
    }

    @Then("the published assignment now appears in the student's own assignment list")
    public void thePublishedAssignmentNowAppearsInTheStudentsOwnAssignmentList() {
        // verifyDraftAssignmentNowVisibleToStudent() already asserts presence before this step runs;
        // reaching here without failure IS the confirmation.
    }

    // ================= Published -> Unpublished transition (visibility toggles correctly) =================

    @When("the admin unpublishes the assignment")
    public void theAdminUnpublishesTheAssignment() {
        assignmentWorkflow.unpublishAssignment(context);
    }

    @Then("the assignment is now unpublished")
    public void theAssignmentIsNowUnpublished() {
        assertThat(context.getDraftAssignmentPublishStatus()).as("publish status must be 'Unpublished'").isEqualTo("Unpublished");
    }

    // ================= Multiple assignments - submission isolation =================

    @When("the admin creates three assignments A, B, and C on the runtime course")
    public void theAdminCreatesThreeAssignmentsAbAndCOnTheRuntimeCourse() {
        assignmentWorkflow.createMultipleAssignmentsOnRuntimeCourse(context);
    }

    @Then("all three assignments are created and verified on the runtime course")
    public void allThreeAssignmentsAreCreatedAndVerifiedOnTheRuntimeCourse() {
        assertThat(context.getMultiAssignmentUuids()).as("A, B, C uuids must be stored").hasSize(3);
    }

    @When("the runtime student retrieves their assignment list containing A, B, and C")
    public void theRuntimeStudentRetrievesTheirAssignmentListContainingABAndC() {
        assignmentWorkflow.studentVerifiesAllMultipleAssignmentsBelongToRuntimeCourse(context);
    }

    @Then("assignments A, B, and C all belong to the runtime course")
    public void assignmentsABAndCAllBelongToTheRuntimeCourse() {
        // studentVerifiesAllMultipleAssignmentsBelongToRuntimeCourse() already asserts this before
        // this step runs; reaching here without failure IS the confirmation.
    }

    @When("the runtime student submits only assignment B")
    public void theRuntimeStudentSubmitsOnlyAssignmentB() {
        assignmentWorkflow.studentSubmitsOnlyAssignmentB(context);
    }

    @Then("only assignment B is registered as submitted")
    public void onlyAssignmentBIsRegisteredAsSubmitted() {
        // studentSubmitsOnlyAssignmentB() already asserts the submission is linked to B before this
        // step runs; reaching here without failure IS the confirmation.
    }

    @When("the student's assignment list is re-checked for submission isolation")
    public void theStudentsAssignmentListIsRecheckedForSubmissionIsolation() {
        assignmentWorkflow.verifyOnlyAssignmentBReflectsSubmission(context);
    }

    @Then("only assignment B shows a submission while A and C remain unaffected")
    public void onlyAssignmentBShowsASubmissionWhileAAndCRemainUnaffected() {
        // verifyOnlyAssignmentBReflectsSubmission() already asserts this before this step runs;
        // reaching here without failure IS the confirmation.
    }

    // ================= Admin edits assignment content =================

    @When("the admin edits the assignment's title, description, marks, and due date")
    public void theAdminEditsTheAssignmentsTitleDescriptionMarksAndDueDate() {
        assignmentWorkflow.editAssignmentContent(context);
    }

    @Then("the assignment is updated with the new values")
    public void theAssignmentIsUpdatedWithTheNewValues() {
        // editAssignmentContent() already asserts the PATCH response reflects every new value before
        // this step runs; reaching here without failure IS the confirmation.
    }

    @When("the runtime student refreshes and retrieves the assignment details")
    public void theRuntimeStudentRefreshesAndRetrievesTheAssignmentDetails() {
        assignmentWorkflow.studentVerifiesUpdatedAssignmentDetails(context);
    }

    @Then("the student sees the updated title, description, marks, and due date")
    public void theStudentSeesTheUpdatedTitleDescriptionMarksAndDueDate() {
        // studentVerifiesUpdatedAssignmentDetails() already asserts this before this step runs;
        // reaching here without failure IS the confirmation.
    }

    // ================= Deadline enforcement: submission blocked after due date =================

    @When("the admin sets the assignment's due date to yesterday")
    public void theAdminSetsTheAssignmentsDueDateToYesterday() {
        assignmentWorkflow.backdateAssignmentDueDate(context);
    }

    @Then("the assignment's due date is now in the past")
    public void theAssignmentsDueDateIsNowInThePast() {
        // backdateAssignmentDueDate() already asserts the new due date before this step runs;
        // reaching here without failure IS the confirmation.
    }

    @When("the runtime student views the past-due assignment")
    public void theRuntimeStudentViewsThePastDueAssignment() {
        assignmentWorkflow.studentViewsPastDueAssignment(context);
    }

    @Then("the student can view the past-due assignment")
    public void theStudentCanViewThePastDueAssignment() {
        // studentViewsPastDueAssignment() already asserts a successful view before this step runs;
        // reaching here without failure IS the confirmation.
    }

    @When("the runtime student attempts to submit the past-due assignment")
    public void theRuntimeStudentAttemptsToSubmitThePastDueAssignment() {
        assignmentWorkflow.studentAttemptsToSubmitPastDueAssignment(context);
    }

    @Then("the submission is rejected because the deadline has passed")
    public void theSubmissionIsRejectedBecauseTheDeadlineHasPassed() {
        // studentAttemptsToSubmitPastDueAssignment() already asserts the 406 rejection before this
        // step runs; reaching here without failure IS the confirmation.
    }

    // ================= Deadline enforcement: late submission allowed (deadLineToggle=true) =================

    @When("the admin enables late submission on the past-due assignment")
    public void theAdminEnablesLateSubmissionOnThePastDueAssignment() {
        assignmentWorkflow.enableLateSubmissionOnPastDueAssignment(context);
    }

    @Then("the assignment now allows submission after its deadline")
    public void theAssignmentNowAllowsSubmissionAfterItsDeadline() {
        // enableLateSubmissionOnPastDueAssignment() already asserts deadLineToggle=true before this
        // step runs; reaching here without failure IS the confirmation.
    }

    @When("the runtime student submits the past-due assignment with late submission allowed")
    public void theRuntimeStudentSubmitsThePastDueAssignmentWithLateSubmissionAllowed() {
        assignmentWorkflow.studentSubmitsPastDueAssignmentWithLateSubmissionAllowed(context);
    }

    @Then("the submission succeeds despite being past the deadline")
    public void theSubmissionSucceedsDespiteBeingPastTheDeadline() {
        // studentSubmitsPastDueAssignmentWithLateSubmissionAllowed() already asserts success before
        // this step runs; reaching here without failure IS the confirmation.
    }

    // ================= Universal scenario-terminal reporting step =================

    @Then("the assignment journey stage is complete")
    public void theAssignmentJourneyStageIsComplete() {
        assignmentWorkflow.completeAssignmentJourneyStage(context);
    }
}
