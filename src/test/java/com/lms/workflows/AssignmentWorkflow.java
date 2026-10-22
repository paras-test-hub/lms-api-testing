package com.lms.workflows;

import com.lms.cleanup.ResourceType;
import com.lms.clients.AssignmentClient;
import com.lms.clients.admin.CohortClient;
import com.lms.clients.admin.ReferenceDataClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.AssignmentJourneyReport;
import com.lms.utils.DateUtils;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Assignment journey - the first business journey to run
 * against the suite-scoped runtime environment. It consumes the environment
 * {@link com.lms.context.ExecutionContext EnvironmentSetup} already provisioned (Program -> Cohort ->
 * Program Term -> Section -> Course, plus the authenticated runtime student) and drives a complete
 * assignment lifecycle ON the runtime course/student - it never re-provisions any of that.
 *
 * <p>Phase 1 (admin create + verify): request/response shapes verified from a real HAR capture
 * (hawkeye-apis_2026-07-21_22-22-20.har), re-confirmed field-for-field against a later capture
 * (hawkeye-apis_2026-07-23_09-54-52.har) - a single {@code createAssignmentDetails} POST (no
 * multi-step PATCH), preceded by the {@code getAssignmentResources} lookup that supplies the required
 * {@code assignmentResourceId}, and followed by a {@code getAllSessionAssignmentDetails} read-back.
 *
 * <p>Phase 2 (admin cross-verify + student retrieve/submit): verified from
 * hawkeye-apis_2026-07-23_09-54-52.har - a direct {@code GET termCourse/{id}} for the full
 * Program/Cohort/ProgramTerm/Course relation chain, then the student's own
 * {@code getAllStudentAssignments} list, {@code assignmentUpload} detail GET, and a text/link-only
 * {@code assignmentUpload} submission POST (the captured submission's file-upload fields depend on a
 * genuine third-party Filestack integration, deliberately not replicated - see
 * {@link AssignmentClient#submitAssignment}).
 *
 * <p>Each business action is its own method with its own reporting checkpoint and assertions
 * (mirrored 1:1 by a Gherkin step in AssignmentJourney.feature) rather than one large method - so a
 * failure always points at the exact business action that failed, not a multi-step black box.
 *
 * <p>Later stages (admin evaluates/grades, student retrieves grade/feedback) will be appended the
 * same way, each added only once its own HAR is available - never guessed.
 */
public class AssignmentWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AssignmentWorkflow.class);

    /** The assignment "resource" type for a standard individual assignment (looked up by name, never hardcoded). */
    private static final String ASSIGNMENT_RESOURCE_NAME = "Create Assignments";

    private static final String TIMEZONE = "Asia/Kolkata";

    private final CohortClient cohortClient;
    private final ReferenceDataClient referenceDataClient;
    private final AssignmentClient assignmentClient;

    public AssignmentWorkflow() {
        this.cohortClient = new CohortClient();
        this.referenceDataClient = new ReferenceDataClient();
        this.assignmentClient = new AssignmentClient();
    }

    /**
     * Creates and publishes an assignment on the runtime course, verifies it via read-back, stores
     * its identity in {@link ScenarioContext}/ExecutionContext for later journey stages, and registers
     * it for suite cleanup. Fails loudly (not a skip) if the environment is not ready, if the runtime
     * course cannot be located, or if the "Create Assignments" resource type is unavailable.
     */
    public void createAssignmentOnRuntimeCourse(ScenarioContext context) {
        context.execution().requireEnvironmentReady();

        String orgId = context.getAdminOrgId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();
        String programTermId = context.getProgramTermId();
        String sectionId = context.getSectionId();
        String courseId = context.getCourseId();

        assertThat(orgId).as("adminOrgId must be set by admin login").isNotBlank();
        assertThat(createdBy).as("adminUserId must be set by admin login").isNotBlank();
        assertThat(programTermId).as("programTermId must be set by Environment Setup").isNotBlank();
        assertThat(sectionId).as("sectionId must be set by Environment Setup").isNotBlank();
        assertThat(courseId).as("courseId must be set by Environment Setup").isNotBlank();

        String termCourseId = locateRuntimeTermCourse(context, programTermId, sectionId, courseId, adminToken);
        String assignmentResourceId = resolveAssignmentResourceId(orgId, adminToken);

        // ---- Runtime assignment data (unique per run; mirrors the real request's field set) ----
        String name = TestDataFactory.assignmentName();
        String details = TestDataFactory.assignmentDescriptionHtml();
        String totalPoints = TestDataFactory.assignmentTotalPoints();
        String weightage = TestDataFactory.assignmentWeightagePercentage();
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        String submissionDate = LocalDate.now().plusDays(2).format(iso);
        String marksReleaseDate = LocalDate.now().plusDays(9).format(iso);
        boolean isDraft = false; // publish = true

        log.info("==============================\nCreating Assignment\nCourse: {}\nTermCourse: {}\nSection: {}\n"
                        + "Name: {}\nPoints: {}\nWeightage: {}\nDue: {}\nPublish: {}\n==============================",
                context.getCourseName(), termCourseId, sectionId, name, totalPoints, weightage, submissionDate, !isDraft);

        Map<String, Object> payload = buildCreatePayload(name, details, assignmentResourceId, submissionDate,
                marksReleaseDate, weightage, totalPoints, isDraft, createdBy);

        Response createResponse = assignmentClient.createAssignment(termCourseId, sectionId, payload, adminToken);
        assertThat(createResponse.statusCode())
                .as("assignment creation must succeed. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        String assignmentUuid = createResponse.jsonPath().getString("Data.uuid");
        assertThat(assignmentUuid)
                .as("created assignment must expose Data.uuid. Response: " + createResponse.getBody().asString())
                .isNotBlank();

        // Store for later journey stages (student retrieve/submit, admin grade) + register for cleanup.
        String publishStatus = isDraft ? "Draft" : "Published";
        context.setAssignmentUuid(assignmentUuid);
        context.setAssignmentName(name);
        context.setAssignmentPublishStatus(publishStatus);
        context.setAssignmentDueDate(submissionDate);
        context.setAssignmentResourceId(assignmentResourceId);

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("termCourseId", termCourseId);
        deletionContext.put("sectionId", sectionId);
        context.registerCreated(ResourceType.ASSIGNMENT, assignmentUuid, deletionContext);

        AssignmentJourneyReport.checkpoint(context, "Assignment Created",
                "Name: " + name, "UUID: " + assignmentUuid, "Status: " + publishStatus);

        verifyAssignment(context, termCourseId, sectionId, adminToken, assignmentUuid, name, details,
                submissionDate, marksReleaseDate, totalPoints, weightage, isDraft, assignmentResourceId);

        AssignmentJourneyReport.checkpoint(context, "Assignment Stored",
                "Stored assignment UUID + name + due date + publish status in ExecutionContext");
        AssignmentJourneyReport.checkpoint(context, "Assignment Registered for Cleanup",
                "ResourceType.ASSIGNMENT [" + assignmentUuid + "] (termCourseId + sectionId captured)");

        log.info("==============================\nCreating Assignment\nName: {}\nUUID: {}\nResult: SUCCESS (published + verified)\n==============================",
                name, assignmentUuid);

        // NOTE: report is NOT published here - the Assignment journey continues (cross-verify, then
        // the student stages), so publish() is deferred to the LAST stage (verifyStudentSubmission),
        // matching how EnvironmentSetupReport is only published after the final Environment Setup step.
    }

    /**
     * Locates the termCourse id for the runtime course by reading the section detail back (the same
     * GET the admin UI fires) and matching the Data.data[] entry whose CourseMaster.uuid equals the
     * runtime courseId. Its {@code uuid} is the termCourse mapping id the assignment endpoints are
     * keyed by. Fails loudly if the runtime course is not attached to the section. Stores the id on
     * the context and cross-checks it against the sectionCourseId Environment Setup captured.
     */
    @SuppressWarnings("unchecked")
    private String locateRuntimeTermCourse(ScenarioContext context, String programTermId, String sectionId,
                                           String courseId, String adminToken) {
        Response response = cohortClient.getSectionDetail(programTermId, sectionId, adminToken);
        assertThat(response.statusCode())
                .as("section-detail GET must succeed to locate the runtime course. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> attachedCourses = response.jsonPath().getList("Data.data");
        assertThat(attachedCourses)
                .as("section detail must expose its attached-course list (Data.data)")
                .isNotNull();

        String termCourseId = null;
        for (Map<String, Object> entry : attachedCourses) {
            Object courseMaster = entry.get("CourseMaster");
            if (courseMaster instanceof Map && courseId.equals(((Map<?, ?>) courseMaster).get("uuid"))) {
                Object uuid = entry.get("uuid");
                termCourseId = uuid != null ? uuid.toString() : null;
                break;
            }
        }
        assertThat(termCourseId)
                .as("the runtime course %s must be attached to section %s (found via section-detail GET) so an "
                        + "assignment can be created on it", courseId, sectionId)
                .isNotBlank();

        String setupSectionCourseId = context.getSectionCourseId();
        if (setupSectionCourseId != null && !setupSectionCourseId.equals(termCourseId)) {
            log.warn("Located termCourseId {} differs from the sectionCourseId {} captured during Environment "
                    + "Setup - using the freshly-located id.", termCourseId, setupSectionCourseId);
        }

        context.setTermCourseId(termCourseId);
        AssignmentJourneyReport.checkpoint(context, "Runtime Course Located",
                "Course: " + context.getCourseName(), "TermCourse UUID: " + termCourseId,
                "Verified attached via section-detail GET");
        log.info("Located runtime termCourse {} for course {} in section {}", termCourseId, courseId, sectionId);
        return termCourseId;
    }

    /**
     * Resolves the required {@code assignmentResourceId} by looking up the org's assignment resource
     * types and selecting "Create Assignments" (a standard individual assignment) by name - never a
     * hardcoded uuid. Fails loudly if the lookup is empty or that type is absent.
     */
    private String resolveAssignmentResourceId(String orgId, String adminToken) {
        Response response = referenceDataClient.listAssignmentResources(orgId, adminToken);
        assertThat(response.statusCode())
                .as("getAssignmentResources lookup must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> resources = response.jsonPath().getList("Data");
        assertThat(resources)
                .as("getAssignmentResources must return a Data list")
                .isNotNull().isNotEmpty();

        for (Map<String, Object> resource : resources) {
            if (ASSIGNMENT_RESOURCE_NAME.equals(resource.get("name"))) {
                Object uuid = resource.get("uuid");
                assertThat(uuid).as("'%s' assignment resource must expose a uuid", ASSIGNMENT_RESOURCE_NAME).isNotNull();
                return uuid.toString();
            }
        }
        throw new IllegalStateException("No '" + ASSIGNMENT_RESOURCE_NAME + "' assignment resource found via "
                + "GET .../getAssignmentResources - cannot create an assignment. Available: " + resources);
    }

    /** Builds the create payload, mirroring the real frontend request (runtime values + fixed defaults). */
    private Map<String, Object> buildCreatePayload(String name, String details, String assignmentResourceId,
                                                   String submissionDate, String marksReleaseDate, String weightage,
                                                   String totalPoints, boolean isDraft, String createdBy) {
        Map<String, Object> proctoringOptions = new HashMap<>();
        proctoringOptions.put("tabSwitching", false);
        proctoringOptions.put("fullScreen", false);
        proctoringOptions.put("rightClick", false);
        proctoringOptions.put("copyPaste", false);
        proctoringOptions.put("videoProctoring", false);

        Map<String, Object> submissionFormat = new HashMap<>();
        submissionFormat.put("allowedFileTypes", Arrays.asList("pdf", "excel", "doc", "zip", "ppt", "video", "image"));

        Map<String, Object> formatType = new HashMap<>();
        formatType.put("types", Arrays.asList("desc", "file", "link"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("sessionId", "");
        payload.put("assignmentDetails", details);
        payload.put("assignmentResourceId", assignmentResourceId);
        payload.put("submissionDate", submissionDate);
        payload.put("marksReleaseDate", marksReleaseDate);
        payload.put("weightagePercentage", weightage);
        payload.put("totalPoints", totalPoints);
        payload.put("isGroup", false);
        payload.put("outWeightagePercentage", "");
        payload.put("quizStartTime", "");
        payload.put("quizEndTime", "");
        payload.put("deadLineToggle", false);
        payload.put("milestoneApproval", false);
        payload.put("isCapstoneMilestone", false);
        payload.put("autoQuizClose", false);
        payload.put("propRofsPassword", "");
        payload.put("propRofsUrl", "");
        payload.put("isDraft", isDraft);
        payload.put("uploadResourceJson", Collections.emptyList());
        payload.put("isGroupMemberSubmit", true);
        payload.put("proctoringOptions", proctoringOptions);
        payload.put("enableAiEvaluation", false);
        payload.put("enableAiEvaluationDetail", "");
        payload.put("assignmentSubmissionFormat", submissionFormat);
        payload.put("assignmentFormatType", formatType);
        payload.put("isProctoringEnabled", false);
        payload.put("warningCount", 0);
        payload.put("isAnonymous", false);
        payload.put("submissionTime", null);
        payload.put("groupSlug", "default");
        payload.put("timezone", TIMEZONE);
        payload.put("quizBeginTime", null);
        payload.put("quizFinishTime", null);
        payload.put("createdBy", createdBy);
        return payload;
    }

    /**
     * Read-back verification via getAllSessionAssignmentDetails - never trusts the POST alone. Finds
     * the just-created assignment by uuid in the runtime termCourse/section's list (which proves it
     * belongs to the runtime course, since the list is scoped to that termCourse+section) and asserts
     * every business field round-tripped: name, details, publish status, due/marks-release dates,
     * points, weightage, and the linked assignment-resource.
     */
    @SuppressWarnings("unchecked")
    private void verifyAssignment(ScenarioContext context, String termCourseId, String sectionId, String adminToken,
                                  String assignmentUuid, String expectedName, String expectedDetails,
                                  String expectedSubmissionDate, String expectedMarksReleaseDate,
                                  String expectedTotalPoints, String expectedWeightage, boolean expectedIsDraft,
                                  String expectedResourceId) {
        Response response = assignmentClient.getAssignments(termCourseId, sectionId, adminToken);
        assertThat(response.statusCode())
                .as("assignment read-back GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("Data");
        assertThat(assignments).as("assignment list (Data) must be present for verification").isNotNull();

        Map<String, Object> mine = null;
        for (Map<String, Object> entry : assignments) {
            if (assignmentUuid.equals(entry.get("uuid"))) {
                mine = entry;
                break;
            }
        }
        assertThat(mine)
                .as("the created assignment %s must appear in the runtime course's assignment list "
                        + "(termCourse %s / section %s) - proving it belongs to the runtime course",
                        assignmentUuid, termCourseId, sectionId)
                .isNotNull();

        assertThat(mine.get("name")).as("assignment name must round-trip").isEqualTo(expectedName);
        assertThat(mine.get("assignmentDetails")).as("assignment details must round-trip").isEqualTo(expectedDetails);
        assertThat(mine.get("isDraft")).as("assignment publish status (isDraft) must round-trip").isEqualTo(expectedIsDraft);
        assertThat(String.valueOf(mine.get("totalPoints"))).as("assignment totalPoints must round-trip").isEqualTo(expectedTotalPoints);
        assertThat(String.valueOf(mine.get("weightagePercentage"))).as("assignment weightagePercentage must round-trip").isEqualTo(expectedWeightage);

        assertThat(DateUtils.toCalendarDate(mine.get("submissionDate")))
                .as("assignment submissionDate (due date) must round-trip")
                .isEqualTo(LocalDate.parse(expectedSubmissionDate));
        assertThat(DateUtils.toCalendarDate(mine.get("marksReleaseDate")))
                .as("assignment marksReleaseDate must round-trip")
                .isEqualTo(LocalDate.parse(expectedMarksReleaseDate));

        Object assignmentResource = mine.get("AssignmentResource");
        assertThat(assignmentResource).as("verified assignment must expose its AssignmentResource").isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) assignmentResource).get("uuid"))
                .as("assignment must be linked to the resolved '%s' resource", ASSIGNMENT_RESOURCE_NAME)
                .isEqualTo(expectedResourceId);

        AssignmentJourneyReport.checkpoint(context, "Assignment Verified (Admin)",
                "Read back via getAllSessionAssignmentDetails GET",
                "Belongs to runtime course (termCourse " + termCourseId + ")",
                "Name / details / dates / points / weightage / resource all match");
        log.info("Assignment {} verified on runtime termCourse {}", assignmentUuid, termCourseId);
    }

    // ================= Phase 2: admin cross-verification + student retrieve/submit =================

    /**
     * Cross-verifies that the assignment's termCourse genuinely belongs to the runtime
     * Program/Cohort/ProgramTerm/Course chain - not merely "some" termCourse. A single
     * {@code GET termCourse/{id}} call (verified via HAR hawkeye-apis_2026-07-23_09-54-52.har)
     * returns the full relation chain, so this needs no extra endpoint. Fails loudly on any mismatch.
     */
    public void verifyRuntimeRelationChain(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String adminToken = context.getAdminToken();

        assertThat(termCourseId).as("termCourseId must be set by the assignment-create stage").isNotBlank();

        Response response = cohortClient.getTermCourseDetail(termCourseId, adminToken);
        assertThat(response.statusCode())
                .as("termCourse detail GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        String courseMasterUuid = response.jsonPath().getString("Data.termCourse.CourseMaster.uuid");
        String programTermUuid = response.jsonPath().getString("Data.termCourse.ProgramTerm.uuid");
        String cohortUuid = response.jsonPath().getString("Data.termCourse.ProgramTerm.Program.uuid");
        String programUuid = response.jsonPath().getString("Data.termCourse.ProgramTerm.Program.ProgramMaster.uuid");

        assertThat(courseMasterUuid).as("termCourse's CourseMaster must match the runtime course").isEqualTo(context.getCourseId());
        assertThat(programTermUuid).as("termCourse's ProgramTerm must match the runtime program term").isEqualTo(context.getProgramTermId());
        assertThat(cohortUuid).as("termCourse's Program (cohort) must match the runtime cohort").isEqualTo(context.getCohortId());
        assertThat(programUuid).as("termCourse's ProgramMaster must match the runtime program").isEqualTo(context.getProgramId());

        AssignmentJourneyReport.checkpoint(context, "Assignment Cross-Verified",
                "Program -> Cohort -> Program Term -> Course chain confirmed via GET termCourse/" + termCourseId,
                "Course: " + courseMasterUuid, "Program Term: " + programTermUuid,
                "Cohort: " + cohortUuid, "Program: " + programUuid);
        log.info("Assignment {} cross-verified against runtime Program/Cohort/ProgramTerm/Course chain", context.getAssignmentUuid());
    }

    /**
     * Student retrieves their assignment list for the runtime program term and locates the runtime
     * assignment by UUID (never by name - the list spans every course in the program term, including
     * unrelated ones). Verified via {@code GET .../getAllStudentAssignments}. Confirms the assignment
     * is attached to the runtime termCourse and captures its numeric internal id (only exposed here,
     * not by the admin create/verify responses). Fails loudly if the runtime assignment is absent from
     * the student's own list.
     */
    @SuppressWarnings("unchecked")
    public void studentRetrievesAssignmentList(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String studentToken = context.getStudentToken();
        String assignmentUuid = context.getAssignmentUuid();

        assertThat(studentToken).as("studentToken must be set by Environment Setup").isNotBlank();
        assertThat(assignmentUuid).as("assignmentUuid must be set by the assignment-create stage").isNotBlank();

        Response response = assignmentClient.getAllStudentAssignments(programTermId, studentToken);
        assertThat(response.statusCode())
                .as("student assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("data.assignment");
        assertThat(assignments).as("student assignment list (data.assignment) must be present").isNotNull();

        Map<String, Object> mine = assignments.stream()
                .filter(a -> assignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(mine)
                .as("the runtime assignment %s (created by the runtime admin) must appear in the runtime "
                        + "student's own assignment list for program term %s - located by UUID, never by name",
                        assignmentUuid, programTermId)
                .isNotNull();

        Object termCourse = mine.get("TermCourse");
        assertThat(termCourse).as("student-visible assignment must expose its TermCourse").isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) termCourse).get("uuid"))
                .as("student-visible assignment must belong to the runtime termCourse")
                .isEqualTo(context.getTermCourseId());

        Object numericId = mine.get("id");
        assertThat(numericId).as("student-visible assignment must expose its numeric id").isNotNull();
        context.setAssignmentId(String.valueOf(numericId));

        AssignmentJourneyReport.checkpoint(context, "Assignment Visible to Runtime Student",
                "Located by UUID in the student's own assignment list (never by name)",
                "Belongs to runtime termCourse " + context.getTermCourseId(),
                "Internal assignment id: " + numericId);
        log.info("Runtime assignment {} confirmed visible to runtime student in program term {}", assignmentUuid, programTermId);
    }

    /**
     * Student retrieves the assignment's own detail view via {@code GET assignmentUpload/{uuid}} and
     * cross-verifies it against the runtime assignment: identity (uuid/name), the TermCourse/ProgramTerm
     * relation (proving it's the runtime course, not merely the same uuid coincidentally), publish
     * status, and due date. Descriptive fields the admin-side verify already exact-matched against what
     * was sent (details/points/weightage/format) are asserted present here too - proving they round-trip
     * to the student-facing endpoint - without a second independently-stored expected value to diff
     * against (ExecutionContext intentionally does not duplicate that raw data across journey stages).
     */
    @SuppressWarnings("unchecked")
    public void studentRetrievesAssignmentDetails(ScenarioContext context) {
        String studentToken = context.getStudentToken();
        String assignmentUuid = context.getAssignmentUuid();

        Response response = assignmentClient.getAssignmentDetail(assignmentUuid, studentToken);
        assertThat(response.statusCode())
                .as("student assignment-detail GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> detail = response.jsonPath().getMap("data.AssignmentDetail");
        assertThat(detail).as("response must expose data.AssignmentDetail").isNotNull();

        assertThat(detail.get("uuid")).as("assignment detail uuid must match").isEqualTo(assignmentUuid);
        assertThat(detail.get("name")).as("assignment detail name (title) must match").isEqualTo(context.getAssignmentName());
        assertThat(DateUtils.toCalendarDate(detail.get("submissionDate")))
                .as("assignment detail due date must match")
                .isEqualTo(LocalDate.parse(context.getAssignmentDueDate()));
        boolean expectedIsDraft = !"Published".equals(context.getAssignmentPublishStatus());
        assertThat(detail.get("isDraft")).as("assignment detail publish status must match").isEqualTo(expectedIsDraft);

        // Descriptive fields round-trip presence (already exact-matched against the sent payload on
        // the admin side; here we only confirm the student-facing endpoint exposes them too).
        assertThat(detail.get("assignmentDetails")).as("assignment detail description must be present").isNotNull();
        assertThat(detail.get("totalPoints")).as("assignment detail max marks must be present").isNotNull();
        assertThat(detail.get("assignmentFormatType")).as("assignment detail submission type must be present").isNotNull();

        Object termCourse = detail.get("TermCourse");
        assertThat(termCourse).as("assignment detail must expose its TermCourse").isInstanceOf(Map.class);
        Map<String, Object> termCourseMap = (Map<String, Object>) termCourse;
        assertThat(termCourseMap.get("uuid")).as("assignment detail must belong to the runtime termCourse").isEqualTo(context.getTermCourseId());
        Object programTerm = termCourseMap.get("ProgramTerm");
        assertThat(programTerm).as("assignment detail's TermCourse must expose its ProgramTerm").isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) programTerm).get("uuid"))
                .as("assignment detail must belong to the runtime program term")
                .isEqualTo(context.getProgramTermId());

        AssignmentJourneyReport.checkpoint(context, "Assignment Details Verified",
                "Read back via GET assignmentUpload/" + assignmentUuid + " (student session)",
                "UUID / title / due date / publish status match the runtime assignment",
                "Belongs to runtime termCourse + program term");
        log.info("Runtime assignment {} details verified via student session", assignmentUuid);
    }

    /**
     * Student submits the runtime assignment via {@code POST assignmentUpload/{uuid}}, using a
     * text/link-only payload (see {@link AssignmentClient#submitAssignment}'s Javadoc for why the
     * captured file-upload fields are deliberately not replicated). Generates unique runtime submission
     * content (execution timestamp + execution UUID) so every run's submission is distinguishable.
     * Stores the resulting submission identity in context and registers it for suite cleanup. Does not
     * treat the 200 response as verification by itself - only as the source of the created submission's
     * own identity, exactly like the assignment-create step treats its own POST response.
     */
    public void studentSubmitsAssignment(ScenarioContext context) {
        String assignmentUuid = context.getAssignmentUuid();
        String studentToken = context.getStudentToken();

        String description = TestDataFactory.assignmentSubmissionDescriptionHtml();
        String driveLink = TestDataFactory.assignmentSubmissionDriveLink();

        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", "");
        payload.put("url", "");
        payload.put("assignmentDescription", description);
        payload.put("driveLink", Collections.singletonList(driveLink));
        payload.put("driveFileName", driveLink);
        payload.put("submittedDate", "");
        payload.put("filestack", Collections.emptyMap());
        payload.put("fileKey", "");

        log.info("==============================\nSubmitting Assignment\nAssignment: {}\nStudent: {}\n==============================",
                context.getAssignmentName(), context.execution().getStudentEmail());

        Response response = assignmentClient.submitAssignment(assignmentUuid, payload, studentToken);
        assertThat(response.statusCode())
                .as("assignment submission must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        String submissionUuid = response.jsonPath().getString("data.uuid");
        assertThat(submissionUuid).as("submission response must expose data.uuid").isNotBlank();

        String linkedAssignmentUuid = response.jsonPath().getString("data.AssignmentDetail.uuid");
        assertThat(linkedAssignmentUuid)
                .as("submission must be linked to the runtime assignment, not a different one")
                .isEqualTo(assignmentUuid);

        String submissionStatus = response.jsonPath().getString("data.studentSubmissionReview");
        String submissionTimestamp = response.jsonPath().getString("data.submittedDate");

        context.setSubmissionUuid(submissionUuid);
        context.setSubmissionStatus(submissionStatus);
        context.setSubmissionTimestamp(submissionTimestamp);

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("assignmentUuid", assignmentUuid);
        deletionContext.put("termCourseId", context.getTermCourseId());
        deletionContext.put("sectionId", context.getSectionId());
        context.registerCreated(ResourceType.ASSIGNMENT_SUBMISSION, submissionUuid, deletionContext);

        AssignmentJourneyReport.checkpoint(context, "Assignment Submitted",
                "Submission UUID: " + submissionUuid, "Status: " + submissionStatus,
                "Linked to runtime assignment: " + assignmentUuid);
        log.info("Assignment {} submitted by runtime student. submissionUuid={}", assignmentUuid, submissionUuid);
    }

    /**
     * Verifies the submission via a GET (not the POST response alone): re-fetches the student's
     * assignment list and confirms {@code latestSubmissionExists=true} for the runtime assignment -
     * the only GET-based submission signal this HAR contains (no dedicated "get submission by id"
     * endpoint exists). Publishes the full Assignment Journey report as the final action, since this
     * is the last stage of this phase.
     */
    @SuppressWarnings("unchecked")
    public void verifyStudentSubmission(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String studentToken = context.getStudentToken();
        String assignmentUuid = context.getAssignmentUuid();

        Response response = assignmentClient.getAllStudentAssignments(programTermId, studentToken);
        assertThat(response.statusCode())
                .as("post-submission assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("data.assignment");
        Map<String, Object> mine = assignments.stream()
                .filter(a -> assignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(mine)
                .as("the runtime assignment %s must still appear in the student's list after submission", assignmentUuid)
                .isNotNull();
        assertThat(mine.get("latestSubmissionExists"))
                .as("assignment must now show a latest submission for the runtime student")
                .isEqualTo(true);

        AssignmentJourneyReport.checkpoint(context, "Submission Verified",
                "Re-fetched via getAllStudentAssignments GET - latestSubmissionExists=true");
        AssignmentJourneyReport.checkpoint(context, "Runtime Context Updated",
                "Stored: assignmentId, submissionUuid, submissionStatus, submissionTimestamp");

        log.info("Submission for assignment {} verified via student assignment-list re-fetch", assignmentUuid);
    }

    // ================= Phase 3: Draft assignment visibility (Draft != Visible) =================

    /**
     * Creates a SECOND, separate assignment on the runtime course with {@code isDraft=true}, verifies
     * it via the same admin read-back used for the published assignment (reusing {@link
     * #buildCreatePayload} / {@link #verifyAssignment}, which already parameterize isDraft - no new
     * endpoint or payload shape, only a different already-verified field value), and stores its
     * identity in the SEPARATE draftAssignmentUuid/draftAssignmentName context fields (the published
     * assignment's fields are left untouched - both assignments coexist on the runtime course).
     *
     * <p>Reads termCourseId directly from context rather than re-locating it: this scenario runs AFTER
     * "Admin creates an assignment..." in the same feature file/suite, which already resolved and
     * stored it. Fails loudly (not silently) if that assumption doesn't hold.
     */
    public void createDraftAssignmentOnRuntimeCourse(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String orgId = context.getAdminOrgId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(termCourseId)
                .as("termCourseId must already be resolved by the main Assignment Journey scenario, "
                        + "which must run before this draft-assignment scenario in the same suite")
                .isNotBlank();

        String assignmentResourceId = resolveAssignmentResourceId(orgId, adminToken);

        String name = TestDataFactory.assignmentName();
        String details = TestDataFactory.assignmentDescriptionHtml();
        String totalPoints = TestDataFactory.assignmentTotalPoints();
        String weightage = TestDataFactory.assignmentWeightagePercentage();
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        String submissionDate = LocalDate.now().plusDays(2).format(iso);
        String marksReleaseDate = LocalDate.now().plusDays(9).format(iso);
        boolean isDraft = true;

        log.info("==============================\nCreating Draft Assignment\nCourse: {}\nTermCourse: {}\n"
                        + "Name: {}\nisDraft: {}\nisGroup: false\n==============================",
                context.getCourseName(), termCourseId, name, isDraft);

        Map<String, Object> payload = buildCreatePayload(name, details, assignmentResourceId, submissionDate,
                marksReleaseDate, weightage, totalPoints, isDraft, createdBy);

        Response createResponse = assignmentClient.createAssignment(termCourseId, sectionId, payload, adminToken);
        assertThat(createResponse.statusCode())
                .as("draft assignment creation must succeed. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        String draftAssignmentUuid = createResponse.jsonPath().getString("Data.uuid");
        assertThat(draftAssignmentUuid)
                .as("created draft assignment must expose Data.uuid. Response: " + createResponse.getBody().asString())
                .isNotBlank();

        context.setDraftAssignmentUuid(draftAssignmentUuid);
        context.setDraftAssignmentName(name);
        context.setDraftAssignmentPublishStatus("Draft");

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("termCourseId", termCourseId);
        deletionContext.put("sectionId", sectionId);
        context.registerCreated(ResourceType.ASSIGNMENT, draftAssignmentUuid, deletionContext);

        AssignmentJourneyReport.checkpoint(context, "Draft Assignment Created (Admin)",
                "Name: " + name, "UUID: " + draftAssignmentUuid, "isDraft: true, isGroup: false");

        verifyAssignment(context, termCourseId, sectionId, adminToken, draftAssignmentUuid, name, details,
                submissionDate, marksReleaseDate, totalPoints, weightage, isDraft, assignmentResourceId);

        log.info("Draft assignment {} created and verified (admin-visible) on runtime termCourse {}", draftAssignmentUuid, termCourseId);
    }

    /**
     * Business rule: Draft != Visible. Confirms the draft assignment does NOT appear in the runtime
     * student's own assignment list ({@code GET getAllStudentAssignments}) - the same endpoint already
     * used to confirm the PUBLISHED assignment DOES appear. Fails loudly if the draft leaks through.
     */
    public void verifyDraftAssignmentNotVisibleToStudent(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String studentToken = context.getStudentToken();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        assertThat(draftAssignmentUuid).as("draftAssignmentUuid must be set by the draft-create stage").isNotBlank();

        Response response = assignmentClient.getAllStudentAssignments(programTermId, studentToken);
        assertThat(response.statusCode())
                .as("student assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("data.assignment");
        assertThat(assignments).as("student assignment list (data.assignment) must be present").isNotNull();

        boolean draftVisibleToStudent = assignments.stream()
                .anyMatch(a -> draftAssignmentUuid.equals(a.get("uuid")));
        assertThat(draftVisibleToStudent)
                .as("draft assignment %s must NOT appear in the runtime student's own assignment list "
                        + "(Draft != Visible) - found it, but it must stay hidden until published",
                        draftAssignmentUuid)
                .isFalse();

        AssignmentJourneyReport.checkpoint(context, "Draft Assignment Not Visible to Student",
                "Confirmed absent from getAllStudentAssignments (student session)");
        log.info("Confirmed draft assignment {} is NOT visible to the runtime student", draftAssignmentUuid);
    }

    /**
     * Business rule: Draft != Visible (the other half) - the admin must still be able to retrieve the
     * draft assignment via the same {@code GET getAllSessionAssignmentDetails} used to verify the
     * published one. Publishes the Assignment Journey report as the final action of this scenario.
     */
    @SuppressWarnings("unchecked")
    public void verifyDraftAssignmentVisibleToAdmin(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String adminToken = context.getAdminToken();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        Response response = assignmentClient.getAssignments(termCourseId, sectionId, adminToken);
        assertThat(response.statusCode())
                .as("admin assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("Data");
        Map<String, Object> mine = assignments.stream()
                .filter(a -> draftAssignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(mine)
                .as("the admin must still be able to retrieve the draft assignment %s via "
                        + "getAllSessionAssignmentDetails", draftAssignmentUuid)
                .isNotNull();
        assertThat(mine.get("isDraft"))
                .as("draft assignment must still be flagged isDraft=true for the admin")
                .isEqualTo(true);

        AssignmentJourneyReport.checkpoint(context, "Draft Assignment Visible to Admin",
                "Confirmed present (isDraft=true) via getAllSessionAssignmentDetails (admin session)");
        AssignmentJourneyReport.checkpoint(context, "Business Rule Verified",
                "Draft != Visible: hidden from student, retrievable by admin");

        log.info("Confirmed draft assignment {} remains visible to admin (isDraft=true)", draftAssignmentUuid);
    }

    // ================= Phase 4: Draft -> Published transition =================

    /**
     * Publishes the draft assignment (flips {@code isDraft} true -&gt; false) via {@code PATCH
     * assignmentDetail/{uuid}} - verified via HAR capture hawkeye-apis_2026-07-23_17-59-37.har.
     * Mirrors the real edit-and-publish UI: fetches the assignment's CURRENT state via the same GET
     * already used for admin verification, resends it unchanged except for {@code isDraft} and {@code
     * updatedBy} - never invents fresh content for the unrelated fields as a side effect of publishing.
     * Fails loudly if the assignment isn't currently a draft (a wrong precondition would silently
     * defeat the point of this test) or if the publish doesn't round-trip.
     */
    @SuppressWarnings("unchecked")
    public void publishDraftAssignment(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String adminToken = context.getAdminToken();
        String adminUserId = context.getAdminUserId();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        assertThat(draftAssignmentUuid).as("draftAssignmentUuid must be set by the draft-create stage").isNotBlank();

        Response listResponse = assignmentClient.getAssignments(termCourseId, sectionId, adminToken);
        assertThat(listResponse.statusCode())
                .as("admin assignment-list GET must succeed. Response: " + listResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = listResponse.jsonPath().getList("Data");
        Map<String, Object> current = assignments.stream()
                .filter(a -> draftAssignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(current).as("draft assignment %s must be retrievable before publishing it", draftAssignmentUuid).isNotNull();
        assertThat(current.get("isDraft")).as("assignment must currently be a draft before publishing").isEqualTo(true);

        Map<String, Object> payload = buildUpdatePayload(current, false, adminUserId, null, null, null, null, null);

        Response patchResponse = assignmentClient.updateAssignment(draftAssignmentUuid, payload, adminToken);
        assertThat(patchResponse.statusCode())
                .as("publishing the draft assignment must succeed. Response: " + patchResponse.getBody().asString())
                .isBetween(200, 299);
        assertThat(patchResponse.jsonPath().getString("Data.uuid")).as("PATCH response must echo the same assignment uuid").isEqualTo(draftAssignmentUuid);
        assertThat(patchResponse.jsonPath().getBoolean("Data.isDraft")).as("assignment must now be published (isDraft=false)").isFalse();

        context.setDraftAssignmentPublishStatus("Published");

        AssignmentJourneyReport.checkpoint(context, "Draft Assignment Published (Admin)",
                "UUID: " + draftAssignmentUuid, "PATCH assignmentDetail/" + draftAssignmentUuid + " -> isDraft: true to false");
        log.info("Draft assignment {} published (isDraft: true -> false)", draftAssignmentUuid);
    }

    /**
     * Builds the PATCH payload by flattening a {@code getAssignments} list entry (the SAME shape
     * {@link #verifyAssignment} already reads) back into a create/update-style payload: strips
     * response-only fields (uuid, isDeleted, timestamps, User, nested relation objects, etc.),
     * flattens {@code AssignmentResource.uuid} to the flat {@code assignmentResourceId} field, and
     * converts the ISO date-time fields back to the short {@code yyyy-MM-dd} form the write side
     * expects. {@code isDraft} and {@code updatedBy} are always set to caller-supplied values; the
     * five overrides (name/details/totalPoints/submissionDate/deadLineToggle) are applied when
     * non-null (the admin-edit and late-submission scenarios) and otherwise left as currently stored
     * (publish/unpublish, which only intend to change {@code isDraft} - a real "resubmit unchanged"
     * PATCH, not a content edit).
     */
    private Map<String, Object> buildUpdatePayload(Map<String, Object> current, boolean isDraft, String updatedBy,
                                                    String nameOverride, String detailsOverride,
                                                    String totalPointsOverride, String submissionDateOverride,
                                                    Boolean deadLineToggleOverride) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", nameOverride != null ? nameOverride : current.get("name"));
        payload.put("sessionId", "");
        payload.put("assignmentDetails", detailsOverride != null ? detailsOverride : current.get("assignmentDetails"));

        Object assignmentResource = current.get("AssignmentResource");
        assertThat(assignmentResource).as("current assignment must expose its AssignmentResource to flatten").isInstanceOf(Map.class);
        payload.put("assignmentResourceId", ((Map<String, Object>) assignmentResource).get("uuid"));

        payload.put("submissionDate", submissionDateOverride != null ? submissionDateOverride : shortDate(current.get("submissionDate")));
        payload.put("submissionTime", current.get("submissionTime"));
        payload.put("marksReleaseDate", shortDate(current.get("marksReleaseDate")));
        payload.put("weightagePercentage", current.get("weightagePercentage"));
        payload.put("totalPoints", totalPointsOverride != null ? totalPointsOverride : current.get("totalPoints"));
        payload.put("isGroup", current.get("isGroup"));
        payload.put("outWeightagePercentage", current.get("outWeightagePercentage"));
        payload.put("quizStartTime", current.get("quizStartTime"));
        payload.put("quizEndTime", current.get("quizEndTime"));
        payload.put("deadLineToggle", deadLineToggleOverride != null ? deadLineToggleOverride : current.get("deadLineToggle"));
        payload.put("milestoneApproval", current.get("milestoneApproval"));
        payload.put("isCapstoneMilestone", current.get("isCapstoneMilestone"));
        payload.put("autoQuizClose", current.get("autoQuizClose"));
        payload.put("propRofsUrl", current.get("propRofsUrl"));
        payload.put("timezone", current.get("timezone"));
        payload.put("isDraft", isDraft);
        payload.put("isGroupMemberSubmit", current.get("isGroupMemberSubmit"));
        payload.put("isProctoringEnabled", current.get("isProctoringEnabled"));
        payload.put("warningCount", current.get("warningCount"));
        payload.put("proctoringOptions", current.get("proctoringOptions"));
        payload.put("enableAiEvaluation", current.get("enableAiEvaluation"));
        payload.put("enableAiEvaluationDetail", current.get("enableAiEvaluationDetail"));
        payload.put("assignmentSubmissionFormat", current.get("assignmentSubmissionFormat"));
        payload.put("assignmentFormatType", current.get("assignmentFormatType"));
        payload.put("isAnonymous", current.get("isAnonymous"));
        payload.put("groupSlug", current.get("groupSlug"));
        payload.put("uploadResourceJson", current.get("uploadResourceJson"));
        payload.put("quizBeginTime", current.get("quizBeginTime"));
        payload.put("quizFinishTime", current.get("quizFinishTime"));
        payload.put("updatedBy", updatedBy);
        return payload;
    }

    /** Converts an ISO date-time string ("2026-07-24T00:00:00.000Z") to the short write-side form ("2026-07-24"). */
    private static String shortDate(Object isoDateTime) {
        String value = String.valueOf(isoDateTime);
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    /**
     * Business rule: Draft -&gt; Published transition. Confirms the just-published assignment NOW
     * appears in the runtime student's own assignment list, via the same {@code
     * GET getAllStudentAssignments} that previously confirmed it was ABSENT while still a draft.
     * Reused both as a scenario's final visibility check and, mid-scenario, as the "student sees it"
     * checkpoint before a later unpublish - does NOT publish the report itself (see {@link
     * #completeAssignmentJourneyStage}, the explicit, scenario-terminal reporting step).
     */
    public void verifyDraftAssignmentNowVisibleToStudent(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String studentToken = context.getStudentToken();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        Response response = assignmentClient.getAllStudentAssignments(programTermId, studentToken);
        assertThat(response.statusCode())
                .as("student assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("data.assignment");
        boolean nowVisible = assignments.stream().anyMatch(a -> draftAssignmentUuid.equals(a.get("uuid")));
        assertThat(nowVisible)
                .as("the just-published assignment %s must now appear in the runtime student's own "
                        + "assignment list (Draft -> Published transition)", draftAssignmentUuid)
                .isTrue();

        AssignmentJourneyReport.checkpoint(context, "Published Assignment Now Visible to Student",
                "Re-fetched via getAllStudentAssignments GET - assignment now present");
        AssignmentJourneyReport.checkpoint(context, "Business Rule Verified",
                "Draft -> Published transition: hidden while draft, visible once published");

        log.info("Confirmed assignment {} is now visible to the runtime student after publishing", draftAssignmentUuid);
    }

    // ================= Phase 5: Published -> Unpublished transition (visibility toggles correctly) =================

    /**
     * Unpublishes a currently-published assignment (flips {@code isDraft} false -&gt; true) via the
     * SAME {@code PATCH assignmentDetail/{uuid}} mechanism as {@link #publishDraftAssignment} - reuses
     * {@link #buildUpdatePayload}, which is already generic on the target {@code isDraft} value, so no
     * new endpoint or payload logic is needed. Fails loudly if the assignment isn't currently published
     * (a wrong precondition would silently defeat the point of this test).
     */
    public void unpublishAssignment(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String adminToken = context.getAdminToken();
        String adminUserId = context.getAdminUserId();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        assertThat(draftAssignmentUuid).as("draftAssignmentUuid must be set by the draft-create stage").isNotBlank();

        Response listResponse = assignmentClient.getAssignments(termCourseId, sectionId, adminToken);
        assertThat(listResponse.statusCode())
                .as("admin assignment-list GET must succeed. Response: " + listResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = listResponse.jsonPath().getList("Data");
        Map<String, Object> current = assignments.stream()
                .filter(a -> draftAssignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(current).as("assignment %s must be retrievable before unpublishing it", draftAssignmentUuid).isNotNull();
        assertThat(current.get("isDraft")).as("assignment must currently be published before unpublishing").isEqualTo(false);

        Map<String, Object> payload = buildUpdatePayload(current, true, adminUserId, null, null, null, null, null);

        Response patchResponse = assignmentClient.updateAssignment(draftAssignmentUuid, payload, adminToken);
        assertThat(patchResponse.statusCode())
                .as("unpublishing the assignment must succeed. Response: " + patchResponse.getBody().asString())
                .isBetween(200, 299);
        assertThat(patchResponse.jsonPath().getString("Data.uuid")).as("PATCH response must echo the same assignment uuid").isEqualTo(draftAssignmentUuid);
        assertThat(patchResponse.jsonPath().getBoolean("Data.isDraft")).as("assignment must now be unpublished (isDraft=true)").isTrue();

        context.setDraftAssignmentPublishStatus("Unpublished");

        AssignmentJourneyReport.checkpoint(context, "Assignment Unpublished (Admin)",
                "UUID: " + draftAssignmentUuid, "PATCH assignmentDetail/" + draftAssignmentUuid + " -> isDraft: false to true");
        log.info("Assignment {} unpublished (isDraft: false -> true)", draftAssignmentUuid);
    }

    // ================= Phase 6: Multiple assignments - submission isolation =================

    /**
     * Creates THREE separate, published assignments ("A", "B", "C") on the runtime course, reusing the
     * exact same create+verify mechanics as the single-assignment flow ({@link #buildCreatePayload} /
     * {@link #verifyAssignment}) once per assignment - no new endpoint or payload shape. Stores all
     * three uuids/names (index 0=A, 1=B, 2=C) for the isolation checks that follow.
     */
    public void createMultipleAssignmentsOnRuntimeCourse(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String orgId = context.getAdminOrgId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(termCourseId)
                .as("termCourseId must already be resolved by the main Assignment Journey scenario, "
                        + "which must run before this scenario in the same suite")
                .isNotBlank();

        String assignmentResourceId = resolveAssignmentResourceId(orgId, adminToken);
        List<String> uuids = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();

        for (String label : new String[] {"A", "B", "C"}) {
            String name = TestDataFactory.assignmentName() + " " + label;
            String details = TestDataFactory.assignmentDescriptionHtml();
            String totalPoints = TestDataFactory.assignmentTotalPoints();
            String weightage = TestDataFactory.assignmentWeightagePercentage();
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
            String submissionDate = LocalDate.now().plusDays(2).format(iso);
            String marksReleaseDate = LocalDate.now().plusDays(9).format(iso);

            Map<String, Object> payload = buildCreatePayload(name, details, assignmentResourceId, submissionDate,
                    marksReleaseDate, weightage, totalPoints, false, createdBy);

            Response createResponse = assignmentClient.createAssignment(termCourseId, sectionId, payload, adminToken);
            assertThat(createResponse.statusCode())
                    .as("assignment %s creation must succeed. Response: %s", label, createResponse.getBody().asString())
                    .isBetween(200, 299);

            String assignmentUuid = createResponse.jsonPath().getString("Data.uuid");
            assertThat(assignmentUuid).as("created assignment %s must expose Data.uuid", label).isNotBlank();

            verifyAssignment(context, termCourseId, sectionId, adminToken, assignmentUuid, name, details,
                    submissionDate, marksReleaseDate, totalPoints, weightage, false, assignmentResourceId);

            Map<String, String> deletionContext = new LinkedHashMap<>();
            deletionContext.put("termCourseId", termCourseId);
            deletionContext.put("sectionId", sectionId);
            context.registerCreated(ResourceType.ASSIGNMENT, assignmentUuid, deletionContext);

            uuids.add(assignmentUuid);
            names.add(name);
            log.info("Created assignment {} ({}) on runtime course", label, assignmentUuid);
        }

        context.setMultiAssignmentUuids(uuids);
        context.setMultiAssignmentNames(names);

        AssignmentJourneyReport.checkpoint(context, "Multiple Assignments Created (A, B, C)",
                "A: " + uuids.get(0), "B: " + uuids.get(1), "C: " + uuids.get(2));
    }

    /**
     * Confirms all three assignments (A, B, C) appear in the runtime student's own assignment list and
     * all belong to the runtime termCourse - never located by name, always by the stored uuids.
     */
    public void studentVerifiesAllMultipleAssignmentsBelongToRuntimeCourse(ScenarioContext context) {
        List<String> uuids = context.getMultiAssignmentUuids();
        assertThat(uuids).as("multi-assignment uuids (A, B, C) must be stored by the create stage").hasSize(3);

        Response response = assignmentClient.getAllStudentAssignments(context.getProgramTermId(), context.getStudentToken());
        assertThat(response.statusCode())
                .as("student assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("data.assignment");
        assertThat(assignments).as("student assignment list (data.assignment) must be present").isNotNull();

        String[] labels = {"A", "B", "C"};
        for (int i = 0; i < uuids.size(); i++) {
            String uuid = uuids.get(i);
            Map<String, Object> mine = assignments.stream()
                    .filter(a -> uuid.equals(a.get("uuid")))
                    .findFirst()
                    .orElse(null);
            assertThat(mine).as("assignment %s (%s) must appear in the student's own assignment list", labels[i], uuid).isNotNull();

            Object termCourse = mine.get("TermCourse");
            assertThat(termCourse).as("assignment %s must expose its TermCourse", labels[i]).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) termCourse).get("uuid"))
                    .as("assignment %s must belong to the runtime termCourse", labels[i])
                    .isEqualTo(context.getTermCourseId());
        }

        AssignmentJourneyReport.checkpoint(context, "All Assignments (A, B, C) Verified on Runtime Course",
                "Located by UUID (never by name); all confirmed attached to termCourse " + context.getTermCourseId());
        log.info("Confirmed all 3 assignments (A, B, C) belong to the runtime course for the runtime student");
    }

    /**
     * Submits ONLY assignment B (index 1) - a text/link-only submission, same mechanics as {@link
     * #studentSubmitsAssignment} - to set up the isolation check that follows (A and C must remain
     * unaffected).
     */
    public void studentSubmitsOnlyAssignmentB(ScenarioContext context) {
        List<String> uuids = context.getMultiAssignmentUuids();
        assertThat(uuids).as("multi-assignment uuids (A, B, C) must be stored by the create stage").hasSize(3);
        String assignmentBUuid = uuids.get(1);
        String studentToken = context.getStudentToken();

        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", "");
        payload.put("url", "");
        payload.put("assignmentDescription", TestDataFactory.assignmentSubmissionDescriptionHtml());
        payload.put("driveLink", Collections.singletonList(TestDataFactory.assignmentSubmissionDriveLink()));
        payload.put("driveFileName", TestDataFactory.assignmentSubmissionDriveLink());
        payload.put("submittedDate", "");
        payload.put("filestack", Collections.emptyMap());
        payload.put("fileKey", "");

        Response response = assignmentClient.submitAssignment(assignmentBUuid, payload, studentToken);
        assertThat(response.statusCode())
                .as("submitting assignment B must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        String linkedAssignmentUuid = response.jsonPath().getString("data.AssignmentDetail.uuid");
        assertThat(linkedAssignmentUuid).as("submission must be linked to assignment B specifically").isEqualTo(assignmentBUuid);

        String submissionUuid = response.jsonPath().getString("data.uuid");
        assertThat(submissionUuid).as("submission response must expose data.uuid").isNotBlank();

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("assignmentUuid", assignmentBUuid);
        deletionContext.put("termCourseId", context.getTermCourseId());
        deletionContext.put("sectionId", context.getSectionId());
        context.registerCreated(ResourceType.ASSIGNMENT_SUBMISSION, submissionUuid, deletionContext);

        AssignmentJourneyReport.checkpoint(context, "Only Assignment B Submitted",
                "Assignment B: " + assignmentBUuid, "Submission UUID: " + submissionUuid);
        log.info("Submitted only assignment B ({}); A and C left untouched", assignmentBUuid);
    }

    /**
     * Business rule: submission isolation. Re-fetches the student's assignment list and confirms ONLY
     * assignment B shows {@code latestSubmissionExists=true} - A and C, though created and verified
     * identically, must remain unaffected by B's submission.
     */
    public void verifyOnlyAssignmentBReflectsSubmission(ScenarioContext context) {
        List<String> uuids = context.getMultiAssignmentUuids();
        assertThat(uuids).as("multi-assignment uuids (A, B, C) must be stored by the create stage").hasSize(3);

        Response response = assignmentClient.getAllStudentAssignments(context.getProgramTermId(), context.getStudentToken());
        assertThat(response.statusCode())
                .as("student assignment-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = response.jsonPath().getList("data.assignment");
        String[] labels = {"A", "B", "C"};
        boolean[] expectedSubmitted = {false, true, false};

        for (int i = 0; i < uuids.size(); i++) {
            String uuid = uuids.get(i);
            Map<String, Object> mine = assignments.stream()
                    .filter(a -> uuid.equals(a.get("uuid")))
                    .findFirst()
                    .orElse(null);
            assertThat(mine).as("assignment %s (%s) must still appear in the student's list", labels[i], uuid).isNotNull();
            assertThat(mine.get("latestSubmissionExists"))
                    .as("assignment %s's submission state must be exactly %s (submission isolation)", labels[i], expectedSubmitted[i])
                    .isEqualTo(expectedSubmitted[i]);
        }

        AssignmentJourneyReport.checkpoint(context, "Only Assignment B Shows a Submission",
                "A: no submission", "B: submitted", "C: no submission");
        AssignmentJourneyReport.checkpoint(context, "Business Rule Verified",
                "Submission isolation: submitting one assignment does not affect sibling assignments");
        log.info("Confirmed submission isolation: only assignment B shows latestSubmissionExists=true");
    }

    // ================= Phase 7: Admin edits assignment content =================

    /**
     * Admin edits the (already-published) assignment's title, description, marks, and due date via
     * the SAME {@code PATCH assignmentDetail/{uuid}} mechanism as publish/unpublish - reuses {@link
     * #buildUpdatePayload}'s content-override parameters, so no new endpoint or payload logic is
     * needed. Generates deliberately DIFFERENT values from the current ones (a fixed points offset, a
     * different due date, fresh title/description) so the "before vs after" change is unambiguous, not
     * a coincidental match. Stores the new values for the student-side read-back that follows (title
     * reuses draftAssignmentName, updated in place).
     */
    @SuppressWarnings("unchecked")
    public void editAssignmentContent(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String adminToken = context.getAdminToken();
        String adminUserId = context.getAdminUserId();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        assertThat(draftAssignmentUuid).as("draftAssignmentUuid must be set by the draft-create stage").isNotBlank();

        Response listResponse = assignmentClient.getAssignments(termCourseId, sectionId, adminToken);
        assertThat(listResponse.statusCode())
                .as("admin assignment-list GET must succeed. Response: " + listResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = listResponse.jsonPath().getList("Data");
        Map<String, Object> current = assignments.stream()
                .filter(a -> draftAssignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(current).as("assignment %s must be retrievable before editing it", draftAssignmentUuid).isNotNull();

        String newTitle = TestDataFactory.assignmentName() + " EDITED";
        String newDescription = TestDataFactory.assignmentDescriptionHtml();
        int currentPoints = Integer.parseInt(String.valueOf(current.get("totalPoints")));
        String newMarks = String.valueOf(currentPoints + 7); // deterministically different from current
        String newDueDate = LocalDate.now().plusDays(5).format(DateTimeFormatter.ISO_LOCAL_DATE); // != the +2-day date set at creation

        Map<String, Object> payload = buildUpdatePayload(current, false, adminUserId, newTitle, newDescription, newMarks, newDueDate, null);

        Response patchResponse = assignmentClient.updateAssignment(draftAssignmentUuid, payload, adminToken);
        assertThat(patchResponse.statusCode())
                .as("editing the assignment must succeed. Response: " + patchResponse.getBody().asString())
                .isBetween(200, 299);
        assertThat(patchResponse.jsonPath().getString("Data.name")).as("PATCH response must reflect the new title").isEqualTo(newTitle);
        assertThat(patchResponse.jsonPath().getString("Data.assignmentDetails")).as("PATCH response must reflect the new description").isEqualTo(newDescription);
        assertThat(patchResponse.jsonPath().getString("Data.totalPoints")).as("PATCH response must reflect the new marks").isEqualTo(newMarks);
        assertThat(DateUtils.toCalendarDate(patchResponse.jsonPath().getString("Data.submissionDate")))
                .as("PATCH response must reflect the new due date").isEqualTo(LocalDate.parse(newDueDate));

        context.setDraftAssignmentName(newTitle);
        context.setEditedAssignmentDetails(newDescription);
        context.setEditedAssignmentMarks(newMarks);
        context.setEditedAssignmentDueDate(newDueDate);

        AssignmentJourneyReport.checkpoint(context, "Assignment Content Updated (Admin)",
                "Title: " + newTitle, "Marks: " + currentPoints + " -> " + newMarks, "Due Date: " + newDueDate);
        log.info("Assignment {} edited: title/description/marks/dueDate updated", draftAssignmentUuid);
    }

    /**
     * Student refreshes the assignment's detail view (same {@code GET assignmentUpload/{uuid}} as
     * {@link #studentRetrievesAssignmentDetails}) and confirms the admin's edits are visible: the new
     * title, description, marks, and due date - not the original values.
     */
    public void studentVerifiesUpdatedAssignmentDetails(ScenarioContext context) {
        String studentToken = context.getStudentToken();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        Response response = assignmentClient.getAssignmentDetail(draftAssignmentUuid, studentToken);
        assertThat(response.statusCode())
                .as("student assignment-detail GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> detail = response.jsonPath().getMap("data.AssignmentDetail");
        assertThat(detail).as("response must expose data.AssignmentDetail").isNotNull();

        assertThat(detail.get("name")).as("student must see the UPDATED title").isEqualTo(context.getDraftAssignmentName());
        assertThat(detail.get("assignmentDetails")).as("student must see the UPDATED description").isEqualTo(context.getEditedAssignmentDetails());
        assertThat(String.valueOf(detail.get("totalPoints"))).as("student must see the UPDATED marks").isEqualTo(context.getEditedAssignmentMarks());
        assertThat(DateUtils.toCalendarDate(detail.get("submissionDate")))
                .as("student must see the UPDATED due date").isEqualTo(LocalDate.parse(context.getEditedAssignmentDueDate()));

        AssignmentJourneyReport.checkpoint(context, "Student Sees Updated Assignment Details",
                "Title: " + context.getDraftAssignmentName(), "Marks: " + context.getEditedAssignmentMarks(),
                "Due Date: " + context.getEditedAssignmentDueDate());
        AssignmentJourneyReport.checkpoint(context, "Business Rule Verified",
                "Admin edits propagate to the student's view");
        log.info("Confirmed student sees the updated assignment {} details", draftAssignmentUuid);
    }

    // ================= Phase 8: Deadline enforcement (submission after due date) =================

    /**
     * Backdates the assignment's due date to yesterday via the SAME {@code PATCH assignmentDetail}
     * mechanism as the admin-edit scenario (reuses {@link #buildUpdatePayload}'s submissionDate
     * override), leaving {@code deadLineToggle} at its current (false) value - a deliberately safer
     * choice than trying a past date at CREATE time, which no HAR proves the backend accepts; PATCH
     * accepting a submissionDate change is already proven by the admin-edit scenario.
     */
    public void backdateAssignmentDueDate(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String adminToken = context.getAdminToken();
        String adminUserId = context.getAdminUserId();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        assertThat(draftAssignmentUuid).as("draftAssignmentUuid must be set by the draft-create stage").isNotBlank();

        Map<String, Object> current = fetchCurrentAssignment(termCourseId, sectionId, adminToken, draftAssignmentUuid);

        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, Object> payload = buildUpdatePayload(current, false, adminUserId, null, null, null, yesterday, null);

        Response patchResponse = assignmentClient.updateAssignment(draftAssignmentUuid, payload, adminToken);
        assertThat(patchResponse.statusCode())
                .as("backdating the assignment must succeed. Response: " + patchResponse.getBody().asString())
                .isBetween(200, 299);
        assertThat(patchResponse.jsonPath().getString("Data.submissionDate")).as("due date must now be yesterday").startsWith(yesterday);

        context.setEditedAssignmentDueDate(yesterday);

        AssignmentJourneyReport.checkpoint(context, "Assignment Due Date Backdated (Admin)",
                "UUID: " + draftAssignmentUuid, "New due date: " + yesterday + " (yesterday)");
        log.info("Assignment {} backdated to yesterday ({})", draftAssignmentUuid, yesterday);
    }

    /**
     * Student retrieves the past-due assignment's detail view - confirms viewing is NOT blocked by
     * the passed deadline (only submission is), via the same {@code GET assignmentUpload/{uuid}} used
     * throughout the journey.
     */
    public void studentViewsPastDueAssignment(ScenarioContext context) {
        String studentToken = context.getStudentToken();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        Response response = assignmentClient.getAssignmentDetail(draftAssignmentUuid, studentToken);
        assertThat(response.statusCode())
                .as("student must still be able to VIEW a past-due assignment. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        assertThat(response.jsonPath().getString("data.AssignmentDetail.uuid")).as("viewed assignment uuid must match").isEqualTo(draftAssignmentUuid);
        assertThat(DateUtils.toCalendarDate(response.jsonPath().getString("data.AssignmentDetail.submissionDate")))
                .as("viewed assignment must show the backdated (past) due date")
                .isEqualTo(LocalDate.parse(context.getEditedAssignmentDueDate()));

        AssignmentJourneyReport.checkpoint(context, "Student Can View Past-Due Assignment",
                "Due date: " + context.getEditedAssignmentDueDate() + " (in the past) - view succeeds");
        log.info("Confirmed student can view past-due assignment {}", draftAssignmentUuid);
    }

    /**
     * Student attempts to submit the past-due assignment (deadLineToggle=false, the default - late
     * submission NOT allowed). Verified via a live 406 response captured directly from the backend:
     * {@code { "code": 406, "message": "Submission is not allowed after the deadline" } }. Registers
     * NOTHING for cleanup - the submission was rejected, nothing was created.
     */
    public void studentAttemptsToSubmitPastDueAssignment(ScenarioContext context) {
        String draftAssignmentUuid = context.getDraftAssignmentUuid();
        String studentToken = context.getStudentToken();

        Map<String, Object> payload = buildTextLinkSubmissionPayload();

        Response response = assignmentClient.submitAssignment(draftAssignmentUuid, payload, studentToken);
        assertThat(response.statusCode())
                .as("submitting a past-due assignment (deadLineToggle=false) must be rejected. Response: " + response.getBody().asString())
                .isEqualTo(406);
        assertThat(response.jsonPath().getString("message"))
                .as("rejection message must explain the deadline block")
                .isEqualTo("Submission is not allowed after the deadline");

        AssignmentJourneyReport.checkpoint(context, "Submission Rejected (Past Deadline)",
                "HTTP 406: \"Submission is not allowed after the deadline\"");
        AssignmentJourneyReport.checkpoint(context, "Business Rule Verified",
                "Submissions are blocked after an assignment's deadline (deadLineToggle=false)");
        log.info("Confirmed submission to past-due assignment {} is rejected with 406", draftAssignmentUuid);
    }

    /**
     * Enables late submission (flips {@code deadLineToggle} to true) AND backdates the due date to
     * yesterday in the SAME PATCH - reuses {@link #buildUpdatePayload}'s submissionDate and
     * deadLineToggle overrides together, mirroring how a real admin would toggle "Allow Submission
     * after deadline" on while editing an already-past-due assignment.
     */
    public void enableLateSubmissionOnPastDueAssignment(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String adminToken = context.getAdminToken();
        String adminUserId = context.getAdminUserId();
        String draftAssignmentUuid = context.getDraftAssignmentUuid();

        assertThat(draftAssignmentUuid).as("draftAssignmentUuid must be set by the draft-create stage").isNotBlank();

        Map<String, Object> current = fetchCurrentAssignment(termCourseId, sectionId, adminToken, draftAssignmentUuid);

        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, Object> payload = buildUpdatePayload(current, false, adminUserId, null, null, null, yesterday, true);

        Response patchResponse = assignmentClient.updateAssignment(draftAssignmentUuid, payload, adminToken);
        assertThat(patchResponse.statusCode())
                .as("enabling late submission must succeed. Response: " + patchResponse.getBody().asString())
                .isBetween(200, 299);
        assertThat(patchResponse.jsonPath().getString("Data.submissionDate")).as("due date must be yesterday").startsWith(yesterday);
        assertThat(patchResponse.jsonPath().getBoolean("Data.deadLineToggle")).as("deadLineToggle must now be true").isTrue();

        context.setEditedAssignmentDueDate(yesterday);

        AssignmentJourneyReport.checkpoint(context, "Late Submission Enabled + Due Date Backdated (Admin)",
                "UUID: " + draftAssignmentUuid, "Due date: " + yesterday + " (yesterday)", "deadLineToggle: true");
        log.info("Assignment {} backdated to {} with deadLineToggle=true (late submission allowed)", draftAssignmentUuid, yesterday);
    }

    /**
     * Student submits the past-due assignment with {@code deadLineToggle=true} - the submission must
     * SUCCEED despite being late, the inverse of {@link #studentAttemptsToSubmitPastDueAssignment}.
     * Registers the resulting submission for suite cleanup like every other successful submission.
     */
    public void studentSubmitsPastDueAssignmentWithLateSubmissionAllowed(ScenarioContext context) {
        String draftAssignmentUuid = context.getDraftAssignmentUuid();
        String studentToken = context.getStudentToken();

        Map<String, Object> payload = buildTextLinkSubmissionPayload();

        Response response = assignmentClient.submitAssignment(draftAssignmentUuid, payload, studentToken);
        assertThat(response.statusCode())
                .as("submitting a past-due assignment with deadLineToggle=true must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        String submissionUuid = response.jsonPath().getString("data.uuid");
        assertThat(submissionUuid).as("submission response must expose data.uuid").isNotBlank();
        assertThat(response.jsonPath().getString("data.AssignmentDetail.uuid"))
                .as("submission must be linked to the past-due assignment")
                .isEqualTo(draftAssignmentUuid);

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("assignmentUuid", draftAssignmentUuid);
        deletionContext.put("termCourseId", context.getTermCourseId());
        deletionContext.put("sectionId", context.getSectionId());
        context.registerCreated(ResourceType.ASSIGNMENT_SUBMISSION, submissionUuid, deletionContext);

        AssignmentJourneyReport.checkpoint(context, "Late Submission Accepted",
                "Submission UUID: " + submissionUuid, "Despite being past the deadline (deadLineToggle=true)");
        AssignmentJourneyReport.checkpoint(context, "Business Rule Verified",
                "deadLineToggle=true overrides the deadline block and allows late submission");
        log.info("Confirmed late submission accepted for past-due assignment {} (deadLineToggle=true)", draftAssignmentUuid);
    }

    /** Fetches an assignment's current admin-visible state by uuid, asserting it's retrievable. */
    private Map<String, Object> fetchCurrentAssignment(String termCourseId, String sectionId, String adminToken, String assignmentUuid) {
        Response listResponse = assignmentClient.getAssignments(termCourseId, sectionId, adminToken);
        assertThat(listResponse.statusCode())
                .as("admin assignment-list GET must succeed. Response: " + listResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> assignments = listResponse.jsonPath().getList("Data");
        Map<String, Object> current = assignments.stream()
                .filter(a -> assignmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(current).as("assignment %s must be retrievable", assignmentUuid).isNotNull();
        return current;
    }

    /** Builds the same text/link-only submission payload used by every submission stage in this journey. */
    private Map<String, Object> buildTextLinkSubmissionPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", "");
        payload.put("url", "");
        payload.put("assignmentDescription", TestDataFactory.assignmentSubmissionDescriptionHtml());
        payload.put("driveLink", Collections.singletonList(TestDataFactory.assignmentSubmissionDriveLink()));
        payload.put("driveFileName", TestDataFactory.assignmentSubmissionDriveLink());
        payload.put("submittedDate", "");
        payload.put("filestack", Collections.emptyMap());
        payload.put("fileKey", "");
        return payload;
    }

    /**
     * Explicit, scenario-terminal reporting step: publishes the Assignment Journey report accumulated
     * so far. Kept separate from the individual verification methods (several of which - {@link
     * #verifyDraftAssignmentNotVisibleToStudent}, {@link #verifyDraftAssignmentNowVisibleToStudent} -
     * are reused MID-scenario, e.g. by the unpublish scenario's before/after visibility checks) so
     * reusing a verification method never prematurely closes out a scenario's report.
     */
    public void completeAssignmentJourneyStage(ScenarioContext context) {
        AssignmentJourneyReport.publish(context);
    }
}
