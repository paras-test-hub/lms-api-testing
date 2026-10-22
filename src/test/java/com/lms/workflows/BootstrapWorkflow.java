package com.lms.workflows;

import com.lms.cleanup.ResourceType;
import com.lms.clients.admin.CohortClient;
import com.lms.clients.admin.CourseClient;
import com.lms.clients.admin.ReferenceDataClient;
import com.lms.clients.admin.UserClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.EnvironmentSetupReport;
import com.lms.utils.JsonSearch;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lms.utils.DateUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for Environment Setup - the one-time provisioning of a
 * complete, isolated academic environment (Program -> Cohort -> Program Term -> Section ->
 * Course -> Attach Course to Section -> Student -> Assign Student to Section) that every
 * Business Workflow (Assignment, Attendance, Quiz, Announcement, Grading, Event, Certificate,
 * Placement, ...) runs against afterward. This is NOT a business workflow and NOT a test - it
 * is the "build a fresh academic institution" step that precedes all of them. Business
 * Workflows must consume the ids this produces via ScenarioContext and must never create their
 * own Program/Cohort/Program Term/Section/Course/Student.
 *
 * Keeps business logic out of the thin Cucumber steps (BootstrapSteps just delegates here).
 * Every id it uses comes from ScenarioContext (populated by the earlier Program/Cohort/
 * admin-login steps); nothing is hardcoded except genuinely static configuration (userType,
 * countryCode, password, ...).
 *
 * Grown one verified link at a time; currently owns the Student-creation step. Program/cohort
 * creation still live inline in BootstrapSteps and will migrate here as the chain consolidates.
 *
 * TODO: rename this class to EnvironmentSetupWorkflow (and BootstrapSteps to
 * EnvironmentSetupSteps) in a dedicated refactoring pass - the "Bootstrap" name predates this
 * architectural clarification and is being kept for now to avoid a large, ripple-heavy rename
 * across every call site in the same change as the enrollment work.
 */
public class BootstrapWorkflow {

    private static final Logger log = LoggerFactory.getLogger(BootstrapWorkflow.class);

    // ---- Static configuration (not test data) ----
    /**
     * userType is the literal string "Student" (or "Employee"), NOT a uuid - the live API
     * rejects a uuid with 400 {"message": "\"userType\" must be one of [Student, Employee]"}.
     * (The uuid in the original payload spec was a role id, a different field.)
     */
    private static final String STUDENT_USER_TYPE = "Student";
    private static final String COUNTRY_CODE = "+91";
    private static final String INITIAL_ADDITIONS = "Mr";

    /**
     * courseFormat/coursePurpose/attendanceType are fixed literal strings, NOT sourced from any
     * GET lookup - confirmed by searching a full HAR capture (hawkeye-apis_2026-07-15_06-48-11.har,
     * 45 requests) of the real "add course" admin flow: no endpoint anywhere in the trace serves
     * either of these as a dropdown. Every real POST/PATCH captured sends these exact literals.
     */
    private static final String COURSE_FORMAT = "session";
    private static final String COURSE_PURPOSE = "session";
    private static final String ATTENDANCE_TYPE = "qr";

    private final UserClient userClient;
    private final CohortClient cohortClient;
    private final ReferenceDataClient referenceDataClient;
    private final CourseClient courseClient;

    public BootstrapWorkflow() {
        this.userClient = new UserClient();
        this.cohortClient = new CohortClient();
        this.referenceDataClient = new ReferenceDataClient();
        this.courseClient = new CourseClient();
    }

    /**
     * Creates a Student inside the already-created Program + Cohort, verifies it via read-back,
     * and stores the identity in ScenarioContext for the later Student Login step.
     */
    public void createAndVerifyStudent(ScenarioContext context) {
        String programId = context.getProgramId();
        String cohortId = context.getCohortId();
        String orgId = context.getAdminOrgId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(programId).as("programId must be set by the Create Program step before creating a student").isNotBlank();
        assertThat(cohortId).as("cohortId must be set by the Create Cohort step before creating a student").isNotBlank();
        assertThat(orgId).as("adminOrgId must be set by admin login").isNotBlank();
        assertThat(createdBy).as("adminUserId must be set by admin login").isNotBlank();

        String firstName = TestDataFactory.studentFirstName();
        String lastName = TestDataFactory.studentLastName();
        String email = TestDataFactory.studentEmail();
        String mobile = TestDataFactory.mobileNumber();
        String password = TestDataFactory.studentPassword();

        // Informative, non-sensitive logging (no password / no token).
        log.info("Creating automation student... program={} cohort={} name={} {} email={} mobile={}",
                context.getProgramName(), context.getCohortName(), firstName, lastName, email, mobile);

        Map<String, Object> fields = new HashMap<>();
        fields.put("firstName", firstName);
        fields.put("lastName", lastName);
        fields.put("officialEmail", email);
        fields.put("password", password);
        fields.put("mobileNumber", mobile);
        fields.put("countryCode", COUNTRY_CODE);
        fields.put("cohortId", cohortId);
        fields.put("programId", programId);
        fields.put("userType", STUDENT_USER_TYPE);
        fields.put("initialAdditions", INITIAL_ADDITIONS);
        fields.put("designation", "");
        fields.put("department", "");
        fields.put("image", "");
        fields.put("calendlyLink", "");
        fields.put("roleId", "");

        Response createResponse = userClient.createStudent(orgId, fields, createdBy, adminToken);
        assertThat(createResponse.statusCode())
                .as("student creation must succeed. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        Object createBody = createResponse.jsonPath().getMap("$");
        Object studentUuid = JsonSearch.findFirstValue(createBody, "uuid");
        assertThat(studentUuid).as("created student must expose a uuid in the create response").isNotNull();

        // Store identity for the Student Login step and register for future cleanup. The runtime
        // student's OWN credentials live in the suite-scoped ExecutionContext (context.getStudentEmail()
        // on ScenarioContext is the separate per-scenario auth-endpoint-test scratch), so address them
        // explicitly via execution().
        context.setStudentUuid(studentUuid.toString());
        context.setStudentUserId(studentUuid.toString());
        context.execution().setStudentEmail(email);
        context.execution().setStudentPassword(password);
        context.setStudentProgramId(programId);
        context.setStudentCohortId(cohortId);
        context.registerCreated(ResourceType.STUDENT, studentUuid.toString());

        verifyStudent(context, firstName, lastName, email);

        EnvironmentSetupReport.checkpoint(context, "Student Created",
                "Email: " + email, "UUID: " + studentUuid);
        EnvironmentSetupReport.checkpoint(context, "Student Assigned to Program",
                "Program UUID: " + programId);
        EnvironmentSetupReport.checkpoint(context, "Student Assigned to Cohort",
                "Cohort UUID: " + cohortId);

        log.info("Automation student created and verified. studentUuid={}", studentUuid);
    }

    /**
     * Resolves the Cohort's auto-created "Term 1", configures it with a valid runtime date window
     * (so today falls inside it), reads it back, and stores its authoritative id + verified details
     * in the suite-scoped context for every downstream journey (Section, Course attach, Enrollment,
     * Session, Attendance, Assignment, ...).
     *
     * <p><b>No term is created here.</b> When a Cohort is created (with {@code numberOfTerms >= 1})
     * the backend AUTOMATICALLY provisions its default terms ("Term 1", ...), but with
     * {@code startDate}/{@code endDate} = null. This method resolves that existing "Term 1" via
     * {@link CohortClient#listProgramTerms} (never POSTing a new term - {@code createProgramTerm} is
     * deliberately never called, as an extra term would pollute the isolated environment), then
     * PATCHes the SAME term into a valid window and verifies the persisted state.
     *
     * <p>Configuration flow (all shapes HAR-verified against this framework's own runtime cohort -
     * references/hawkeye-apis_2026-09-14_12-24-52.har):
     * <ol>
     *   <li>List terms, select the one named "Term 1", capture its uuid as the authoritative programTermId.</li>
     *   <li>GET getCohortDetails to read that term's existing {@code CourseFormats[]} (their ids are
     *       required in the PATCH's {@code courseFormatArray} and must come from the API, never invented).</li>
     *   <li>PATCH programTerm/{uuid} with a dynamic window (today-7d .. today+30d) plus the HAR-proven
     *       passthrough fields. {@code programId} in that payload is the COHORT uuid, per the HAR.</li>
     *   <li>GET getCohortDetails again, locate the term BY UUID (not by position), and verify: same uuid,
     *       term == "Term 1", non-null dates, dates round-trip to the values sent, and today is inside the
     *       inclusive window. {@code isActiveTerm} is intentionally NOT required true (the HAR proves it
     *       stays false even for a valid window containing today).</li>
     *   <li>Store programTermId/name/startDate/endDate in the context.</li>
     * </ol>
     */
    public void createAndVerifyProgramTerm(ScenarioContext context) {
        String cohortId = context.getCohortId();
        String adminToken = context.getAdminToken();
        String updatedBy = context.getAdminUserId();

        assertThat(cohortId).as("cohortId must be set by the Create Cohort step before resolving a program term").isNotBlank();
        assertThat(updatedBy).as("adminUserId must be set by admin login before configuring a program term").isNotBlank();

        log.info("Resolving automation program term (Term 1)... cohort={}", context.getCohortName());

        // 1. Resolve the backend-auto-created "Term 1" and capture its authoritative uuid.
        Response listResponse = cohortClient.listProgramTerms(cohortId, adminToken);
        assertThat(listResponse.statusCode())
                .as("listing program terms for cohort %s must succeed. Response: %s", cohortId, listResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> terms = listResponse.jsonPath().getList("Data");
        assertThat(terms).as("program term list must not be empty for cohort %s", cohortId).isNotEmpty();

        Map<String, Object> term1 = terms.stream()
                .filter(t -> "Term 1".equalsIgnoreCase(String.valueOf(t.get("term"))))
                .findFirst()
                .orElse(null);

        assertThat(term1)
                .as("Runtime cohort does not contain the expected Term 1. Cannot continue Environment Setup. Available terms: %s",
                        terms.stream().map(t -> String.valueOf(t.get("term"))).collect(java.util.stream.Collectors.toList()))
                .isNotNull();

        Object programTermIdObj = term1.get("uuid");
        assertThat(programTermIdObj).as("Term 1 must expose a non-empty uuid").isNotNull();
        String programTermId = programTermIdObj.toString().trim();
        assertThat(programTermId).as("Term 1 uuid must not be blank").isNotBlank();

        context.setProgramTermId(programTermId);
        log.info("Runtime program term resolved (Term 1). programTermId={}", programTermId);

        // 2. Read the term's existing CourseFormats so the PATCH can echo their ids back (never invented).
        Response preDetails = cohortClient.getCohortDetails(cohortId, adminToken);
        assertThat(preDetails.statusCode())
                .as("getCohortDetails (pre-configure) must succeed. Response: " + preDetails.getBody().asString())
                .isBetween(200, 299);
        Map<String, Object> preTerm = findTermByUuid(preDetails, programTermId);
        assertThat(preTerm)
                .as("Term 1 (uuid %s) must be present in getCohortDetails before configuration", programTermId)
                .isNotNull();
        List<Map<String, Object>> courseFormatArray = buildCourseFormatArray(preTerm);

        // 3. PATCH the SAME term into a valid, dynamic window (today inside it). Dates are plain
        // ISO calendar strings (yyyy-MM-dd), exactly as the HAR sends them - no time, no Z.
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(7);
        LocalDate endDate = today.plusDays(30);

        Map<String, Object> fields = new HashMap<>();
        fields.put("startDate", startDate.format(iso));
        fields.put("endDate", endDate.format(iso));
        // HAR-proven passthrough fields (references/hawkeye-apis_2026-09-14_12-24-52.har PATCH body).
        // These are the values the real admin save sent; not IDs, not dates, not invented.
        fields.put("skipFeedback", true);
        fields.put("minCourseCount", 2);
        fields.put("maxCourseCount", 15);
        fields.put("country", "");
        fields.put("documentRequirements", Collections.emptyList());
        if (!courseFormatArray.isEmpty()) {
            fields.put("courseFormatArray", courseFormatArray);
        }
        // programId here is the COHORT/batch uuid (verified live: the Program Master id is rejected).
        fields.put("programId", cohortId);

        log.info("Configuring runtime Term 1 window... programTermId={} start={} end={}",
                programTermId, startDate, endDate);

        Response patchResponse = cohortClient.updateProgramTerm(programTermId, fields, updatedBy, adminToken);
        assertThat(patchResponse.statusCode())
                .as("program term update (PATCH) must succeed. Response: " + patchResponse.getBody().asString())
                .isBetween(200, 299);

        // 4. Read back via getCohortDetails and verify the SAME term's persisted state.
        Response postDetails = cohortClient.getCohortDetails(cohortId, adminToken);
        assertThat(postDetails.statusCode())
                .as("getCohortDetails (post-configure) must succeed. Response: " + postDetails.getBody().asString())
                .isBetween(200, 299);
        Map<String, Object> postTerm = findTermByUuid(postDetails, programTermId);
        assertThat(postTerm)
                .as("configured Term 1 (uuid %s) must be present in getCohortDetails after configuration", programTermId)
                .isNotNull();

        verifyConfiguredProgramTerm(postTerm, programTermId, startDate, endDate, today);

        // 5. Store the verified runtime term state (from the read-back, not the PATCH response).
        String termName = String.valueOf(postTerm.get("term"));
        context.setProgramTermName(termName);
        context.setProgramTermStartDate(startDate.format(iso));
        context.setProgramTermEndDate(endDate.format(iso));

        EnvironmentSetupReport.checkpoint(context, "Runtime Term 1 Configured",
                "Term: " + termName,
                "UUID: " + programTermId,
                "Window: " + startDate.format(iso) + " -> " + endDate.format(iso),
                "Active Today: true (today falls inside the configured window)");

        log.info("Runtime Term 1 configured and verified. programTermId={} window={}..{}",
                programTermId, startDate, endDate);
    }

    /**
     * Locates a Program Term inside a getCohortDetails response by its uuid (never by array position),
     * so read-back always addresses the exact term that was configured.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findTermByUuid(Response cohortDetails, String programTermId) {
        List<Map<String, Object>> programTerms = cohortDetails.jsonPath().getList("Data.ProgramTerm");
        if (programTerms == null) {
            return null;
        }
        return programTerms.stream()
                .filter(t -> programTermId.equals(String.valueOf(t.get("uuid"))))
                .findFirst()
                .orElse(null);
    }

    /**
     * Builds the PATCH's {@code courseFormatArray} from the term's existing {@code CourseFormats[]},
     * echoing each entry's real uuid as {@code courseFormatId} plus its {@code minTermCount} - the ids
     * come from the runtime API response, never fabricated. Returns an empty list if the term exposes
     * no CourseFormats, in which case the caller omits the field entirely rather than invent one.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildCourseFormatArray(Map<String, Object> term) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object courseFormats = term.get("CourseFormats");
        if (!(courseFormats instanceof List)) {
            return result;
        }
        for (Object entry : (List<Object>) courseFormats) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<String, Object> cf = (Map<String, Object>) entry;
            Object formatId = cf.get("uuid");
            if (formatId == null) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("courseFormatId", formatId);
            item.put("minTermCount", cf.get("minTermCount"));
            result.add(item);
        }
        return result;
    }

    /**
     * Verifies the configured Term 1's persisted state from the getCohortDetails read-back: same uuid,
     * term == "Term 1", non-null dates that round-trip (via calendar-date normalization) to the values
     * sent, and that today falls inside the inclusive [start, end] window. {@code isActiveTerm} is NOT
     * asserted - the HAR proves it stays false even when the window contains today.
     */
    private void verifyConfiguredProgramTerm(Map<String, Object> term, String programTermId,
                                             LocalDate expectedStart, LocalDate expectedEnd, LocalDate today) {
        assertThat(String.valueOf(term.get("uuid")))
                .as("read-back term uuid must equal the configured programTermId")
                .isEqualTo(programTermId);
        assertThat(String.valueOf(term.get("term")))
                .as("configured runtime term must be Term 1")
                .isEqualTo("Term 1");

        assertThat(term.get("startDate")).as("configured Term 1 startDate must be non-null after PATCH").isNotNull();
        assertThat(term.get("endDate")).as("configured Term 1 endDate must be non-null after PATCH").isNotNull();

        LocalDate persistedStart = DateUtils.toCalendarDate(term.get("startDate"));
        LocalDate persistedEnd = DateUtils.toCalendarDate(term.get("endDate"));
        assertThat(persistedStart)
                .as("persisted Term 1 startDate must round-trip to the configured start")
                .isEqualTo(expectedStart);
        assertThat(persistedEnd)
                .as("persisted Term 1 endDate must round-trip to the configured end")
                .isEqualTo(expectedEnd);

        assertThat(!today.isBefore(persistedStart) && !today.isAfter(persistedEnd))
                .as("today (%s) must fall inside the runtime Term 1 window [%s, %s]", today, persistedStart, persistedEnd)
                .isTrue();
    }

    /**
     * Creates a Section under the already-created Program Term, verifies it, and stores its
     * id in ScenarioContext for the Course-attach step.
     *
     * Request shape verified from lms-admin-frontend's customSection thunk (programSlice.js):
     * POST /api/v1/program/programTerm/{programTermId}/section with { sectionName, createdBy }.
     */
    public void createAndVerifySection(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(programTermId).as("programTermId must be set by the Create Program Term step before creating a section").isNotBlank();

        String sectionName = TestDataFactory.sectionName();
        log.info("Creating automation section... programTerm={} section={}", programTermId, sectionName);

        Map<String, Object> fields = new HashMap<>();
        // Verified live: the field is "name", not "sectionName" (400 "\"name\" is required" otherwise).
        fields.put("name", sectionName);

        Response createResponse = cohortClient.createSection(programTermId, fields, createdBy, adminToken);
        assertThat(createResponse.statusCode())
                .as("section creation must succeed. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        Object body = createResponse.jsonPath().getMap("$");
        Object sectionId = JsonSearch.findFirstValue(body, "uuid");
        assertThat(sectionId).as("created section must expose a uuid in the create response").isNotNull();

        context.setSectionId(sectionId.toString());
        context.registerCreated(ResourceType.SECTION, sectionId.toString());

        EnvironmentSetupReport.checkpoint(context, "Section Created",
                "Name: " + sectionName, "UUID: " + sectionId);

        log.info("Automation section created. sectionId={}", sectionId);
    }

    /**
     * Creates a Course Master, applies the two mandatory PATCHes the real admin frontend sends
     * after creation, and verifies the final state via GET. All request/response shapes below
     * are verified from a real HAR network capture (hawkeye-apis_2026-07-15_06-48-11.har) of
     * the admin "add course" flow, not the OpenAPI spec.
     *
     * The two PATCHes are internal plumbing of the real multi-step save form (both hit the
     * SAME courseMaster/{id} endpoint with progressively fuller bodies) - not independently
     * meaningful business steps, so they're not split into separate public methods/Then steps.
     *
     * Course Type / Modality / Class Type / Department / Course Tag are randomly selected from
     * live GET lookups (never hardcoded); each lookup fails LOUD (IllegalStateException, not a
     * skip-style Assumption) if empty, per explicit requirement. Course Purpose and Attendance
     * Type are fixed literals (COURSE_PURPOSE/ATTENDANCE_TYPE) - no backing lookup exists.
     */
    public void createAndVerifyCourse(ScenarioContext context) {
        String orgId = context.getAdminOrgId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(orgId).as("adminOrgId must be set by admin login").isNotBlank();
        assertThat(createdBy).as("adminUserId must be set by admin login").isNotBlank();

        Map<String, Object> courseType = pickSpecificReferenceItem(
                referenceDataClient.listCourseTypes(orgId, adminToken), "name", "Core",
                "No 'Core' course type found via GET .../getAllCourseTypes - environment setup cannot continue.");
        Map<String, Object> courseMode = pickRequiredReferenceItem(
                referenceDataClient.listCourseModes(orgId, adminToken), "mode",
                "No course modes (modality) available via GET .../getCourseModes - environment setup cannot continue.");
        Map<String, Object> courseClass = pickRequiredReferenceItem(
                referenceDataClient.listCourseClasses(orgId, adminToken), "name",
                "No course classes (class type) available via GET .../getCourseClass - environment setup cannot continue.");
        Map<String, Object> department = pickRequiredReferenceItem(
                referenceDataClient.listDepartments(adminToken), "name",
                "No departments available via GET .../nps-department - environment setup cannot continue.");

        String courseName = TestDataFactory.courseName();
        String courseCode = TestDataFactory.courseCode();
        int credit = TestDataFactory.courseCredit();
        int hours = TestDataFactory.courseHours();
        int sessions = TestDataFactory.courseSessions();

        log.info("==============================\n"
                        + "Creating Course\nProgram: {}\nCohort: {}\nCourse: {}\nCredit: {}\n"
                        + "Department: {}\nCourse Type: {}\nClass Type: {}\nModality: {}\n"
                        + "Purpose: {}\nAttendance: {}\n==============================",
                context.getProgramName(), context.getCohortName(), courseName, credit,
                department.get("name"), courseType.get("name"), courseClass.get("name"), courseMode.get("mode"),
                COURSE_PURPOSE, ATTENDANCE_TYPE);

        Map<String, Object> createFields = new HashMap<>();
        createFields.put("name", courseName);
        createFields.put("courseCode", courseCode);
        createFields.put("isDraft", true);
        createFields.put("courseShortName", "");
        createFields.put("isPublished", true);
        createFields.put("courseModeId", courseMode.get("uuid"));
        createFields.put("courseTypeId", courseType.get("uuid"));
        createFields.put("courseClassId", courseClass.get("uuid"));
        createFields.put("credits", String.valueOf(credit));
        createFields.put("courseFormat", COURSE_FORMAT);
        createFields.put("hours", String.valueOf(hours));
        createFields.put("numberOfSessions", String.valueOf(sessions));
        createFields.put("evaluationCriteria", Collections.singletonList(evaluationCriterion(
                "classParticipation", "100", "", "", "")));
        createFields.put("courseOverview", Collections.emptyMap());
        createFields.put("description", "");
        createFields.put("courseOutcome", "");
        createFields.put("workexperienced", "");
        createFields.put("readingMaterial", "");
        createFields.put("videoLink", "");
        createFields.put("topicsJson", Collections.emptyList());
        createFields.put("courseDomain", Collections.emptyList());
        createFields.put("placementCourse", false);
        createFields.put("courseTagId", "");
        createFields.put("subjectWeight", "");
        createFields.put("isGamify", false);
        createFields.put("isGamifyDomainWise", false);
        createFields.put("attendanceType", ATTENDANCE_TYPE);
        createFields.put("departmentId", department.get("uuid"));
        createFields.put("coursePurpose", COURSE_PURPOSE);

        Response createResponse = courseClient.create(orgId, createFields, createdBy, adminToken);
        assertThat(createResponse.statusCode())
                .as("course creation must succeed. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        Object createBody = createResponse.jsonPath().getMap("$");
        Object courseId = JsonSearch.findFirstValue(createBody, "uuid");
        assertThat(courseId).as("created course must expose a uuid in the create response").isNotNull();
        String courseIdStr = courseId.toString();

        context.setCourseId(courseIdStr);
        context.setCourseName(courseName);
        context.setCourseCode(courseCode);
        context.setCourseCredit(credit);
        context.setDepartmentId((String) department.get("uuid"));
        context.setCourseTypeId((String) courseType.get("uuid"));
        context.setClassTypeId((String) courseClass.get("uuid"));
        context.setCourseModeId((String) courseMode.get("uuid"));
        context.setAttendanceType(ATTENDANCE_TYPE);
        context.setCoursePurpose(COURSE_PURPOSE);
        context.registerCreated(ResourceType.COURSE, courseIdStr);

        // PATCH #1: real flow sets a real courseTagId (was "" on create) and nulls subjectWeight.
        Map<String, Object> courseTag = pickRequiredReferenceItem(
                referenceDataClient.listCourseTags(orgId, adminToken), "name",
                "No course tags available via GET .../getAllCourseTags - environment setup cannot continue.");
        String courseTagId = (String) courseTag.get("uuid");
        Map<String, Object> patch1Fields = new HashMap<>(createFields);
        patch1Fields.put("courseTagId", courseTagId);
        patch1Fields.put("subjectWeight", null);
        Response patch1Response = courseClient.update(orgId, courseIdStr, patch1Fields, createdBy, adminToken);
        assertThat(patch1Response.statusCode())
                .as("course PATCH #1 (courseTagId) must succeed. Response: " + patch1Response.getBody().asString())
                .isBetween(200, 299);
        context.setCourseTagId(courseTagId);

        // PATCH #2: real flow expands evaluationCriteria to 3 entries and adds a description.
        Map<String, Object> patch2Fields = new HashMap<>(patch1Fields);
        patch2Fields.put("evaluationCriteria", java.util.Arrays.asList(
                evaluationCriterion("classParticipation", "10", "group", "", ""),
                evaluationCriterion("attendance", "30", "individual", "", ""),
                evaluationCriterion("quiz", "60", "individual", "", "")));
        patch2Fields.put("description", "Created by automated environment setup workflow.");
        Response patch2Response = courseClient.update(orgId, courseIdStr, patch2Fields, createdBy, adminToken);
        assertThat(patch2Response.statusCode())
                .as("course PATCH #2 (evaluationCriteria/description) must succeed. Response: " + patch2Response.getBody().asString())
                .isBetween(200, 299);

        verifyCourse(context, courseName, courseCode, credit, hours, sessions,
                courseType, courseMode, courseClass, department);

        EnvironmentSetupReport.checkpoint(context, "Course Created",
                "Name: " + courseName, "UUID: " + courseIdStr);

        log.info("Automation course created, patched twice, and verified. courseId={}", courseIdStr);
    }

    private static Map<String, Object> evaluationCriterion(String criteria, String percentage, String details, String from, String to) {
        Map<String, Object> item = new HashMap<>();
        item.put("criteria", criteria);
        item.put("percentage", percentage);
        item.put("from", from);
        item.put("to", to);
        item.put("details", details);
        item.put("description", "");
        return item;
    }

    /** Picks a random reference-data item, failing LOUD (not a skip) if the lookup is empty. */
    private static Map<String, Object> pickRequiredReferenceItem(Response response, String labelKey, String failureMessage) {
        Object body = response.jsonPath().getMap("$");
        Map<String, Object> item = JsonSearch.pickRandomObjectWithKeys(body, "uuid", labelKey);
        if (item == null) {
            throw new IllegalStateException(failureMessage);
        }
        return item;
    }

    /** Finds a specific reference item by its label value (e.g. courseType name='core'). */
    private static Map<String, Object> pickSpecificReferenceItem(Response response, String labelKey, String labelValue, String failureMessage) {
        Object body = response.jsonPath().getMap("$");
        Map<String, Object> item = JsonSearch.findObjectWithKeyValue(body, labelKey, labelValue, "uuid");
        if (item == null) {
            throw new IllegalStateException(failureMessage);
        }
        return item;
    }

    /**
     * GET read-back - confirms the course's persisted values match what was sent, including
     * the nested relation objects (CourseType/CourseMode/CourseClass/NpsDepartment) rather than
     * just the flat write-side ids, since the GET response nests them under relation keys.
     */
    private void verifyCourse(ScenarioContext context, String expectedName, String expectedCode,
                               int expectedCredit, int expectedHours, int expectedSessions,
                               Map<String, Object> courseType, Map<String, Object> courseMode,
                               Map<String, Object> courseClass, Map<String, Object> department) {
        Response getResponse = courseClient.getById(context.getAdminOrgId(), context.getCourseId(), context.getAdminToken());
        assertThat(getResponse.statusCode())
                .as("course read-back GET must succeed. Response: " + getResponse.getBody().asString())
                .isBetween(200, 299);

        Object body = getResponse.jsonPath().getMap("$");
        assertThat(JsonSearch.containsMatchingObject(body, "name", expectedName))
                .as("read-back course must carry the name we created (%s)", expectedName).isTrue();
        assertThat(JsonSearch.containsMatchingObject(body, "courseCode", expectedCode))
                .as("read-back course must carry the courseCode we created (%s)", expectedCode).isTrue();
        assertThat(getResponse.jsonPath().getInt("Data.credits")).isEqualTo(expectedCredit);
        assertThat(getResponse.jsonPath().getString("Data.hours")).isEqualTo(String.valueOf(expectedHours));
        assertThat(getResponse.jsonPath().getInt("Data.numberOfSessions")).isEqualTo(expectedSessions);
        assertThat(getResponse.jsonPath().getString("Data.CourseType.uuid")).isEqualTo(courseType.get("uuid"));
        assertThat(getResponse.jsonPath().getString("Data.CourseMode.uuid")).isEqualTo(courseMode.get("uuid"));
        assertThat(getResponse.jsonPath().getString("Data.CourseClass.uuid")).isEqualTo(courseClass.get("uuid"));
        assertThat(getResponse.jsonPath().getString("Data.NpsDepartment.uuid")).isEqualTo(department.get("uuid"));
    }

    /**
     * Attaches the created Course to the created Section (within the created Program Term), then
     * verifies the attachment with a GET read-back. Request/response shapes verified from a real
     * HAR capture (hawkeye-apis_2026-07-15_07-50-21.har) of the admin "add course to section"
     * flow - the earlier build guessed this and hit a 500; this replays the real 201 request.
     *
     * Real frontend sequence: POST .../programTerm/{ptId}/sectionCourse (201), then GET
     * .../programTerm/{ptId}/section/{sectionId} to refresh the section's course list. We do
     * exactly that and confirm our courseId appears in the section.
     *
     * courseArray item shape (the part the old guess got wrong - it omitted courseModeId/
     * courseClassId/subjectWeight/isSubjectWeightEnable and used sectionArray:[{id}] instead of
     * [{sectionId}]): every id comes from the current Environment Setup execution's ScenarioContext.
     */
    public void attachCourseToSection(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String sectionId = context.getSectionId();
        String courseId = context.getCourseId();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        String courseTypeId = context.getCourseTypeId();
        String courseTagId = context.getCourseTagId();
        String courseModeId = context.getCourseModeId();
        String courseClassId = context.getClassTypeId();

        assertThat(programTermId).as("programTermId must be set before attaching a course to a section").isNotBlank();
        assertThat(sectionId).as("sectionId must be set before attaching a course to a section").isNotBlank();
        assertThat(courseId).as("courseId must be set before attaching a course to a section").isNotBlank();
        assertThat(courseTypeId).as("courseTypeId must be set by the Create Course step before attaching").isNotBlank();
        assertThat(courseTagId).as("courseTagId must be set by the Create Course step (PATCH #1) before attaching").isNotBlank();
        assertThat(courseModeId).as("courseModeId must be set by the Create Course step before attaching").isNotBlank();
        assertThat(courseClassId).as("courseClassId (classTypeId) must be set by the Create Course step before attaching").isNotBlank();

        Object gradingTemplateId = context.hasAttribute("gradingTemplateId") ? context.getAttribute("gradingTemplateId") : null;
        assertThat(gradingTemplateId).as("gradingTemplateId must be available (set by the Cohort step) to attach a course").isNotNull();

        log.info("==============================\n"
                        + "Attaching Course\nCourse: {}\nProgram: {}\nCohort: {}\nProgram Term: {}\nSection: {}\n"
                        + "==============================",
                context.getCourseName(), context.getProgramName(), context.getCohortName(), programTermId, sectionId);

        // sectionArray item is {sectionId}; courseArray item carries the full course identity.
        // Verified from the HAR's 201 request - NOT the earlier {id}/3-field guess that 500'd.
        Map<String, Object> sectionRef = new HashMap<>();
        sectionRef.put("sectionId", sectionId);

        Map<String, Object> courseRef = new HashMap<>();
        courseRef.put("courseMasterId", courseId);
        courseRef.put("courseTypeId", courseTypeId);
        courseRef.put("courseTagId", courseTagId);
        courseRef.put("courseModeId", courseModeId);
        courseRef.put("courseClassId", courseClassId);
        courseRef.put("subjectWeight", "");
        courseRef.put("isSubjectWeightEnable", false);

        Map<String, Object> fields = new HashMap<>();
        fields.put("sectionArray", Collections.singletonList(sectionRef));
        fields.put("courseArray", Collections.singletonList(courseRef));
        fields.put("templateMasterId", gradingTemplateId);

        Response response = cohortClient.attachCourseToSection(programTermId, fields, createdBy, adminToken);
        assertThat(response.statusCode())
                .as("attach course to section must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        verifyCourseAttached(context, programTermId, sectionId, courseId);

        // Register the real sectionCourse mapping uuid the backend created (captured from the
        // verify GET) so a future CleanupManager handler can delete it; fall back to a composite
        // course:section key only if the backend didn't expose a mapping uuid.
        String mappingId = context.getSectionCourseId();
        context.registerCreated(ResourceType.SECTION_COURSE,
                mappingId != null ? mappingId : courseId + ":" + sectionId);

        EnvironmentSetupReport.checkpoint(context, "Course Assigned to Cohort",
                "Course: " + context.getCourseName(), "Section: " + sectionId,
                "Verified via section-detail GET");

        log.info("==============================\nAttaching Course\nCourse: {}\nSection: {}\nResult: SUCCESS\n==============================",
                context.getCourseName(), sectionId);
    }

    /**
     * Assigns the already-created Student to the already-created Program Term / Section, then
     * verifies the enrollment with a GET read-back. Request/response shapes verified from a real
     * HAR capture (hawkeye-apis_2026-07-16_10-03-18.har) of the admin "assign student to section"
     * flow: GET getSectionStudents -> GET getUnassignedUser -> POST enrollStudents -> GET
     * getSectionStudents (same order replayed here, no requests skipped or reordered).
     *
     * "Term 1 / Section 1" per the requirement means the first Program Term / Section belonging
     * to THIS Environment Setup execution's Cohort - which is exactly {@code context.getProgramTermId()}
     * / {@code context.getSectionId()}, since those are the only term/section this run created
     * (and the ones Course was already attached to, keeping the whole isolated environment
     * coherent). A fresh "list terms for cohort, take entry #1" lookup was deliberately NOT used:
     * a cohort's numberOfTerms setting auto-creates that many default terms as a side effect of
     * cohort creation (see createAndVerifyProgramTerm's Javadoc), so the positionally-first term
     * returned by a live list call can be one of those auto-generated defaults rather than the
     * term this run actually built (and attached the Course to) - reusing the context ids avoids
     * that mismatch entirely while still satisfying "never use another Program/Cohort's Term or
     * Section", since these ARE this execution's own resources.
     */
    public void enrollStudentInSection(ScenarioContext context) {
        String cohortId = context.getCohortId();
        String programTermId = context.getProgramTermId();
        String sectionId = context.getSectionId();
        String studentUuid = context.getStudentUuid();
        String studentEmail = context.execution().getStudentEmail();
        String createdBy = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(cohortId).as("cohortId must be set before assigning a student to a section").isNotBlank();
        assertThat(programTermId).as("programTermId must be set before assigning a student to a section").isNotBlank();
        assertThat(sectionId).as("sectionId must be set before assigning a student to a section").isNotBlank();
        assertThat(studentUuid).as("studentUuid must be set by the Create Student step before assigning to a section").isNotBlank();

        log.info("==============================\n"
                        + "Assigning Student to Section\nProgram: {}\nCohort: {}\nTerm: {}\nSection: {}\nStudent: {}\n"
                        + "==============================",
                context.getProgramName(), context.getCohortName(), programTermId, sectionId, studentEmail);

        // 1. GET section students (pre-state, matches the real UI's own call order - not
        // strictly needed for our verification logic, but replayed since the HAR shows the
        // frontend fires it before checking who's unassigned).
        Response preSectionStudents = cohortClient.getSectionStudents(programTermId, sectionId, adminToken);
        assertThat(preSectionStudents.statusCode())
                .as("pre-enrollment section-students GET must succeed. Response: " + preSectionStudents.getBody().asString())
                .isBetween(200, 299);

        // 2. GET unassigned users - verify our student is present and genuinely unassigned
        // (empty UserSections) BEFORE enrolling. Despite the endpoint's name, it returns EVERY
        // Program member annotated with their own section status, not a pre-filtered list - see
        // CohortClient#getUnassignedUsers's Javadoc. NOTE: the first path segment is the
        // COHORT/batch id, not the Program Master id - verified live (Program Master id gives
        // 400 "Program not found") and consistent with the HAR, where that segment's uuid
        // carries a batchName and is the same id getCohortDetails is keyed by.
        Response unassignedResponse = cohortClient.getUnassignedUsers(cohortId, programTermId, sectionId, adminToken);
        assertThat(unassignedResponse.statusCode())
                .as("getUnassignedUser GET must succeed. Response: " + unassignedResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> userPrograms = unassignedResponse.jsonPath().getList("Data.Program.UserPrograms");
        assertThat(userPrograms)
                .as("Data.Program.UserPrograms must be present in the getUnassignedUser response")
                .isNotNull();

        Map<String, Object> myUserProgram = null;
        for (Map<String, Object> up : userPrograms) {
            Object user = up.get("User");
            if (user instanceof Map && studentUuid.equals(((Map<?, ?>) user).get("uuid"))) {
                myUserProgram = up;
                break;
            }
        }
        assertThat(myUserProgram)
                .as("the created student %s must appear in the Program's member list before it can be assigned to a section", studentUuid)
                .isNotNull();
        Map<String, Object> myUser = (Map<String, Object>) myUserProgram.get("User");
        List<?> myUserSections = (List<?>) myUser.get("UserSections");
        assertThat(myUserSections)
                .as("the newly created student %s must be genuinely unassigned (no UserSections) before enrollment", studentUuid)
                .isNotNull().isEmpty();

        // 3. POST enrollStudents - {studentIds:[{id}], createdBy}, verified from the HAR's 201 request.
        Map<String, Object> studentRef = new HashMap<>();
        studentRef.put("id", studentUuid);
        Map<String, Object> enrollFields = new HashMap<>();
        enrollFields.put("studentIds", Collections.singletonList(studentRef));

        Response enrollResponse = cohortClient.enrollStudents(programTermId, sectionId, enrollFields, createdBy, adminToken);
        assertThat(enrollResponse.statusCode())
                .as("student enrollment must succeed. Response: " + enrollResponse.getBody().asString())
                .isBetween(200, 299);

        // 4. GET section students again - verify the student now appears, identity matches, and
        // capture the UserSections mapping uuid (only exposed here, never in the POST response).
        verifyStudentEnrolled(context, programTermId, sectionId, studentUuid, studentEmail);

        context.registerCreated(ResourceType.ENROLLMENT, context.getStudentEnrollmentUuid() != null
                ? context.getStudentEnrollmentUuid() : studentUuid + ":" + sectionId);

        // Transitively verified: Section was created scoped to this exact programTermId
        // (createAndVerifySection), and the just-passed getSectionStudents match confirms this
        // student is in that Section - so "assigned to this Program Term" follows from the
        // same already-verified fact, without an extra API call.
        EnvironmentSetupReport.checkpoint(context, "Student Assigned to First Program Term",
                "Program Term UUID: " + programTermId);
        EnvironmentSetupReport.checkpoint(context, "Student Assigned to First Section",
                "Section UUID: " + sectionId, "Verified via getSectionStudents GET");

        log.info("==============================\nAssigning Student to Section\nStudent: {}\nSection: {}\nResult: SUCCESS\n==============================",
                studentEmail, sectionId);

        // NOTE: the Environment Setup report is NOT published here. Student authentication is the
        // final step of Environment Setup (it produces the "ready" session every business workflow
        // consumes), so the summary is published only after StudentLoginWorkflow#loginStudent has
        // created and stored that session - see that method. This keeps "Environment Ready" meaning
        // "student created, assigned, AND authenticated with session stored".
    }

    /**
     * Read-back verification via getSectionStudents (not the POST response, which carries no
     * mapping id). Confirms the student's uuid AND email match, and that their UserSections
     * contains an entry whose Section.uuid equals our sectionId - proving both identity and
     * correct section linkage, not just "some enrollment happened somewhere." Captures the
     * UserSections entry's own uuid (the enrollment/mapping id) into ScenarioContext.
     */
    @SuppressWarnings("unchecked")
    private void verifyStudentEnrolled(ScenarioContext context, String programTermId, String sectionId,
                                        String studentUuid, String expectedEmail) {
        Response getResponse = cohortClient.getSectionStudents(programTermId, sectionId, context.getAdminToken());
        assertThat(getResponse.statusCode())
                .as("post-enrollment section-students GET must succeed. Response: " + getResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> students = getResponse.jsonPath().getList("Data");
        assertThat(students).as("section-students list must be present for verification").isNotNull();

        Map<String, Object> mine = null;
        for (Map<String, Object> entry : students) {
            if (studentUuid.equals(entry.get("uuid"))) {
                mine = entry;
                break;
            }
        }
        assertThat(mine)
                .as("the enrolled student %s must appear in section %s's roster after enrollment (verified via getSectionStudents GET)",
                        studentUuid, sectionId)
                .isNotNull();
        assertThat(mine.get("officialEmail"))
                .as("enrolled student's email must match the student created in this Environment Setup run")
                .isEqualTo(expectedEmail);

        List<Map<String, Object>> userSections = (List<Map<String, Object>>) mine.get("UserSections");
        assertThat(userSections).as("enrolled student must have at least one UserSections entry").isNotEmpty();

        Map<String, Object> mySectionLink = null;
        for (Map<String, Object> us : userSections) {
            Object section = us.get("Section");
            if (section instanceof Map && sectionId.equals(((Map<?, ?>) section).get("uuid"))) {
                mySectionLink = us;
                break;
            }
        }
        assertThat(mySectionLink)
                .as("enrolled student's UserSections must contain an entry for section %s (Environment Setup's own section)", sectionId)
                .isNotNull();

        Object mappingUuid = mySectionLink.get("uuid");
        if (mappingUuid != null) {
            context.setStudentEnrollmentUuid(mappingUuid.toString());
        }
    }

    /**
     * Confirms the course is really attached by reading the section back (the same GET the admin
     * UI fires after the attach) and matching our courseId against the section's course list. Also
     * captures the sectionCourse mapping uuid (the only new relationship id the backend returns,
     * and only via this GET, not the POST) into ScenarioContext. Fails immediately if our course
     * is not present in the section.
     */
    private void verifyCourseAttached(ScenarioContext context, String programTermId, String sectionId, String courseId) {
        Response getResponse = cohortClient.getSectionDetail(programTermId, sectionId, context.getAdminToken());
        assertThat(getResponse.statusCode())
                .as("section detail read-back must succeed. Response: " + getResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> attachedCourses = getResponse.jsonPath().getList("Data.data");
        assertThat(attachedCourses)
                .as("section must expose its attached-course list (Data.data) for verification")
                .isNotNull();

        Map<String, Object> mine = null;
        for (Map<String, Object> entry : attachedCourses) {
            Object courseMaster = entry.get("CourseMaster");
            if (courseMaster instanceof Map && courseId.equals(((Map<?, ?>) courseMaster).get("uuid"))) {
                mine = entry;
                break;
            }
        }
        assertThat(mine)
                .as("the created course %s must appear in section %s after attaching (verified via section-detail GET)", courseId, sectionId)
                .isNotNull();

        Object mappingUuid = mine.get("uuid");
        if (mappingUuid != null) {
            context.setSectionCourseId(mappingUuid.toString());
        }
    }

    /**
     * Read-back verification - never trusts the POST's success alone. Fetches the student and
     * confirms the identity fields we sent. Program/cohort/org linkage is asserted from context
     * (correct by construction - the ids came from the just-created resources) and confirmed
     * from the read-back only if the response echoes them, since the user-fetch response shape
     * isn't fully verified yet ("verify as much information as is available").
     */
    private void verifyStudent(ScenarioContext context, String expectedFirst, String expectedLast, String expectedEmail) {
        Response getResponse = userClient.getStudentById(
                context.getAdminOrgId(), context.getStudentUuid(), context.getAdminToken());

        assertThat(getResponse.statusCode())
                .as("student read-back GET must succeed. Response: " + getResponse.getBody().asString())
                .isBetween(200, 299);

        Object body = getResponse.jsonPath().getMap("$");
        assertThat(JsonSearch.containsMatchingObject(body, "officialEmail", expectedEmail))
                .as("read-back student must carry the email we created (%s)", expectedEmail).isTrue();
        assertThat(JsonSearch.containsMatchingObject(body, "firstName", expectedFirst))
                .as("read-back student must carry the firstName we created (%s)", expectedFirst).isTrue();
        assertThat(JsonSearch.containsMatchingObject(body, "lastName", expectedLast))
                .as("read-back student must carry the lastName we created (%s)", expectedLast).isTrue();

        // Program/cohort/org are correct by construction (ids sourced from the created
        // resources); assert they are present in context so the Student Login step can rely on them.
        assertThat(context.getStudentProgramId()).as("student must be linked to the created program").isNotBlank();
        assertThat(context.getStudentCohortId()).as("student must be linked to the created cohort").isNotBlank();
    }
}
