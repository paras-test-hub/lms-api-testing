package com.lms.steps.workflows;

import com.lms.cleanup.CreatedResource;
import com.lms.cleanup.ResourceType;
import com.lms.clients.AttendanceClient;
import com.lms.context.ScenarioContext;
import com.lms.workflows.AttendanceWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the Attendance journey ({@code 10_journeys/AttendanceJourney.feature}) - the third
 * business journey, consuming the suite-scoped runtime environment. Thin by design: all logic lives
 * in {@link AttendanceWorkflow}, which in turn REUSES {@code SessionWorkflow} for session creation
 * rather than duplicating it.
 */
public class AttendanceJourneySteps {

    private final ScenarioContext context;
    private final AttendanceWorkflow attendanceWorkflow;

    public AttendanceJourneySteps(ScenarioContext context) {
        this.context = context;
        this.attendanceWorkflow = new AttendanceWorkflow();
    }

    @Given("the runtime environment is ready for the attendance journey")
    public void theRuntimeEnvironmentIsReadyForTheAttendanceJourney() {
        // Fail-fast precondition guard: the journey must never run without a provisioned environment.
        context.execution().requireEnvironmentReady();
        assertThat(context.getCourseId()).as("runtime courseId must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentUuid()).as("runtime student must be available from Environment Setup").isNotBlank();
        assertThat(context.getStudentToken()).as("runtime student session must be available from Environment Setup").isNotBlank();
        assertThat(context.getAdminToken()).as("admin session must be available from Environment Setup").isNotBlank();
    }

    @When("a completed offline session is created on the runtime course for attendance")
    public void aCompletedOfflineSessionIsCreatedOnTheRuntimeCourseForAttendance() {
        attendanceWorkflow.createRuntimeSessionForAttendance(context);
    }

    @Then("the runtime session is available for attendance")
    public void theRuntimeSessionIsAvailableForAttendance() {
        assertThat(context.getSessionUuid())
                .as("a runtime session must have been created and verified by SessionWorkflow")
                .isNotBlank();
    }

    @When("the admin views the runtime session")
    public void theAdminViewsTheRuntimeSession() {
        attendanceWorkflow.verifySessionVisibleToAdmin(context);
    }

    @Then("the runtime session is visible to the admin")
    public void theRuntimeSessionIsVisibleToTheAdmin() {
        // verifySessionVisibleToAdmin() already asserts the session is present in the admin attendance
        // session list before this step runs; reaching here IS the confirmation.
    }

    @When("the runtime student views their sessions")
    public void theRuntimeStudentViewsTheirSessions() {
        attendanceWorkflow.verifySessionVisibleToStudent(context);
    }

    @Then("the runtime session is visible to the student")
    public void theRuntimeSessionIsVisibleToTheStudent() {
        // verifySessionVisibleToStudent() already asserts the session appears in the runtime student's
        // own day view before this step runs; reaching here IS the confirmation.
    }

    @When("the admin marks the runtime student present")
    public void theAdminMarksTheRuntimeStudentPresent() {
        attendanceWorkflow.markRuntimeStudentPresent(context);
    }

    @Then("the attendance record exists for the runtime student")
    public void theAttendanceRecordExistsForTheRuntimeStudent() {
        assertThat(context.getAttendanceRecordUuid()).as("attendance record uuid must be stored").isNotBlank();
        assertThat(context.getStudentNumericId()).as("runtime student's numeric id must be stored").isNotBlank();
    }

    @When("the admin verifies the attendance is present")
    public void theAdminVerifiesTheAttendanceIsPresent() {
        attendanceWorkflow.verifyAdminAttendance(context, AttendanceClient.STATUS_PRESENT);
    }

    @When("the runtime student verifies their attendance is present")
    public void theRuntimeStudentVerifiesTheirAttendanceIsPresent() {
        attendanceWorkflow.verifyStudentAttendance(context, AttendanceClient.STATUS_PRESENT);
    }

    @Then("the admin and student both report the runtime student as present")
    public void theAdminAndStudentBothReportTheRuntimeStudentAsPresent() {
        assertThat(context.getAttendanceStatus())
                .as("the runtime attendance status must be stored as Present")
                .isEqualTo(AttendanceClient.STATUS_PRESENT);
    }

    @When("the admin updates the runtime student to absent")
    public void theAdminUpdatesTheRuntimeStudentToAbsent() {
        attendanceWorkflow.updateRuntimeStudentToAbsent(context);
    }

    @Then("the same attendance record is modified rather than duplicated")
    public void theSameAttendanceRecordIsModifiedRatherThanDuplicated() {
        // updateRuntimeStudentToAbsent() already asserts the returned record uuid is unchanged before
        // this step runs; reaching here IS the confirmation that no second record was created.
        assertThat(context.getAttendanceStatus())
                .as("the runtime attendance status must be stored as Absent")
                .isEqualTo(AttendanceClient.STATUS_ABSENT);
    }

    @When("the admin verifies the attendance is absent")
    public void theAdminVerifiesTheAttendanceIsAbsent() {
        attendanceWorkflow.verifyAdminAttendance(context, AttendanceClient.STATUS_ABSENT);
    }

    @When("the runtime student verifies their attendance is absent")
    public void theRuntimeStudentVerifiesTheirAttendanceIsAbsent() {
        attendanceWorkflow.verifyStudentAttendance(context, AttendanceClient.STATUS_ABSENT);
    }

    @Then("the admin and student remain synchronized throughout the attendance journey")
    public void theAdminAndStudentRemainSynchronizedThroughoutTheAttendanceJourney() {
        attendanceWorkflow.verifyAdminStudentSynchronization(context);
    }

    @Then("the attendance is registered for suite cleanup")
    public void theAttendanceIsRegisteredForSuiteCleanup() {
        String recordUuid = context.getAttendanceRecordUuid();
        boolean registered = context.getCreatedResources().stream()
                .anyMatch(r -> r.getType() == ResourceType.ATTENDANCE && recordUuid.equals(r.getId()));
        assertThat(registered)
                .as("the attendance record %s must be registered as ResourceType.ATTENDANCE for suite cleanup", recordUuid)
                .isTrue();

        // The future delete handler will need the session + termCourse ids - confirm they were captured.
        CreatedResource attendance = context.getCreatedResources().stream()
                .filter(r -> r.getType() == ResourceType.ATTENDANCE && recordUuid.equals(r.getId()))
                .findFirst().orElseThrow();
        assertThat(attendance.getDeletionContext())
                .as("attendance cleanup registration must carry sessionUuid + termCourseId for a future delete handler")
                .containsKeys("sessionUuid", "termCourseId");
    }

    @Then("the attendance journey stage is complete")
    public void theAttendanceJourneyStageIsComplete() {
        attendanceWorkflow.completeAttendanceJourneyStage(context);
    }
}
