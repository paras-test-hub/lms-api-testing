package com.lms.context;

import com.lms.cleanup.CreatedResource;
import com.lms.cleanup.ResourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Suite-scoped single source of truth for the runtime environment the whole test suite shares.
 *
 * <p>Unlike {@link ScenarioContext} (one fresh instance per scenario, via PicoContainer), this
 * is a process-wide singleton that survives for the entire suite. {@code EnvironmentSetup.feature}
 * provisions the isolated academic environment ONCE and stores every runtime-created entity here;
 * every subsequent business journey (Assignment, Attendance, Quiz, ...) reads these ids/sessions
 * rather than recreating anything; {@code Cleanup.feature} tears the whole thing down once at the
 * end. See {@link ScenarioContext}, whose entity accessors delegate here so existing workflow and
 * step code keeps compiling unchanged while its state becomes suite-scoped.
 *
 * <p>Scope split: this holds the durable, cross-scenario environment (admin session, provisioned
 * program/cohort/term/section/course/student, runtime student session, and the single cleanup
 * registry that accumulates across setup AND every journey). Per-scenario ephemera (last HTTP
 * response, the Cucumber Scenario handle, HTML-report accumulators, ad-hoc auth-request scratch)
 * stay in {@link ScenarioContext}.
 *
 * <p>A singleton is safe here because a single shared runtime environment is inherently
 * incompatible with parallel scenario execution (ENABLE_PARALLEL_EXECUTION is off); {@link #reset()}
 * is called from the suite's {@code @BeforeAll} so an in-JVM re-run starts clean.
 */
public final class ExecutionContext {

    private static ExecutionContext instance = new ExecutionContext();

    /** The suite-wide instance. Never null (reset replaces it, never nulls it). */
    public static ExecutionContext get() {
        return instance;
    }

    /** Fresh slate for a new suite run (invoked from the suite {@code @BeforeAll}). */
    public static synchronized void reset() {
        instance = new ExecutionContext();
    }

    private ExecutionContext() {
    }

    // ---- Admin session (one automation admin for the whole suite) ----
    private String adminToken;
    private String adminRefreshToken;
    private String adminUserId;
    private String adminOrgId;

    // ---- Provisioned environment entities (set once by EnvironmentSetup) ----
    private String programId;
    private String programName;
    private String cohortId;
    private String cohortName;
    private String programTermId;
    /**
     * Runtime Term 1 business state, set once by Environment Setup after it PATCHes the
     * backend-auto-created "Term 1" into a valid date window and reads it back. Dates are stored as
     * ISO calendar strings (yyyy-MM-dd), matching the String convention used for other date fields
     * (e.g. assignmentDueDate); consumers normalize with DateUtils.toCalendarDate. Deliberately does
     * NOT hold term minCredit/maxCredit (the PATCH does not persist them - they stay null),
     * isActiveTerm (stays false even inside a valid window), or course-count limits (no consumer).
     * Course-level credit remains the separate {@link #courseCredit}.
     */
    private String programTermName;
    private String programTermStartDate;
    private String programTermEndDate;
    private String sectionId;
    private String courseId;
    private String courseName;
    private String courseCode;
    private int courseCredit;
    private String departmentId;
    private String courseTypeId;
    private String classTypeId;
    private String courseModeId;
    private String attendanceType;
    private String coursePurpose;
    private String courseTagId;
    private String sectionCourseId;
    private String studentEnrollmentUuid;
    private String enrollmentId;

    /**
     * The "termCourse" mapping id - the course as attached to a section within a program term. It
     * is NOT the courseMaster id (courseId): the assignment endpoints are keyed by termCourse
     * ({@code /program/termCourse/{termCourseId}/section/{sectionId}/...}). Resolved by the
     * Assignment journey from the section-detail read-back (the Data.data[] entry whose
     * CourseMaster.uuid == courseId), and reused by every later assignment stage.
     */
    private String termCourseId;

    // ---- Capstone journey outputs (set by the Capstone Mini-Setup stage) ----

    /**
     * Capstone Mini-Setup outputs. {@code capstoneSectionId}/{@code capstoneSectionName} are the NEW
     * capstone Section - never the Environment Setup {@link #sectionId}. {@code capstoneTermCourseId}
     * is the runtime CourseMaster's instance inside THAT section: a different entity from {@link
     * #termCourseId}, which the Assignment / Session / Attendance journeys depend on. All three are
     * resolved once per stage, from response payloads captured during the current run.
     */
    private String capstoneSectionId;
    private String capstoneSectionName;
    private String capstoneTermCourseId;

    /**
     * The capstone gamification configuration's {@code Data.uuid} from
     * {@code POST /api/v1/program/capstoneGamifications}. Used to locate the persisted config BY
     * IDENTITY inside {@code Data.termCourse.GamificationConfigs[]} on the termCourse read-back,
     * rather than by array position.
     */
    private String capstoneGamificationConfigUuid;

    /**
     * The SAME configuration's {@code Data.id} - a NUMERIC internal id. It is NOT interchangeable
     * with {@link #capstoneGamificationConfigUuid}: the rewards API keys off this numeric value
     * ({@code POST /api/v1/program/gamificationRewards} sends {@code gamificationConfigId:
     * <numeric>}, and {@code GET /api/v1/program/gamificationRewards/{numeric}} uses it as the path
     * segment). Stored separately for exactly that reason.
     */
    private Integer capstoneGamificationConfigNumericId;

    /**
     * The created reward's {@code Data.uuid} from {@code POST /api/v1/program/gamificationRewards},
     * used to match the reward by identity in the rewards read-back rather than by name.
     */
    private String capstoneRewardUuid;

    /**
     * The Capstone team's identity, captured ONCE from the team-creation response - {@code Data[0].uuid}
     * and {@code Data[0].name} of {@code POST /api/v1/program/addStudentsInGroup}. Every later S3
     * verification step READS these stored values and never re-derives the team identity from a list
     * read, a name search, an array position or the {@code Count} field: the admin-side
     * {@code getAllSectionGroups} read locates its entry by {@code uuid} equality against
     * {@link #capstoneTeamUuid}, and the student-side read cross-checks {@code Data.userGroup.uuid}
     * against the same value. {@link #capstoneTeamName} is the name the creation request sent, echoed
     * back by the create response, and is compared by equality on both later reads - it is never used
     * to FIND the team.
     */
    private String capstoneTeamUuid;
    private String capstoneTeamName;

    // NOTE: the three Capstone S2 configuration ids above are distinct from, and must never
    // overwrite, capstoneTermCourseId / capstoneSectionId or the normal runtime termCourseId /
    // sectionId.

    // ---- Assignment journey outputs (set by AssignmentJourney; consumed by later stages) ----
    private String assignmentUuid;
    private String assignmentName;
    private String assignmentPublishStatus;
    private String assignmentDueDate;
    /** The assignment's numeric internal id (distinct from assignmentUuid) - only exposed by the
     * student-side getAllStudentAssignments list response, not by the admin create/verify responses. */
    private String assignmentId;
    /** The resolved "Create Assignments" assignmentResourceId used to create this assignment. */
    private String assignmentResourceId;

    // ---- Assignment submission outputs (Phase 2: set by the student submission stage) ----
    private String submissionUuid;
    private String submissionStatus;
    private String submissionTimestamp;

    /**
     * A SECOND, separate assignment (isDraft=true) created to verify the "Draft != Visible" business
     * rule - a draft must remain visible to the admin but hidden from the student. Deliberately
     * distinct fields from assignmentUuid/assignmentName (the published assignment), since both
     * coexist on the runtime course at the same time.
     */
    private String draftAssignmentUuid;
    private String draftAssignmentName;
    /** "Draft" until published via PATCH assignmentDetail/{id} (isDraft: true -> false), then "Published". */
    private String draftAssignmentPublishStatus;

    /**
     * THREE separate assignments ("A", "B", "C") created for the multi-assignment isolation scenario -
     * confirms all three belong to the runtime course and that submitting one does not affect the
     * others. Index-aligned with {@link #multiAssignmentNames} (0=A, 1=B, 2=C).
     */
    private List<String> multiAssignmentUuids = new ArrayList<>();
    private List<String> multiAssignmentNames = new ArrayList<>();

    /**
     * The admin-edited content applied to draftAssignmentUuid (title reuses {@link
     * #draftAssignmentName}, updated in place by the edit). Set by the admin-edit scenario; read back
     * by the student-side verification that follows.
     */
    private String editedAssignmentDetails;
    private String editedAssignmentMarks;
    private String editedAssignmentDueDate;

    /**
     * Session journey outputs (Phase 1: admin creates an Offline Session on the runtime course).
     * Deliberately only the runtime entities later phases (Attendance, QR attendance, Live Session,
     * Faculty) will reuse - NOT a copy of the whole create payload. The session's schedule/date fields
     * are re-read from the session GET when a later phase needs them, rather than duplicated here.
     */
    private String sessionUuid;
    private String sessionName;
    /** Always "offline" for Phase 1 - Online/Hybrid sessions are their own future journeys. */
    private String sessionMode;
    /** BiometricRoom.uuid of the room the session was created in (sent as roomLocationId). */
    private String locationUuid;
    /** BiometricRoom.roomName - the room's display name, as returned by the room lookup. */
    private String locationName;

    /**
     * Attendance journey outputs (Phase 1: admin marks the runtime student Present, then Absent).
     * {@code attendanceRecordUuid} is the temp-attendance record the mark call returns - the SAME
     * record is overwritten by the update, so a second value never appears. {@code studentNumericId}
     * is the runtime student's numeric id, resolved from the section roster because the attendance
     * API keys students by that rather than by uuid; later attendance journeys (QR, OTP, biometric)
     * reuse it instead of re-reading the roster.
     */
    private String attendanceRecordUuid;
    private String attendanceStatus;
    private String studentNumericId;

    // ---- Runtime student identity + session (the only "valid student") ----
    private String studentUuid;
    private String studentEmail;
    private String studentPassword;
    private String studentToken;
    private String studentRefreshToken;
    private String studentUserId;
    private String studentProgramId;
    private String studentCohortId;

    // ---- Suite lifecycle flags ----
    private boolean environmentReady;
    private boolean cleanupCompleted;

    /**
     * The single cleanup registry for the WHOLE suite, in creation order. EnvironmentSetup and
     * every business journey append to it; {@code Cleanup.feature} walks it in REVERSE at the end.
     */
    private final List<CreatedResource> createdResources = new ArrayList<>();

    // ---- Admin session ----
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }

    public String getAdminRefreshToken() { return adminRefreshToken; }
    public void setAdminRefreshToken(String adminRefreshToken) { this.adminRefreshToken = adminRefreshToken; }

    public String getAdminUserId() { return adminUserId; }
    public void setAdminUserId(String adminUserId) { this.adminUserId = adminUserId; }

    public String getAdminOrgId() { return adminOrgId; }
    public void setAdminOrgId(String adminOrgId) { this.adminOrgId = adminOrgId; }

    // ---- Environment entities ----
    public String getProgramId() { return programId; }
    public void setProgramId(String programId) { this.programId = programId; }

    public String getProgramName() { return programName; }
    public void setProgramName(String programName) { this.programName = programName; }

    public String getCohortId() { return cohortId; }
    public void setCohortId(String cohortId) { this.cohortId = cohortId; }

    public String getCohortName() { return cohortName; }
    public void setCohortName(String cohortName) { this.cohortName = cohortName; }

    public String getProgramTermId() { return programTermId; }
    public void setProgramTermId(String programTermId) { this.programTermId = programTermId; }

    public String getProgramTermName() { return programTermName; }
    public void setProgramTermName(String programTermName) { this.programTermName = programTermName; }

    public String getProgramTermStartDate() { return programTermStartDate; }
    public void setProgramTermStartDate(String programTermStartDate) { this.programTermStartDate = programTermStartDate; }

    public String getProgramTermEndDate() { return programTermEndDate; }
    public void setProgramTermEndDate(String programTermEndDate) { this.programTermEndDate = programTermEndDate; }

    public String getSectionId() { return sectionId; }
    public void setSectionId(String sectionId) { this.sectionId = sectionId; }

    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public String getCourseCode() { return courseCode; }
    public void setCourseCode(String courseCode) { this.courseCode = courseCode; }

    public int getCourseCredit() { return courseCredit; }
    public void setCourseCredit(int courseCredit) { this.courseCredit = courseCredit; }

    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

    public String getCourseTypeId() { return courseTypeId; }
    public void setCourseTypeId(String courseTypeId) { this.courseTypeId = courseTypeId; }

    public String getClassTypeId() { return classTypeId; }
    public void setClassTypeId(String classTypeId) { this.classTypeId = classTypeId; }

    public String getCourseModeId() { return courseModeId; }
    public void setCourseModeId(String courseModeId) { this.courseModeId = courseModeId; }

    public String getAttendanceType() { return attendanceType; }
    public void setAttendanceType(String attendanceType) { this.attendanceType = attendanceType; }

    public String getCoursePurpose() { return coursePurpose; }
    public void setCoursePurpose(String coursePurpose) { this.coursePurpose = coursePurpose; }

    public String getCourseTagId() { return courseTagId; }
    public void setCourseTagId(String courseTagId) { this.courseTagId = courseTagId; }

    public String getSectionCourseId() { return sectionCourseId; }
    public void setSectionCourseId(String sectionCourseId) { this.sectionCourseId = sectionCourseId; }

    public String getStudentEnrollmentUuid() { return studentEnrollmentUuid; }
    public void setStudentEnrollmentUuid(String studentEnrollmentUuid) { this.studentEnrollmentUuid = studentEnrollmentUuid; }

    public String getEnrollmentId() { return enrollmentId; }
    public void setEnrollmentId(String enrollmentId) { this.enrollmentId = enrollmentId; }

    public String getTermCourseId() { return termCourseId; }
    public void setTermCourseId(String termCourseId) { this.termCourseId = termCourseId; }

    // ---- Capstone journey outputs ----
    public String getCapstoneSectionId() { return capstoneSectionId; }
    public void setCapstoneSectionId(String capstoneSectionId) { this.capstoneSectionId = capstoneSectionId; }

    public String getCapstoneSectionName() { return capstoneSectionName; }
    public void setCapstoneSectionName(String capstoneSectionName) { this.capstoneSectionName = capstoneSectionName; }

    public String getCapstoneTermCourseId() { return capstoneTermCourseId; }
    public void setCapstoneTermCourseId(String capstoneTermCourseId) { this.capstoneTermCourseId = capstoneTermCourseId; }

    public String getCapstoneGamificationConfigUuid() { return capstoneGamificationConfigUuid; }
    public void setCapstoneGamificationConfigUuid(String capstoneGamificationConfigUuid) { this.capstoneGamificationConfigUuid = capstoneGamificationConfigUuid; }

    public Integer getCapstoneGamificationConfigNumericId() { return capstoneGamificationConfigNumericId; }
    public void setCapstoneGamificationConfigNumericId(Integer capstoneGamificationConfigNumericId) { this.capstoneGamificationConfigNumericId = capstoneGamificationConfigNumericId; }

    public String getCapstoneRewardUuid() { return capstoneRewardUuid; }
    public void setCapstoneRewardUuid(String capstoneRewardUuid) { this.capstoneRewardUuid = capstoneRewardUuid; }

    public String getCapstoneTeamUuid() { return capstoneTeamUuid; }
    public void setCapstoneTeamUuid(String capstoneTeamUuid) { this.capstoneTeamUuid = capstoneTeamUuid; }

    public String getCapstoneTeamName() { return capstoneTeamName; }
    public void setCapstoneTeamName(String capstoneTeamName) { this.capstoneTeamName = capstoneTeamName; }

    // ---- Assignment journey outputs ----
    public String getAssignmentUuid() { return assignmentUuid; }
    public void setAssignmentUuid(String assignmentUuid) { this.assignmentUuid = assignmentUuid; }

    public String getAssignmentName() { return assignmentName; }
    public void setAssignmentName(String assignmentName) { this.assignmentName = assignmentName; }

    public String getAssignmentPublishStatus() { return assignmentPublishStatus; }
    public void setAssignmentPublishStatus(String assignmentPublishStatus) { this.assignmentPublishStatus = assignmentPublishStatus; }

    public String getAssignmentDueDate() { return assignmentDueDate; }
    public void setAssignmentDueDate(String assignmentDueDate) { this.assignmentDueDate = assignmentDueDate; }

    public String getAssignmentId() { return assignmentId; }
    public void setAssignmentId(String assignmentId) { this.assignmentId = assignmentId; }

    public String getAssignmentResourceId() { return assignmentResourceId; }
    public void setAssignmentResourceId(String assignmentResourceId) { this.assignmentResourceId = assignmentResourceId; }

    // ---- Assignment submission ----
    public String getSubmissionUuid() { return submissionUuid; }
    public void setSubmissionUuid(String submissionUuid) { this.submissionUuid = submissionUuid; }

    public String getSubmissionStatus() { return submissionStatus; }
    public void setSubmissionStatus(String submissionStatus) { this.submissionStatus = submissionStatus; }

    public String getSubmissionTimestamp() { return submissionTimestamp; }
    public void setSubmissionTimestamp(String submissionTimestamp) { this.submissionTimestamp = submissionTimestamp; }

    // ---- Draft assignment (Draft != Visible business rule) ----
    public String getDraftAssignmentUuid() { return draftAssignmentUuid; }
    public void setDraftAssignmentUuid(String draftAssignmentUuid) { this.draftAssignmentUuid = draftAssignmentUuid; }

    public String getDraftAssignmentName() { return draftAssignmentName; }
    public void setDraftAssignmentName(String draftAssignmentName) { this.draftAssignmentName = draftAssignmentName; }

    public String getDraftAssignmentPublishStatus() { return draftAssignmentPublishStatus; }
    public void setDraftAssignmentPublishStatus(String draftAssignmentPublishStatus) { this.draftAssignmentPublishStatus = draftAssignmentPublishStatus; }

    // ---- Multi-assignment (A/B/C) isolation scenario ----
    public List<String> getMultiAssignmentUuids() { return multiAssignmentUuids; }
    public void setMultiAssignmentUuids(List<String> multiAssignmentUuids) { this.multiAssignmentUuids = multiAssignmentUuids; }

    public List<String> getMultiAssignmentNames() { return multiAssignmentNames; }
    public void setMultiAssignmentNames(List<String> multiAssignmentNames) { this.multiAssignmentNames = multiAssignmentNames; }

    // ---- Admin-edited assignment content ----
    public String getEditedAssignmentDetails() { return editedAssignmentDetails; }
    public void setEditedAssignmentDetails(String editedAssignmentDetails) { this.editedAssignmentDetails = editedAssignmentDetails; }

    public String getEditedAssignmentMarks() { return editedAssignmentMarks; }
    public void setEditedAssignmentMarks(String editedAssignmentMarks) { this.editedAssignmentMarks = editedAssignmentMarks; }

    public String getEditedAssignmentDueDate() { return editedAssignmentDueDate; }
    public void setEditedAssignmentDueDate(String editedAssignmentDueDate) { this.editedAssignmentDueDate = editedAssignmentDueDate; }

    // ---- Session journey outputs ----
    public String getSessionUuid() { return sessionUuid; }
    public void setSessionUuid(String sessionUuid) { this.sessionUuid = sessionUuid; }

    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }

    public String getSessionMode() { return sessionMode; }
    public void setSessionMode(String sessionMode) { this.sessionMode = sessionMode; }

    public String getLocationUuid() { return locationUuid; }
    public void setLocationUuid(String locationUuid) { this.locationUuid = locationUuid; }

    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }

    // ---- Attendance journey outputs ----
    public String getAttendanceRecordUuid() { return attendanceRecordUuid; }
    public void setAttendanceRecordUuid(String attendanceRecordUuid) { this.attendanceRecordUuid = attendanceRecordUuid; }

    public String getAttendanceStatus() { return attendanceStatus; }
    public void setAttendanceStatus(String attendanceStatus) { this.attendanceStatus = attendanceStatus; }

    public String getStudentNumericId() { return studentNumericId; }
    public void setStudentNumericId(String studentNumericId) { this.studentNumericId = studentNumericId; }

    // ---- Runtime student ----
    public String getStudentUuid() { return studentUuid; }
    public void setStudentUuid(String studentUuid) { this.studentUuid = studentUuid; }

    public String getStudentEmail() { return studentEmail; }
    public void setStudentEmail(String studentEmail) { this.studentEmail = studentEmail; }

    public String getStudentPassword() { return studentPassword; }
    public void setStudentPassword(String studentPassword) { this.studentPassword = studentPassword; }

    public String getStudentToken() { return studentToken; }
    public void setStudentToken(String studentToken) { this.studentToken = studentToken; }

    public String getStudentRefreshToken() { return studentRefreshToken; }
    public void setStudentRefreshToken(String studentRefreshToken) { this.studentRefreshToken = studentRefreshToken; }

    public String getStudentUserId() { return studentUserId; }
    public void setStudentUserId(String studentUserId) { this.studentUserId = studentUserId; }

    public String getStudentProgramId() { return studentProgramId; }
    public void setStudentProgramId(String studentProgramId) { this.studentProgramId = studentProgramId; }

    public String getStudentCohortId() { return studentCohortId; }
    public void setStudentCohortId(String studentCohortId) { this.studentCohortId = studentCohortId; }

    // ---- Lifecycle ----

    /** True once EnvironmentSetup has provisioned + authenticated the runtime environment. */
    public boolean isEnvironmentReady() {
        return environmentReady;
    }

    public void markEnvironmentReady() {
        this.environmentReady = true;
    }

    /**
     * Fail-fast guard for business journeys: they must never run without a provisioned
     * environment. Called from a journey's opening precondition step.
     */
    public void requireEnvironmentReady() {
        if (!environmentReady) {
            throw new IllegalStateException(
                    "Runtime environment is not ready - EnvironmentSetup.feature must provision it before any "
                            + "business journey runs. Run the full suite (or include features/environment) rather "
                            + "than a journey in isolation.");
        }
    }

    /** True once Cleanup.feature has completed teardown - the {@code @AfterAll} leak-guard checks this. */
    public boolean isCleanupCompleted() {
        return cleanupCompleted;
    }

    public void markCleanupCompleted() {
        this.cleanupCompleted = true;
    }

    // ---- Concern journey outputs ----

    private String concernCategoryName;
    private String concernSubCategoryName;
    private String concernSubCategoryUuid;
    private boolean concernEscalationConfigured;

    public String getConcernCategoryName() { return concernCategoryName; }
    public void setConcernCategoryName(String concernCategoryName) { this.concernCategoryName = concernCategoryName; }

    public String getConcernSubCategoryName() { return concernSubCategoryName; }
    public void setConcernSubCategoryName(String concernSubCategoryName) { this.concernSubCategoryName = concernSubCategoryName; }

    public String getConcernSubCategoryUuid() { return concernSubCategoryUuid; }
    public void setConcernSubCategoryUuid(String concernSubCategoryUuid) { this.concernSubCategoryUuid = concernSubCategoryUuid; }

    public boolean isConcernEscalationConfigured() { return concernEscalationConfigured; }
    public void setConcernEscalationConfigured(boolean concernEscalationConfigured) { this.concernEscalationConfigured = concernEscalationConfigured; }

    // ---- Cleanup registry ----
    public void registerCreated(ResourceType type, String id) {
        createdResources.add(new CreatedResource(type, id));
    }

    public void registerCreated(ResourceType type, String id, Map<String, String> deletionContext) {
        createdResources.add(new CreatedResource(type, id, deletionContext));
    }

    /** Created resources in creation order; Cleanup walks this in reverse (children before parents). */
    public List<CreatedResource> getCreatedResources() {
        return Collections.unmodifiableList(createdResources);
    }
}
