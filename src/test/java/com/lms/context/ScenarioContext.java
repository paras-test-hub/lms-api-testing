package com.lms.context;

import com.lms.cleanup.CreatedResource;
import com.lms.cleanup.ResourceType;
import io.cucumber.java.Scenario;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-scenario state, injected fresh into every step/hook class by Cucumber-Java's PicoContainer
 * (one instance per scenario, so no manual reset is needed).
 *
 * <p>Since the framework moved to a suite-scoped runtime environment, this class now plays two
 * roles:
 * <ul>
 *   <li><b>Per-scenario ephemera</b> it OWNS: the last HTTP response, the Cucumber {@link Scenario}
 *       handle, the HTML-report line accumulator, an ad-hoc attribute bag, and the auth-request
 *       scratch fields (studentEmail/password, adminEmail/password) used only by the login-endpoint
 *       tests in {@code AuthenticationSteps}. These are genuinely per-scenario and must NOT leak
 *       across scenarios.</li>
 *   <li><b>Runtime environment</b> it DELEGATES to {@link ExecutionContext}: admin session, the
 *       provisioned program/cohort/term/section/course, the runtime student session, and the
 *       cleanup registry. The accessors are kept here (so existing workflow/step/cleanup code
 *       compiles unchanged) but read/write the suite-scoped singleton, so a value set during
 *       EnvironmentSetup is visible to every later journey.</li>
 * </ul>
 *
 * <p>Note the deliberate split for the student/admin credentials: the RUNTIME student's
 * email/password live in {@link ExecutionContext} (reach them via {@link #execution()}), while
 * this class's own {@code studentEmail}/{@code adminEmail} fields are throwaway inputs for building
 * a login request in the endpoint tests. They share a name but not a scope, and never collide
 * because provisioning code addresses the runtime pair through {@code execution()} explicitly.
 */
public class ScenarioContext {

    /** The suite-scoped runtime environment this scenario reads/enriches. */
    public ExecutionContext execution() {
        return ExecutionContext.get();
    }

    // ---- Per-scenario auth-request scratch (login-endpoint tests only) ----
    private String studentEmail;
    private String studentPassword;
    private String adminEmail;
    private String adminPassword;

    // ---- Per-scenario ephemera ----
    private Scenario cucumberScenario;
    private final java.util.List<String> environmentSetupReportLines = new java.util.ArrayList<>();
    private Response lastResponse;

    /** Free-form bag for ids created mid-scenario by endpoint tests (e.g. ProgramMasterCrud's own program id). */
    private final Map<String, Object> attributes = new HashMap<>();

    // ================= Per-scenario auth-request scratch =================

    public String getStudentEmail() {
        return studentEmail;
    }

    public void setStudentEmail(String studentEmail) {
        this.studentEmail = studentEmail;
    }

    public String getStudentPassword() {
        return studentPassword;
    }

    public void setStudentPassword(String studentPassword) {
        this.studentPassword = studentPassword;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    // ================= Admin session (delegated -> ExecutionContext) =================

    public String getAdminToken() {
        return execution().getAdminToken();
    }

    public void setAdminToken(String adminToken) {
        execution().setAdminToken(adminToken);
    }

    public String getAdminRefreshToken() {
        return execution().getAdminRefreshToken();
    }

    public void setAdminRefreshToken(String adminRefreshToken) {
        execution().setAdminRefreshToken(adminRefreshToken);
    }

    public String getAdminUserId() {
        return execution().getAdminUserId();
    }

    public void setAdminUserId(String adminUserId) {
        execution().setAdminUserId(adminUserId);
    }

    /** Organization.uuid from the admin login response - required as a path param on most admin org-scoped endpoints. */
    public String getAdminOrgId() {
        return execution().getAdminOrgId();
    }

    public void setAdminOrgId(String adminOrgId) {
        execution().setAdminOrgId(adminOrgId);
    }

    // ================= Per-scenario ephemera =================

    /** The current scenario's Cucumber {@link Scenario} handle, captured once in Hooks#beforeScenario - reporting only. */
    public Scenario getCucumberScenario() {
        return cucumberScenario;
    }

    public void setCucumberScenario(Scenario cucumberScenario) {
        this.cucumberScenario = cucumberScenario;
    }

    /** Appends one formatted line/block to this scenario's report accumulator - reporting only. */
    public void appendEnvironmentSetupReportLine(String line) {
        environmentSetupReportLines.add(line);
    }

    public List<String> getEnvironmentSetupReportLines() {
        return environmentSetupReportLines;
    }

    public Response getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(Response lastResponse) {
        this.lastResponse = lastResponse;
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    // ================= Environment entities (delegated -> ExecutionContext) =================

    public String getProgramId() {
        return execution().getProgramId();
    }

    public void setProgramId(String programId) {
        execution().setProgramId(programId);
    }

    public String getProgramName() {
        return execution().getProgramName();
    }

    public void setProgramName(String programName) {
        execution().setProgramName(programName);
    }

    public String getCohortId() {
        return execution().getCohortId();
    }

    public void setCohortId(String cohortId) {
        execution().setCohortId(cohortId);
    }

    public String getCohortName() {
        return execution().getCohortName();
    }

    public void setCohortName(String cohortName) {
        execution().setCohortName(cohortName);
    }

    public String getProgramTermId() {
        return execution().getProgramTermId();
    }

    public void setProgramTermId(String programTermId) {
        execution().setProgramTermId(programTermId);
    }

    public String getProgramTermName() {
        return execution().getProgramTermName();
    }

    public void setProgramTermName(String programTermName) {
        execution().setProgramTermName(programTermName);
    }

    public String getProgramTermStartDate() {
        return execution().getProgramTermStartDate();
    }

    public void setProgramTermStartDate(String programTermStartDate) {
        execution().setProgramTermStartDate(programTermStartDate);
    }

    public String getProgramTermEndDate() {
        return execution().getProgramTermEndDate();
    }

    public void setProgramTermEndDate(String programTermEndDate) {
        execution().setProgramTermEndDate(programTermEndDate);
    }

    public String getSectionId() {
        return execution().getSectionId();
    }

    public void setSectionId(String sectionId) {
        execution().setSectionId(sectionId);
    }

    public String getCourseId() {
        return execution().getCourseId();
    }

    public void setCourseId(String courseId) {
        execution().setCourseId(courseId);
    }

    public String getCourseName() {
        return execution().getCourseName();
    }

    public void setCourseName(String courseName) {
        execution().setCourseName(courseName);
    }

    public String getCourseCode() {
        return execution().getCourseCode();
    }

    public void setCourseCode(String courseCode) {
        execution().setCourseCode(courseCode);
    }

    public int getCourseCredit() {
        return execution().getCourseCredit();
    }

    public void setCourseCredit(int courseCredit) {
        execution().setCourseCredit(courseCredit);
    }

    public String getDepartmentId() {
        return execution().getDepartmentId();
    }

    public void setDepartmentId(String departmentId) {
        execution().setDepartmentId(departmentId);
    }

    public String getCourseTypeId() {
        return execution().getCourseTypeId();
    }

    public void setCourseTypeId(String courseTypeId) {
        execution().setCourseTypeId(courseTypeId);
    }

    public String getClassTypeId() {
        return execution().getClassTypeId();
    }

    public void setClassTypeId(String classTypeId) {
        execution().setClassTypeId(classTypeId);
    }

    public String getCourseModeId() {
        return execution().getCourseModeId();
    }

    public void setCourseModeId(String courseModeId) {
        execution().setCourseModeId(courseModeId);
    }

    public String getAttendanceType() {
        return execution().getAttendanceType();
    }

    public void setAttendanceType(String attendanceType) {
        execution().setAttendanceType(attendanceType);
    }

    public String getCoursePurpose() {
        return execution().getCoursePurpose();
    }

    public void setCoursePurpose(String coursePurpose) {
        execution().setCoursePurpose(coursePurpose);
    }

    public String getCourseTagId() {
        return execution().getCourseTagId();
    }

    public void setCourseTagId(String courseTagId) {
        execution().setCourseTagId(courseTagId);
    }

    public String getSectionCourseId() {
        return execution().getSectionCourseId();
    }

    public void setSectionCourseId(String sectionCourseId) {
        execution().setSectionCourseId(sectionCourseId);
    }

    public String getStudentEnrollmentUuid() {
        return execution().getStudentEnrollmentUuid();
    }

    public void setStudentEnrollmentUuid(String studentEnrollmentUuid) {
        execution().setStudentEnrollmentUuid(studentEnrollmentUuid);
    }

    public String getEnrollmentId() {
        return execution().getEnrollmentId();
    }

    public void setEnrollmentId(String enrollmentId) {
        execution().setEnrollmentId(enrollmentId);
    }

    public String getTermCourseId() {
        return execution().getTermCourseId();
    }

    public void setTermCourseId(String termCourseId) {
        execution().setTermCourseId(termCourseId);
    }

    // ================= Capstone journey (delegated -> ExecutionContext) =================

    public String getCapstoneSectionId() {
        return execution().getCapstoneSectionId();
    }

    public void setCapstoneSectionId(String capstoneSectionId) {
        execution().setCapstoneSectionId(capstoneSectionId);
    }

    public String getCapstoneSectionName() {
        return execution().getCapstoneSectionName();
    }

    public void setCapstoneSectionName(String capstoneSectionName) {
        execution().setCapstoneSectionName(capstoneSectionName);
    }

    public String getCapstoneTermCourseId() {
        return execution().getCapstoneTermCourseId();
    }

    public void setCapstoneTermCourseId(String capstoneTermCourseId) {
        execution().setCapstoneTermCourseId(capstoneTermCourseId);
    }

    public String getCapstoneGamificationConfigUuid() {
        return execution().getCapstoneGamificationConfigUuid();
    }

    public void setCapstoneGamificationConfigUuid(String capstoneGamificationConfigUuid) {
        execution().setCapstoneGamificationConfigUuid(capstoneGamificationConfigUuid);
    }

    public Integer getCapstoneGamificationConfigNumericId() {
        return execution().getCapstoneGamificationConfigNumericId();
    }

    public void setCapstoneGamificationConfigNumericId(Integer capstoneGamificationConfigNumericId) {
        execution().setCapstoneGamificationConfigNumericId(capstoneGamificationConfigNumericId);
    }

    public String getCapstoneRewardUuid() {
        return execution().getCapstoneRewardUuid();
    }

    public void setCapstoneRewardUuid(String capstoneRewardUuid) {
        execution().setCapstoneRewardUuid(capstoneRewardUuid);
    }

    /** The S3 team identity, captured once at creation and read (never re-derived) by every later step. */
    public String getCapstoneTeamUuid() {
        return execution().getCapstoneTeamUuid();
    }

    public void setCapstoneTeamUuid(String capstoneTeamUuid) {
        execution().setCapstoneTeamUuid(capstoneTeamUuid);
    }

    public String getCapstoneTeamName() {
        return execution().getCapstoneTeamName();
    }

    public void setCapstoneTeamName(String capstoneTeamName) {
        execution().setCapstoneTeamName(capstoneTeamName);
    }

    // ---- Assignment journey outputs (delegated -> ExecutionContext) ----

    public String getAssignmentUuid() {
        return execution().getAssignmentUuid();
    }

    public void setAssignmentUuid(String assignmentUuid) {
        execution().setAssignmentUuid(assignmentUuid);
    }

    public String getAssignmentName() {
        return execution().getAssignmentName();
    }

    public void setAssignmentName(String assignmentName) {
        execution().setAssignmentName(assignmentName);
    }

    public String getAssignmentPublishStatus() {
        return execution().getAssignmentPublishStatus();
    }

    public void setAssignmentPublishStatus(String assignmentPublishStatus) {
        execution().setAssignmentPublishStatus(assignmentPublishStatus);
    }

    public String getAssignmentDueDate() {
        return execution().getAssignmentDueDate();
    }

    public void setAssignmentDueDate(String assignmentDueDate) {
        execution().setAssignmentDueDate(assignmentDueDate);
    }

    public String getAssignmentId() {
        return execution().getAssignmentId();
    }

    public void setAssignmentId(String assignmentId) {
        execution().setAssignmentId(assignmentId);
    }

    public String getAssignmentResourceId() {
        return execution().getAssignmentResourceId();
    }

    public void setAssignmentResourceId(String assignmentResourceId) {
        execution().setAssignmentResourceId(assignmentResourceId);
    }

    // ================= Assignment submission (delegated -> ExecutionContext) =================

    public String getSubmissionUuid() {
        return execution().getSubmissionUuid();
    }

    public void setSubmissionUuid(String submissionUuid) {
        execution().setSubmissionUuid(submissionUuid);
    }

    public String getSubmissionStatus() {
        return execution().getSubmissionStatus();
    }

    public void setSubmissionStatus(String submissionStatus) {
        execution().setSubmissionStatus(submissionStatus);
    }

    public String getSubmissionTimestamp() {
        return execution().getSubmissionTimestamp();
    }

    public void setSubmissionTimestamp(String submissionTimestamp) {
        execution().setSubmissionTimestamp(submissionTimestamp);
    }

    // ================= Session journey (delegated -> ExecutionContext) =================

    public String getSessionUuid() {
        return execution().getSessionUuid();
    }

    public void setSessionUuid(String sessionUuid) {
        execution().setSessionUuid(sessionUuid);
    }

    public String getSessionName() {
        return execution().getSessionName();
    }

    public void setSessionName(String sessionName) {
        execution().setSessionName(sessionName);
    }

    public String getSessionMode() {
        return execution().getSessionMode();
    }

    public void setSessionMode(String sessionMode) {
        execution().setSessionMode(sessionMode);
    }

    public String getLocationUuid() {
        return execution().getLocationUuid();
    }

    public void setLocationUuid(String locationUuid) {
        execution().setLocationUuid(locationUuid);
    }

    public String getLocationName() {
        return execution().getLocationName();
    }

    public void setLocationName(String locationName) {
        execution().setLocationName(locationName);
    }

    // ================= Attendance journey (delegated -> ExecutionContext) =================

    public String getAttendanceRecordUuid() {
        return execution().getAttendanceRecordUuid();
    }

    public void setAttendanceRecordUuid(String attendanceRecordUuid) {
        execution().setAttendanceRecordUuid(attendanceRecordUuid);
    }

    public String getAttendanceStatus() {
        return execution().getAttendanceStatus();
    }

    public void setAttendanceStatus(String attendanceStatus) {
        execution().setAttendanceStatus(attendanceStatus);
    }

    public String getStudentNumericId() {
        return execution().getStudentNumericId();
    }

    public void setStudentNumericId(String studentNumericId) {
        execution().setStudentNumericId(studentNumericId);
    }

    // ================= Draft assignment (delegated -> ExecutionContext) =================

    public String getDraftAssignmentUuid() {
        return execution().getDraftAssignmentUuid();
    }

    public void setDraftAssignmentUuid(String draftAssignmentUuid) {
        execution().setDraftAssignmentUuid(draftAssignmentUuid);
    }

    public String getDraftAssignmentName() {
        return execution().getDraftAssignmentName();
    }

    public void setDraftAssignmentName(String draftAssignmentName) {
        execution().setDraftAssignmentName(draftAssignmentName);
    }

    public String getDraftAssignmentPublishStatus() {
        return execution().getDraftAssignmentPublishStatus();
    }

    public void setDraftAssignmentPublishStatus(String draftAssignmentPublishStatus) {
        execution().setDraftAssignmentPublishStatus(draftAssignmentPublishStatus);
    }

    // ================= Multi-assignment (A/B/C) isolation scenario (delegated) =================

    public List<String> getMultiAssignmentUuids() {
        return execution().getMultiAssignmentUuids();
    }

    public void setMultiAssignmentUuids(List<String> multiAssignmentUuids) {
        execution().setMultiAssignmentUuids(multiAssignmentUuids);
    }

    public List<String> getMultiAssignmentNames() {
        return execution().getMultiAssignmentNames();
    }

    public void setMultiAssignmentNames(List<String> multiAssignmentNames) {
        execution().setMultiAssignmentNames(multiAssignmentNames);
    }

    // ================= Admin-edited assignment content (delegated) =================

    public String getEditedAssignmentDetails() {
        return execution().getEditedAssignmentDetails();
    }

    public void setEditedAssignmentDetails(String editedAssignmentDetails) {
        execution().setEditedAssignmentDetails(editedAssignmentDetails);
    }

    public String getEditedAssignmentMarks() {
        return execution().getEditedAssignmentMarks();
    }

    public void setEditedAssignmentMarks(String editedAssignmentMarks) {
        execution().setEditedAssignmentMarks(editedAssignmentMarks);
    }

    public String getEditedAssignmentDueDate() {
        return execution().getEditedAssignmentDueDate();
    }

    public void setEditedAssignmentDueDate(String editedAssignmentDueDate) {
        execution().setEditedAssignmentDueDate(editedAssignmentDueDate);
    }

    // ================= Runtime student session (delegated -> ExecutionContext) =================
    // NOTE: the runtime student's OWN email/password are the ExecutionContext pair (reach them via
    // execution().getStudentEmail()/getStudentPassword()); this class's studentEmail/studentPassword
    // above are the separate per-scenario auth-request scratch used by the login-endpoint tests.

    public String getStudentUuid() {
        return execution().getStudentUuid();
    }

    public void setStudentUuid(String studentUuid) {
        execution().setStudentUuid(studentUuid);
    }

    public String getStudentToken() {
        return execution().getStudentToken();
    }

    public void setStudentToken(String studentToken) {
        execution().setStudentToken(studentToken);
    }

    public String getStudentRefreshToken() {
        return execution().getStudentRefreshToken();
    }

    public void setStudentRefreshToken(String studentRefreshToken) {
        execution().setStudentRefreshToken(studentRefreshToken);
    }

    public String getStudentUserId() {
        return execution().getStudentUserId();
    }

    public void setStudentUserId(String studentUserId) {
        execution().setStudentUserId(studentUserId);
    }

    public String getStudentProgramId() {
        return execution().getStudentProgramId();
    }

    public void setStudentProgramId(String studentProgramId) {
        execution().setStudentProgramId(studentProgramId);
    }

    public String getStudentCohortId() {
        return execution().getStudentCohortId();
    }

    public void setStudentCohortId(String studentCohortId) {
        execution().setStudentCohortId(studentCohortId);
    }

    // ================= Concern journey (delegated -> ExecutionContext) =================

    public String getConcernCategoryName() {
        return execution().getConcernCategoryName();
    }

    public void setConcernCategoryName(String concernCategoryName) {
        execution().setConcernCategoryName(concernCategoryName);
    }

    public String getConcernSubCategoryName() {
        return execution().getConcernSubCategoryName();
    }

    public void setConcernSubCategoryName(String concernSubCategoryName) {
        execution().setConcernSubCategoryName(concernSubCategoryName);
    }

    public String getConcernSubCategoryUuid() {
        return execution().getConcernSubCategoryUuid();
    }

    public void setConcernSubCategoryUuid(String concernSubCategoryUuid) {
        execution().setConcernSubCategoryUuid(concernSubCategoryUuid);
    }

    public boolean isConcernEscalationConfigured() {
        return execution().isConcernEscalationConfigured();
    }

    public void setConcernEscalationConfigured(boolean concernEscalationConfigured) {
        execution().setConcernEscalationConfigured(concernEscalationConfigured);
    }

    // ================= Cleanup registry (delegated -> ExecutionContext suite registry) =================

    public void registerCreated(ResourceType type, String id) {
        execution().registerCreated(type, id);
    }

    public void registerCreated(ResourceType type, String id, Map<String, String> deletionContext) {
        execution().registerCreated(type, id, deletionContext);
    }

    /** The suite-wide created-resource registry (creation order); Cleanup walks it in reverse. */
    public List<CreatedResource> getCreatedResources() {
        return execution().getCreatedResources();
    }
}
