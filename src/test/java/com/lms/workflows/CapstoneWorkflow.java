package com.lms.workflows;

import com.lms.cleanup.ResourceType;
import com.lms.clients.admin.CapstoneClient;
import com.lms.clients.admin.CohortClient;
import com.lms.clients.admin.ReferenceDataClient;
import com.lms.clients.student.StudentCapstoneClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.CapstoneJourneyReport;
import com.lms.utils.DateUtils;
import com.lms.utils.JsonSearch;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Capstone Mini-Setup stage - the journey that reuses the
 * suite-scoped runtime environment {@code EnvironmentSetup} provisioned (Program -> Cohort ->
 * Program Term -> Section -> Course -> Student, plus the authenticated admin) and adds a CAPSTONE
 * Outclass Section beside the runtime section, inside the SAME Program Term. It provisions nothing
 * else: no Program, Cohort, Program Term, normal Section, CourseMaster or Student is created here.
 *
 * <p>The correctness risk this class is written against is the four entities that all read like "the
 * course":
 * <ul>
 *   <li><b>CourseMaster</b> ({@code courseId}) - the org-level course definition, reused as-is;</li>
 *   <li><b>TermCourse</b> - a CourseMaster's instance inside ONE section, so the capstone section
 *       yields its own {@code capstoneTermCourseId}, distinct from the runtime {@code termCourseId}
 *       the Assignment / Session / Attendance journeys depend on;</li>
 *   <li><b>SectionCourse mapping</b> - the join record the section-courses read returns;</li>
 *   <li><b>Section</b> - {@code capstoneSectionId} here, never the Environment Setup
 *       {@code sectionId}.</li>
 * </ul>
 * Nothing in this class writes {@code sectionId}, {@code termCourseId} or {@code sectionCourseId}:
 * it calls no setter for them, which is what keeps the earlier journeys passing on the same run.
 *
 * <p>Each business action is its own method with its own assertions and its own reporting checkpoint
 * (mirrored 1:1 by a Gherkin step in Capstone.feature) rather than one large method - so a failure
 * always points at the exact business action that failed. Every state change is confirmed by a
 * separate read whose payload is asserted; an HTTP 2xx is never treated as evidence on its own.
 *
 * <p>Holds a {@link CohortClient}, a {@link ReferenceDataClient} and a {@link CapstoneClient}, and no
 * mutable instance state - every id lives in {@code ExecutionContext}, so nothing can leak between
 * scenarios. No token, refresh token, password or {@code Authorization} value is interpolated into any
 * log line, report line or assertion message.
 *
 * <p><b>S2 (Configure Capstone) - ONE aggregate POST behind SIX business steps.</b> Read this before
 * concluding a call is missing. The capstone gamification configuration endpoint
 * ({@code POST /capstoneGamifications}) is an AGGREGATE: metrics, the metrics-builder grid, the
 * dashboard display, the analytics display, the leaderboard formula, the resources and the schedule are
 * all persisted in ONE request, and the HAR contains no per-section PUT/PATCH. The journey still needs
 * six individually meaningful steps in the report, so the six {@code configureCapstone*} methods for
 * metrics / metrics-builder / dashboard / analytics / leaderboard / resources work like this:
 * <ul>
 *   <li>whichever of them runs FIRST builds the COMPLETE payload, fires the single POST, and stores the
 *       configuration uuid + numeric id in {@code ExecutionContext} (and the request/response bodies in
 *       the per-scenario attribute bag);</li>
 *   <li>each of them - including the first - then asserts that its OWN slice of the aggregate is present
 *       and correct in what was actually persisted (the configuration response, falling back to a
 *       termCourse read-back when the response does not echo that slice) and records its own
 *       checkpoint.</li>
 * </ul>
 * So there is exactly one configuration write no matter how many of the six steps run, each step owns a
 * disjoint slice of the payload, and a failing step names the slice that did not land.
 * {@link #configureCapstoneRewards(ScenarioContext)} is the one genuinely separate step: rewards are
 * their own API, so it performs two real calls of its own.
 *
 * <p>NO CLEANUP REGISTRATION exists for the gamification configuration or the reward, and that is
 * deliberate: the HAR contains no DELETE for either resource, so no delete endpoint can be introduced
 * without inventing one. Teardown of both is expected to happen transitively with the capstone
 * TermCourse / runtime Cohort, and that cascade is UNVERIFIED - a documented gap, not an oversight.
 */
public class CapstoneWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CapstoneWorkflow.class);

    private final CohortClient cohortClient;
    private final ReferenceDataClient referenceDataClient;
    private final CapstoneClient capstoneClient;
    /**
     * The one STUDENT-token client this workflow holds. Separate from {@link CapstoneClient} - which is
     * admin-scoped - because S3's last read is issued as the runtime student, and the two actors' calls must
     * not share a client that could reach for the wrong credential. The token is always passed in per call.
     */
    private final StudentCapstoneClient studentCapstoneClient;

    public CapstoneWorkflow() {
        this.cohortClient = new CohortClient();
        this.referenceDataClient = new ReferenceDataClient();
        this.capstoneClient = new CapstoneClient();
        this.studentCapstoneClient = new StudentCapstoneClient();
    }

    // ================= Stage 0: the precondition guard =================

    /**
     * Asserts the suite-scoped runtime environment this stage consumes is fully provisioned BEFORE any
     * request is issued. Delegates to {@code ExecutionContext.requireEnvironmentReady()}, whose failure
     * message already names {@code EnvironmentSetup.feature} as the missing prerequisite, then checks
     * each individual id the stage reads so a partially-populated context names the blank id rather
     * than failing later inside a request.
     *
     * <p>Deliberately makes NO term-state read and NO {@code isActiveTerm} assertion: the capstone
     * section is created inside the Program Term the runtime already has, whatever its active flag
     * says. This method issues no HTTP request at all.
     *
     * <p>Deliberately does NOT require {@code termCourseId}. That id is resolved by
     * {@code AssignmentWorkflow.locateRuntimeTermCourse}, not by Environment Setup, so requiring it here
     * would make {@code @capstone} silently depend on {@code @assignment} running first in the same suite.
     * The capstone stage addresses no request to the runtime TermCourse, so its absence is not a missing
     * precondition - it only downgrades one optional distinctness assertion in
     * {@link #verifyRuntimeCourseInCapstoneSection}.
     */
    public void requireRuntimeEnvironmentReady(ScenarioContext context) {
        context.execution().requireEnvironmentReady();

        assertThat(context.getCohortId()).as("cohortId must be set by Environment Setup").isNotBlank();
        assertThat(context.getProgramTermId()).as("programTermId must be set by Environment Setup").isNotBlank();
        assertThat(context.getCourseId()).as("courseId must be set by Environment Setup").isNotBlank();
        assertThat(context.getStudentUuid()).as("studentUuid must be set by Environment Setup").isNotBlank();
        assertThat(context.getSectionId()).as("sectionId must be set by Environment Setup").isNotBlank();
        assertThat(context.getAdminUserId()).as("adminUserId must be set by admin login").isNotBlank();
        assertThat(context.getAdminOrgId()).as("adminOrgId must be set by admin login").isNotBlank();
        assertThat(context.getAdminToken()).as("the admin session must be authenticated by Environment Setup").isNotBlank();

        log.info("==============================\nCapstone Mini-Setup\nCohort: {}\nProgram Term: {}\n"
                        + "Runtime Section: {}\nRuntime Course: {}\nRuntime Student: {}\n"
                        + "Result: runtime environment ready\n==============================",
                context.getCohortId(), context.getProgramTermId(), context.getSectionId(),
                context.getCourseId(), context.getStudentUuid());
    }

    // ================= Stage 1: the capstone outclass section =================

    /**
     * Creates the ONE Capstone Outclass Section this stage adds, inside the runtime Program Term. The
     * request body carries only {@code programTermId} and {@code isCapstoneSection} - the client stamps
     * {@code createdBy} - and no name: the section name is BACKEND-generated and captured from the
     * response, never composed here.
     *
     * <p>Asserts 201, asserts the created section reports {@code isCapstoneSection}, captures
     * {@code Data.uuid} / {@code Data.name} into the suite-scoped context, and asserts the new id
     * differs from the Environment Setup {@code sectionId} - the capstone section must be a NEW
     * section, not the runtime one.
     *
     * <p>Asserts nothing about {@code Data.programTermId}, {@code Data.programId} or
     * {@code Data.createdBy}: those are numeric internal identifiers in a different id space from the
     * uuids this framework carries, so a comparison against a uuid would be meaningless rather than
     * merely redundant. Program Term ownership is instead proven later, by the termCourse detail read.
     */
    public void createCapstoneOutclassSection(ScenarioContext context) {
        String cohortId = context.getCohortId();
        String programTermId = context.getProgramTermId();
        String runtimeSectionId = context.getSectionId();

        log.info("==============================\nCreating Capstone Outclass Section\nCohort: {}\n"
                        + "Program Term: {}\nRuntime Section (must stay untouched): {}\n==============================",
                cohortId, programTermId, runtimeSectionId);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("programTermId", programTermId);
        fields.put("isCapstoneSection", true);

        Response response = cohortClient.createTermSection(cohortId, fields, context.getAdminUserId(),
                context.getAdminToken());
        String body = response.getBody().asString();

        assertThat(response.statusCode())
                .as("capstone section creation must return 201. Response: " + body)
                .isEqualTo(201);
        assertThat(response.jsonPath().getBoolean("Data.isCapstoneSection"))
                .as("the created section must report isCapstoneSection - a section created without the capstone "
                        + "flag is an ordinary section. Response: " + body)
                .isTrue();

        String capstoneSectionId = response.jsonPath().getString("Data.uuid");
        String capstoneSectionName = response.jsonPath().getString("Data.name");

        assertThat(capstoneSectionId)
                .as("capstone section id must be present in the create response (Data.uuid). Response: " + body)
                .isNotBlank();
        assertThat(capstoneSectionId)
                .as("the capstone section %s must be a NEW section, distinct from the runtime section %s that "
                        + "Environment Setup provisioned", capstoneSectionId, runtimeSectionId)
                .isNotEqualTo(runtimeSectionId);

        context.setCapstoneSectionId(capstoneSectionId);
        context.setCapstoneSectionName(capstoneSectionName);

        // Cleanup registration #1 of the stage's exactly two, appended AFTER the create assertions pass and
        // after everything Environment Setup registered, so the append-only suite registry stays in creation
        // order (the SECTION_COURSE mapping registered in verifyRuntimeCourseInCapstoneSection is #2).
        // CleanupManager is deliberately NOT modified: it deletes COHORT and PROGRAM and records a logged
        // skip for every other type, so this entry becomes a logged skip and no delete endpoint for sections
        // is introduced. KNOWN UNCERTAINTY 2 (open by design): teardown of this section is expected to happen
        // transitively via deletion of the runtime Cohort, and that cascade - to the section, its enrollment,
        // its section-course mapping and its TermCourse - is UNVERIFIED. Nothing here asserts cascade
        // behaviour in either direction.
        context.registerCreated(ResourceType.SECTION, capstoneSectionId);

        CapstoneJourneyReport.checkpoint(context, "Capstone Outclass Section Created",
                "Capstone Section: " + capstoneSectionName,
                "Capstone Section UUID: " + capstoneSectionId,
                "Distinct from the runtime section (" + runtimeSectionId + ")");

        log.info("==============================\nCreating Capstone Outclass Section\nName: {}\nUUID: {}\n"
                        + "Result: SUCCESS (isCapstoneSection confirmed, distinct from the runtime section)\n"
                        + "==============================",
                capstoneSectionName, capstoneSectionId);
    }

    // ================= Stage 2: the runtime student joins the capstone section =================

    /**
     * Enrolls the EXISTING runtime student into the capstone section, replaying the admin UI's own
     * three-call order through the existing, unmodified {@code CohortClient} operations:
     * {@code getSectionStudents} (pre-state) -> {@code getUnassignedUsers} (membership + guard) ->
     * {@code enrollStudents}. No student is created here, and the only enrollment target in the whole
     * stage is {@code capstoneSectionId} - no request is addressed to the Environment Setup
     * {@code sectionId}, so the normal section's roster is left exactly as Environment Setup left it.
     *
     * <p>The pre-state read asserts its status only. Nothing is asserted about the pre-enrollment
     * roster contents: proof of the enrollment is the read-back in
     * {@link #verifyRuntimeStudentInCapstoneSection(ScenarioContext)}, not this call.
     *
     * <p>A 2xx on the enrollment POST is not treated as evidence - the verification step is separate
     * and is what records the checkpoint.
     */
    @SuppressWarnings("unchecked")
    public void assignRuntimeStudentToCapstoneSection(ScenarioContext context) {
        String cohortId = context.getCohortId();
        String programTermId = context.getProgramTermId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String studentUuid = context.getStudentUuid();
        String adminToken = context.getAdminToken();

        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by the capstone section creation step before enrollment")
                .isNotBlank();

        log.info("==============================\nAssigning Runtime Student to Capstone Section\n"
                        + "Cohort: {}\nProgram Term: {}\nCapstone Section: {}\nRuntime Student: {}\n"
                        + "Runtime Section (must stay untouched): {}\n==============================",
                cohortId, programTermId, capstoneSectionId, studentUuid, context.getSectionId());

        // 1. Pre-state roster read - the admin UI fires it before checking who is unassigned, so it is
        // replayed in the same position. Status only: the pre-enrollment roster contents are not the
        // subject of any assertion here.
        Response preRoster = cohortClient.getSectionStudents(programTermId, capstoneSectionId, adminToken);
        assertThat(preRoster.statusCode())
                .as("pre-enrollment capstone roster read must succeed. Response: " + preRoster.getBody().asString())
                .isBetween(200, 299);

        // 2. Program membership + the "not already in the capstone section" guard. Despite its name,
        // this endpoint returns EVERY Program member annotated with their own UserSections, so the
        // caller does the matching.
        Response unassignedResponse = cohortClient.getUnassignedUsers(cohortId, programTermId, capstoneSectionId, adminToken);
        assertThat(unassignedResponse.statusCode())
                .as("unassigned-users read must succeed before capstone enrollment. Response: "
                        + unassignedResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> userPrograms = unassignedResponse.jsonPath().getList("Data.Program.UserPrograms");
        assertThat(userPrograms)
                .as("the Program member list must be present in the unassigned-users response before capstone enrollment")
                .isNotNull();

        Map<String, Object> runtimeUserProgram = null;
        for (Map<String, Object> userProgram : userPrograms) {
            Object user = userProgram.get("User");
            if (user instanceof Map && studentUuid.equals(((Map<?, ?>) user).get("uuid"))) {
                runtimeUserProgram = userProgram;
                break;
            }
        }
        assertThat(runtimeUserProgram)
                .as("the runtime student %s must be a member of the runtime Program before enrollment into the "
                        + "capstone section", studentUuid)
                .isNotNull();

        // WEAKENED GUARD - deliberately NOT Bootstrap's check, and this is the single most important
        // detail in this method. BootstrapWorkflow.enrollStudentInSection asserts the student's
        // UserSections is EMPTY, which holds only for a brand-new student. By the time this stage runs
        // the runtime student is ALREADY enrolled in the Normal Section, so UserSections is non-empty
        // BY DESIGN and the Bootstrap guard would fail on a perfectly correct environment. The correct,
        // weaker condition asserted here is: the student is a Program member (above) AND no UserSections
        // entry points at THIS capstone section - i.e. not already in the capstone section. Membership
        // in other sections is expected and must not fail the guard.
        Map<String, Object> runtimeUser = (Map<String, Object>) runtimeUserProgram.get("User");
        List<Map<String, Object>> userSections = (List<Map<String, Object>>) runtimeUser.get("UserSections");
        boolean alreadyInCapstoneSection = false;
        if (userSections != null) {
            for (Map<String, Object> userSection : userSections) {
                Object section = userSection.get("Section");
                if (section instanceof Map && capstoneSectionId.equals(((Map<?, ?>) section).get("uuid"))) {
                    alreadyInCapstoneSection = true;
                    break;
                }
            }
        }
        assertThat(alreadyInCapstoneSection)
                .as("the runtime student %s must not already be a member of the capstone section %s before this "
                        + "enrollment (membership in the runtime section is expected and is not a failure)",
                        studentUuid, capstoneSectionId)
                .isFalse();

        // 3. Enroll - the client stamps createdBy, producing {studentIds:[{id}], createdBy}.
        Map<String, Object> studentRef = new LinkedHashMap<>();
        studentRef.put("id", studentUuid);
        Map<String, Object> enrollFields = new LinkedHashMap<>();
        enrollFields.put("studentIds", Collections.singletonList(studentRef));

        Response enrollResponse = cohortClient.enrollStudents(programTermId, capstoneSectionId, enrollFields,
                context.getAdminUserId(), adminToken);
        assertThat(enrollResponse.statusCode())
                .as("capstone section enrollment of the runtime student %s must succeed. Response: %s",
                        studentUuid, enrollResponse.getBody().asString())
                .isBetween(200, 299);

        log.info("==============================\nAssigning Runtime Student to Capstone Section\nStudent: {}\n"
                        + "Capstone Section: {}\nResult: enrollment accepted (pending roster read-back)\n"
                        + "==============================",
                studentUuid, capstoneSectionId);
    }

    /**
     * Read-back verification of the capstone enrollment: reads the capstone section's roster and
     * asserts an entry whose student uuid equals the runtime {@code studentUuid} is present. This -
     * not the enrollment POST's 2xx - is the evidence the enrollment landed, so the checkpoint is
     * recorded only after this assertion passes. On failure the message reports how many roster
     * entries were actually found, which distinguishes "empty roster" from "roster holds someone else".
     */
    public void verifyRuntimeStudentInCapstoneSection(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String studentUuid = context.getStudentUuid();

        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured before the capstone roster can be verified")
                .isNotBlank();

        Response rosterResponse = cohortClient.getSectionStudents(programTermId, capstoneSectionId,
                context.getAdminToken());
        assertThat(rosterResponse.statusCode())
                .as("post-enrollment capstone roster read must succeed. Response: " + rosterResponse.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> roster = rosterResponse.jsonPath().getList("Data");
        assertThat(roster)
                .as("the capstone section roster list must be present to verify the runtime student's enrollment")
                .isNotNull();

        Map<String, Object> runtimeStudentEntry = null;
        for (Map<String, Object> entry : roster) {
            if (studentUuid.equals(entry.get("uuid"))) {
                runtimeStudentEntry = entry;
                break;
            }
        }
        assertThat(runtimeStudentEntry)
                .as("the runtime student %s must appear in the capstone section %s roster read-back; %d roster "
                        + "entries were found", studentUuid, capstoneSectionId, roster.size())
                .isNotNull();

        CapstoneJourneyReport.checkpoint(context, "Runtime Student Added to Capstone Section",
                "Student UUID: " + studentUuid,
                "Verified via section roster read-back");

        log.info("==============================\nVerifying Runtime Student in Capstone Section\nStudent: {}\n"
                        + "Capstone Section: {}\nRoster Entries: {}\nResult: SUCCESS (verified via section roster "
                        + "read-back)\n==============================",
                studentUuid, capstoneSectionId, roster.size());
    }

    // ================= Stage 3: the runtime course joins the capstone section =================

    /**
     * Attaches the EXISTING runtime CourseMaster ({@code courseId}) to the capstone section, through the
     * existing, unmodified {@code CohortClient.attachCourseToSection}. No CourseMaster is created here -
     * the same org-level course definition is simply attached to a second section, which is exactly what
     * produces the capstone section's own TermCourse instance.
     *
     * <p>WHY {@code templateMasterId} IS RESOLVED AT RUNTIME instead of being read from the context:
     * {@code BootstrapSteps} parks the id it resolves in {@code ScenarioContext}'s ATTRIBUTE BAG
     * ({@code putAttribute("gradingTemplateId", ...)}), and that bag is PER-SCENARIO - unlike the entity
     * accessors, which delegate to the suite-scoped {@code ExecutionContext}. Environment Setup is a
     * different scenario, so by the time this scenario runs the bag is empty and reading it would yield
     * null. The id is therefore re-resolved here with the same {@code listGradingTemplates} +
     * {@code JsonSearch.findFirstValue} pair {@code BootstrapSteps} uses. Nothing grading-template shaped
     * is written to {@code ExecutionContext}, and {@code BootstrapSteps} is left unchanged.
     *
     * <p>The body mirrors the HAR-verified shape {@code BootstrapWorkflow} sends, with the capstone
     * section id substituted for the runtime one: {@code sectionArray:[{sectionId}]} (never
     * {@code [{id}]}) and a {@code courseArray} item carrying the full course identity. The four lookup
     * ids come from {@code ExecutionContext}, where Environment Setup left them - note
     * {@code courseClassId} is carried as {@code classTypeId}.
     *
     * <p>A 2xx here is not treated as evidence: the read-back that both confirms the attachment and
     * resolves the capstone TermCourse is a separate step, which is also where the checkpoint is
     * recorded.
     */
    public void addRuntimeCourseToCapstoneSection(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String courseId = context.getCourseId();
        String adminOrgId = context.getAdminOrgId();
        String adminToken = context.getAdminToken();

        String courseTypeId = context.getCourseTypeId();
        String courseTagId = context.getCourseTagId();
        String courseModeId = context.getCourseModeId();
        String courseClassId = context.getClassTypeId();

        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by the capstone section creation step before the runtime "
                        + "course can be attached")
                .isNotBlank();
        assertThat(programTermId)
                .as("programTermId must be set by Environment Setup before attaching the runtime course to the "
                        + "capstone section")
                .isNotBlank();
        assertThat(courseId)
                .as("courseId must be set by Environment Setup - the capstone stage attaches the EXISTING runtime "
                        + "course and creates none")
                .isNotBlank();
        assertThat(courseTypeId)
                .as("courseTypeId must be set by Environment Setup before attaching the runtime course").isNotBlank();
        assertThat(courseTagId)
                .as("courseTagId must be set by Environment Setup before attaching the runtime course").isNotBlank();
        assertThat(courseModeId)
                .as("courseModeId must be set by Environment Setup before attaching the runtime course").isNotBlank();
        assertThat(courseClassId)
                .as("courseClassId (carried as classTypeId) must be set by Environment Setup before attaching the "
                        + "runtime course")
                .isNotBlank();

        Response templatesResponse = referenceDataClient.listGradingTemplates(adminOrgId, adminToken);
        assertThat(templatesResponse.statusCode())
                .as("grading template read must succeed before the capstone attach body can be built. Response: "
                        + templatesResponse.getBody().asString())
                .isBetween(200, 299);

        Object templateMasterId = JsonSearch.findFirstValue(templatesResponse.jsonPath().getMap("$"), "uuid");
        assertThat(templateMasterId)
                .as("no grading template is available for the runtime organization - seed at least one")
                .isNotNull();

        log.info("==============================\nAdding Runtime Course to Capstone Section\nProgram Term: {}\n"
                        + "Capstone Section: {}\nRuntime Course: {}\n"
                        + "Runtime Section (must stay untouched): {}\n==============================",
                programTermId, capstoneSectionId, courseId, context.getSectionId());

        Map<String, Object> sectionRef = new LinkedHashMap<>();
        sectionRef.put("sectionId", capstoneSectionId);

        Map<String, Object> courseRef = new LinkedHashMap<>();
        courseRef.put("courseMasterId", courseId);
        courseRef.put("courseTypeId", courseTypeId);
        courseRef.put("courseTagId", courseTagId);
        courseRef.put("courseModeId", courseModeId);
        courseRef.put("courseClassId", courseClassId);
        courseRef.put("subjectWeight", "");
        courseRef.put("isSubjectWeightEnable", false);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sectionArray", Collections.singletonList(sectionRef));
        fields.put("courseArray", Collections.singletonList(courseRef));
        fields.put("templateMasterId", templateMasterId);

        Response response = cohortClient.attachCourseToSection(programTermId, fields, context.getAdminUserId(),
                adminToken);
        assertThat(response.statusCode())
                .as("attaching the runtime course %s to the capstone section %s must succeed. Response: %s",
                        courseId, capstoneSectionId, response.getBody().asString())
                .isBetween(200, 299);

        // Deliberately NOT BootstrapWorkflow.verifyCourseAttached: that method writes the mapping uuid it
        // finds into setSectionCourseId(...), which delegates to the suite-scoped ExecutionContext and
        // would OVERWRITE the normal section's mapping id that Environment Setup stored. The capstone
        // read-back therefore has its own resolution path, in the following verification step, and no
        // checkpoint is recorded here - a 2xx alone is not evidence the attachment landed.

        log.info("==============================\nAdding Runtime Course to Capstone Section\nCourse: {}\n"
                        + "Capstone Section: {}\nResult: attachment accepted (pending section-courses read-back)\n"
                        + "==============================",
                courseId, capstoneSectionId);
    }

    /**
     * Read-back verification of the capstone course attachment AND the single point at which the capstone
     * TermCourse id is resolved. Reads the capstone section's section-courses projection, matches the
     * mapping for the runtime CourseMaster, and captures that mapping's {@code uuid} - which IS the
     * capstone TermCourse id - into the suite-scoped context.
     *
     * <p>{@code capstoneTermCourseId} is resolved EXACTLY ONCE, here. The gamification update and its
     * verification both reuse the stored value and re-derive nothing, so there is no way for the stage to
     * end up configuring one TermCourse and verifying another.
     *
     * <p>Two assertions always carry the business meaning: the matched mapping must carry its own
     * {@code uuid}, and that mapping's {@code Section.uuid} must be the capstone section (the projection
     * returned the section we asked about). A THIRD assertion is CONDITIONAL: when the runtime
     * {@code termCourseId} is available, the captured id must DIFFER from it - a CourseMaster attached to a
     * second section yields a second, independent TermCourse, and that distinctness is precisely what lets
     * gamification be enabled on the capstone course without touching the runtime one. The runtime
     * {@code termCourseId} originates from {@code AssignmentWorkflow.locateRuntimeTermCourse}, NOT from
     * Environment Setup, so it is simply absent when {@code @capstone} runs without {@code @assignment};
     * the distinctness check is then logged as skipped rather than failing a correct run.
     *
     * <p>Deliberately calls NO setter for {@code sectionCourseId} and neither
     * {@code BootstrapWorkflow.verifyCourseAttached} nor {@code getSectionDetail}:
     * {@code verifyCourseAttached} writes the mapping uuid it finds into {@code setSectionCourseId(...)},
     * which delegates to the suite-scoped {@code ExecutionContext} and would overwrite the normal
     * section's mapping id Environment Setup stored. That is why this stage has its own resolution path
     * rather than a workaround applied afterwards.
     */
    public void verifyRuntimeCourseInCapstoneSection(ScenarioContext context) {
        String capstoneSectionId = context.getCapstoneSectionId();
        String courseId = context.getCourseId();
        String runtimeTermCourseId = context.getTermCourseId();

        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured before the capstone section-courses read-back can run")
                .isNotBlank();
        assertThat(courseId)
                .as("courseId must be set by Environment Setup - it is what the capstone section-course mapping "
                        + "is matched by")
                .isNotBlank();

        // Display-only label for the log lines below: the runtime TermCourse id is resolved by the
        // Assignment journey, so it is legitimately absent here and would otherwise render as "null".
        // Assertions keep using the raw value - only the logs use this label.
        String runtimeTermCourseLabel = runtimeTermCourseId == null || runtimeTermCourseId.isBlank()
                ? "not resolved in this run"
                : runtimeTermCourseId;

        log.info("==============================\nVerifying Runtime Course in Capstone Section\n"
                        + "Capstone Section: {}\nRuntime Course (CourseMaster): {}\n"
                        + "Runtime TermCourse (must stay untouched): {}\n==============================",
                capstoneSectionId, courseId, runtimeTermCourseLabel);

        Response response = cohortClient.getSectionCourses(capstoneSectionId, context.getAdminToken());
        assertThat(response.statusCode())
                .as("capstone section-courses read must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> mapping = findSectionCourseByCourseMaster(response, courseId);

        Object mappingUuid = mapping.get("uuid");
        assertThat(mappingUuid == null ? null : mappingUuid.toString())
                .as("the matched capstone section-course mapping must carry its own uuid - that value IS the "
                        + "capstone TermCourse id. Matched mapping: " + mapping)
                .isNotBlank();
        String capstoneTermCourseId = String.valueOf(mappingUuid);

        Object section = mapping.get("Section");
        String mappedSectionId = section instanceof Map
                ? String.valueOf(((Map<?, ?>) section).get("uuid"))
                : null;
        assertThat(mappedSectionId)
                .as("the capstone section-courses read for section %s returned a matched mapping whose Section is "
                        + "%s - the mapping the capstone TermCourse is taken from must belong to the capstone section",
                        capstoneSectionId, mappedSectionId)
                .isEqualTo(capstoneSectionId);

        // CONDITIONAL on purpose: the runtime termCourseId is resolved by AssignmentWorkflow, not by
        // Environment Setup, so it is absent whenever @capstone runs standalone. Distinctness is a BONUS
        // verification of an invariant the backend owns, never a precondition of this stage - no request
        // here is addressed to the runtime TermCourse - so its absence must not fail a correct run.
        if (runtimeTermCourseId != null && !runtimeTermCourseId.isBlank()) {
            assertThat(capstoneTermCourseId)
                    .as("the capstone TermCourse %s must be a DISTINCT instance from the normal TermCourse %s: the same "
                            + "CourseMaster attached to a second section yields its own TermCourse, and only that "
                            + "distinctness lets the capstone course be configured without touching the runtime one",
                            capstoneTermCourseId, runtimeTermCourseId)
                    .isNotEqualTo(runtimeTermCourseId);
        } else {
            log.info("==============================\nVerifying Runtime Course in Capstone Section\n"
                    + "Runtime TermCourse: not resolved in this run (it is set by the Assignment journey, not by "
                    + "Environment Setup)\nResult: the capstone-vs-runtime TermCourse distinctness check was SKIPPED\n"
                    + "Note: the capstone stage needs no runtime TermCourse id for any of its requests\n"
                    + "==============================");
        }

        // Resolved ONCE, here. Every later capstone step reads this stored value back and re-derives nothing.
        context.setCapstoneTermCourseId(capstoneTermCourseId);

        Object courseMaster = mapping.get("CourseMaster");
        String courseName = courseMaster instanceof Map
                ? String.valueOf(((Map<?, ?>) courseMaster).get("name"))
                : null;

        CapstoneJourneyReport.checkpoint(context, "Runtime Course Added to Capstone Section",
                "Course: " + courseName,
                "Capstone TermCourse UUID: " + capstoneTermCourseId,
                "Matched by CourseMaster uuid");

        // Cleanup registration #2 of the stage's exactly two, appended AFTER the mapping is verified so the
        // append-only suite registry holds the capstone entries in creation order (SECTION first, from
        // createCapstoneOutclassSection, then this mapping). The deletion context is METADATA ONLY - the ids a
        // future delete handler would need, mirroring how the Attendance journey carries its own forward - and
        // defines no deletion behaviour. NO enrollment entry is registered: the requirements name exactly two
        // registrations, and inventing a third would put an unverifiable id class into the registry.
        // CleanupManager is deliberately NOT modified, so this entry becomes a logged skip and no delete
        // endpoint for section-course mappings or term courses is introduced. KNOWN UNCERTAINTY 2 (open by
        // design): teardown is expected to happen transitively via deletion of the runtime Cohort, and that
        // cascade - to the capstone section, its enrollment, this mapping and its TermCourse - is UNVERIFIED;
        // nothing here asserts cascade behaviour in either direction.
        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("sectionId", capstoneSectionId);
        deletionContext.put("courseMasterId", courseId);
        context.registerCreated(ResourceType.SECTION_COURSE, capstoneTermCourseId, deletionContext);

        log.info("==============================\nVerifying Runtime Course in Capstone Section\nCourse: {}\n"
                        + "Capstone Section: {}\nCapstone TermCourse: {}\n"
                        + "Result: SUCCESS (matched by CourseMaster uuid, distinct from the runtime TermCourse {})\n"
                        + "==============================",
                courseName, capstoneSectionId, capstoneTermCourseId, runtimeTermCourseLabel);
    }

    /**
     * Selects the section-course mapping entry for one CourseMaster out of a section-courses response.
     * Matching is on {@code CourseMaster.uuid} ONLY - never on course name, which is neither unique nor
     * stable and would let a same-named course from a different definition satisfy the match.
     *
     * <p>Shared on purpose by the capstone TermCourse resolution and by the gamification verification's
     * second read, so those two can never drift into matching the mapping by different criteria.
     *
     * <p>On no match the failure reports the expected uuid, every {@code CourseMaster.uuid} actually
     * present in the payload and the reported {@code Count} - enough to tell "the attach did not land"
     * apart from "the attach landed on a different course" or "the projection is paginated past it",
     * rather than a bare "not found".
     */
    private Map<String, Object> findSectionCourseByCourseMaster(Response sectionCourses, String courseId) {
        List<Map<String, Object>> mappings = sectionCourses.jsonPath().getList("Data");
        Object count = sectionCourses.jsonPath().get("Count");

        assertThat(mappings)
                .as("the section-courses list must be present to match the CourseMaster %s. Response: %s",
                        courseId, sectionCourses.getBody().asString())
                .isNotNull();

        List<String> courseMasterIdsPresent = new ArrayList<>();
        Map<String, Object> match = null;
        for (Map<String, Object> candidate : mappings) {
            Object courseMaster = candidate.get("CourseMaster");
            if (!(courseMaster instanceof Map)) {
                continue;
            }
            Object candidateCourseId = ((Map<?, ?>) courseMaster).get("uuid");
            courseMasterIdsPresent.add(String.valueOf(candidateCourseId));
            if (match == null && courseId.equals(candidateCourseId)) {
                match = candidate;
            }
        }

        assertThat(match)
                .as("no section-course mapping matched CourseMaster %s. CourseMaster uuids present: %s. Count: %s",
                        courseId, courseMasterIdsPresent, count)
                .isNotNull();

        return match;
    }

    // ================= Stage 4: gamification on the capstone TermCourse =================

    /**
     * Enables gamification on the CAPSTONE TermCourse only, as the full course-edit object the backend's
     * update contract expects - not a one-field patch. The endpoint replaces the course-edit surface, so a
     * body carrying only {@code isGamify} would blank every other configured field; every value sent here
     * is therefore read back from the TermCourse detail endpoint FIRST, in the same run, and carried
     * through unchanged apart from the single {@code isGamify} mutation.
     *
     * <p>Before mutating anything, the relation chain of the read target is re-asserted -
     * {@code Section.uuid} is the capstone section, {@code CourseMaster.uuid} is the runtime course and
     * {@code ProgramTerm.uuid} is the runtime program term. The stored {@code capstoneTermCourseId} was
     * already proven distinct from the runtime {@code termCourseId} when it was resolved, but this update
     * is the one destructive call in the stage, so the target is confirmed to belong to the capstone chain
     * immediately before it is written rather than only when it was first looked up.
     *
     * <p>The only target is {@code capstoneTermCourseId}. No request in this stage is addressed to
     * {@code ExecutionContext.termCourseId}, and nothing here asserts anything about the normal
     * TermCourse's {@code isGamify} - the runtime course is simply never touched.
     *
     * <p>Records NO checkpoint: a 2xx on the update is not evidence that gamification is on. The
     * "Capstone Course Gamification Enabled" checkpoint belongs to the verification step, which confirms
     * the flag through two independent reads.
     */
    public void enableCapstoneCourseGamification(ScenarioContext context) {
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String courseId = context.getCourseId();
        String programTermId = context.getProgramTermId();
        String adminToken = context.getAdminToken();

        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be resolved by the capstone section-courses read-back before "
                        + "gamification can be enabled")
                .isNotBlank();

        log.info("==============================\nEnabling Gamification on the Capstone Course\n"
                        + "Capstone Section: {}\nCapstone TermCourse: {}\nRuntime Course (CourseMaster): {}\n"
                        + "Runtime TermCourse (must stay untouched): {}\n==============================",
                capstoneSectionId, capstoneTermCourseId, courseId, context.getTermCourseId());

        // Read FIRST: the update is a complete course-edit object, so the current values are the only safe
        // source for the ~29 fields that must survive the edit unchanged.
        Response detail = cohortClient.getTermCourseDetail(capstoneTermCourseId, adminToken);
        String detailBody = detail.getBody().asString();
        assertThat(detail.statusCode())
                .as("capstone termCourse detail read must succeed before the gamification update. Response: "
                        + detailBody)
                .isBetween(200, 299);

        Map<String, Object> termCourse = detail.jsonPath().getMap("Data.termCourse");
        assertThat(termCourse)
                .as("the capstone termCourse detail must carry Data.termCourse - it is the source of every field "
                        + "the gamification update carries through. Response: " + detailBody)
                .isNotNull();

        // Re-assert the relation chain immediately before the one destructive call in the stage.
        assertThat(nestedUuid(termCourse, "Section"))
                .as("the gamification update target must belong to the capstone section: the termCourse detail read "
                        + "for %s reports Section %s, expected the capstone section %s",
                        capstoneTermCourseId, nestedUuid(termCourse, "Section"), capstoneSectionId)
                .isEqualTo(capstoneSectionId);
        assertThat(nestedUuid(termCourse, "CourseMaster"))
                .as("the gamification update target must be an instance of the runtime CourseMaster: the termCourse "
                        + "detail read for %s reports CourseMaster %s, expected %s",
                        capstoneTermCourseId, nestedUuid(termCourse, "CourseMaster"), courseId)
                .isEqualTo(courseId);
        assertThat(nestedUuid(termCourse, "ProgramTerm"))
                .as("the gamification update target must sit in the runtime program term: the termCourse detail read "
                        + "for %s reports ProgramTerm %s, expected %s",
                        capstoneTermCourseId, nestedUuid(termCourse, "ProgramTerm"), programTermId)
                .isEqualTo(programTermId);

        // taIds / facultyIds are TOP-LEVEL under Data, NOT inside Data.termCourse.
        Object taIds = detail.jsonPath().get("Data.taIds");
        Object facultyIds = detail.jsonPath().get("Data.facultyIds");

        Map<String, Object> body = buildTermCourseUpdatePayload(termCourse, taIds, facultyIds);

        Response response = cohortClient.updateTermCourse(capstoneTermCourseId, body, context.getAdminUserId(),
                adminToken);
        String responseBody = response.getBody().asString();
        assertThat(response.statusCode())
                .as("enabling gamification on the capstone TermCourse %s must succeed - actual status %d. "
                        + "Response: %s", capstoneTermCourseId, response.statusCode(), responseBody)
                .isBetween(200, 299);

        // No checkpoint here on purpose: the accepted update is not evidence the flag is set. The
        // verification step's two independent reads are, and that is where the checkpoint is recorded.

        log.info("==============================\nEnabling Gamification on the Capstone Course\n"
                        + "Capstone TermCourse: {}\nResult: update accepted (pending two-read verification)\n"
                        + "==============================",
                capstoneTermCourseId);
    }

    // ================= Stage 5: gamification verified by two independent reads =================

    /**
     * Read-back verification of the gamification update - the evidence the update step deliberately does
     * not claim for itself. Gamification is confirmed TWICE, through two independent endpoints:
     *
     * <ol>
     *   <li>the TermCourse detail read, which is the authoritative record of the entity that was written;</li>
     *   <li>the section-courses projection for the capstone section, which is how the rest of the platform
     *       (and the admin UI) actually sees the flag.</li>
     * </ol>
     *
     * Two reads rather than one because they can disagree: a write that landed on the entity but has not
     * surfaced in the projection is a real, observable defect, and only the second read can catch it. Every
     * failure message therefore names WHICH read disagreed ("termCourse detail read" vs "section-courses
     * projection") - the whole value of a two-read check is lost if the report cannot say which one dissented.
     *
     * <p>Read 1 also re-asserts the full relation chain - {@code Section.uuid} is the capstone section,
     * {@code Section.isCapstoneSection} is set, {@code CourseMaster.uuid} is the runtime course and
     * {@code ProgramTerm.uuid} is the runtime program term. That is what turns "some course is gamified"
     * into "the CAPSTONE instance of the runtime course, in the runtime term, is gamified":
     * {@code isCapstoneSection} in particular proves the flag landed on a capstone section rather than on a
     * section that merely happens to be new.
     *
     * <p>Read 2 re-matches the mapping through {@link #findSectionCourseByCourseMaster(Response, String)} -
     * the SAME helper that resolved {@code capstoneTermCourseId} in the first place - so the two steps can
     * never drift into matching the mapping by different criteria.
     *
     * <p>Uses the STORED {@code capstoneTermCourseId} and re-resolves nothing: the id was resolved exactly
     * once, before the update, so it is impossible for this step to verify a different TermCourse from the
     * one that was written. Asserts nothing about {@code isActiveTerm} or any other unrelated state.
     *
     * <p>This is the stage's last step, so once both reads agree it records the gamification checkpoint,
     * then the closing "Capstone Mini-Setup Complete" checkpoint naming what the stage did and did NOT
     * create, and publishes the accumulated capstone report block.
     */
    public void verifyCapstoneCourseGamified(ScenarioContext context) {
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String courseId = context.getCourseId();
        String programTermId = context.getProgramTermId();
        String adminToken = context.getAdminToken();

        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be the value resolved once by the capstone section-courses "
                        + "read-back - the gamification verification re-resolves nothing")
                .isNotBlank();
        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured before the gamification verification reads can run")
                .isNotBlank();

        log.info("==============================\nVerifying Capstone Course Gamification\n"
                        + "Capstone TermCourse: {}\nCapstone Section: {}\nRuntime Course (CourseMaster): {}\n"
                        + "Program Term: {}\n==============================",
                capstoneTermCourseId, capstoneSectionId, courseId, programTermId);

        // ---- Read 1: the TermCourse detail - the authoritative record of the entity that was written ----
        Response detail = cohortClient.getTermCourseDetail(capstoneTermCourseId, adminToken);
        String detailBody = detail.getBody().asString();
        assertThat(detail.statusCode())
                .as("capstone termCourse detail read must succeed to verify gamification. Response: " + detailBody)
                .isBetween(200, 299);

        Map<String, Object> termCourse = detail.jsonPath().getMap("Data.termCourse");
        assertThat(termCourse)
                .as("the capstone termCourse detail must carry Data.termCourse to verify gamification. Response: "
                        + detailBody)
                .isNotNull();

        Object detailIsGamify = termCourse.get("isGamify");
        assertThat(detailIsGamify)
                .as("termCourse detail read reports isGamify=%s for the capstone TermCourse %s - gamification was "
                        + "not enabled on the capstone course", detailIsGamify, capstoneTermCourseId)
                .isEqualTo(Boolean.TRUE);

        String detailSectionId = nestedUuid(termCourse, "Section");
        assertThat(detailSectionId)
                .as("termCourse detail read reports relation Section=%s for the capstone TermCourse %s, expected the "
                        + "capstone section %s", detailSectionId, capstoneTermCourseId, capstoneSectionId)
                .isEqualTo(capstoneSectionId);

        Object section = termCourse.get("Section");
        Object detailIsCapstoneSection = section instanceof Map ? ((Map<?, ?>) section).get("isCapstoneSection") : null;
        assertThat(detailIsCapstoneSection)
                .as("termCourse detail read reports relation Section.isCapstoneSection=%s for section %s - the "
                        + "gamified course must sit in a CAPSTONE section, not merely in a newly created one",
                        detailIsCapstoneSection, detailSectionId)
                .isEqualTo(Boolean.TRUE);

        String detailCourseMasterId = nestedUuid(termCourse, "CourseMaster");
        assertThat(detailCourseMasterId)
                .as("termCourse detail read reports relation CourseMaster=%s for the capstone TermCourse %s, "
                        + "expected the runtime course %s", detailCourseMasterId, capstoneTermCourseId, courseId)
                .isEqualTo(courseId);

        String detailProgramTermId = nestedUuid(termCourse, "ProgramTerm");
        assertThat(detailProgramTermId)
                .as("termCourse detail read reports relation ProgramTerm=%s for the capstone TermCourse %s, "
                        + "expected the runtime program term %s",
                        detailProgramTermId, capstoneTermCourseId, programTermId)
                .isEqualTo(programTermId);

        // ---- Read 2: the section-courses projection - how the rest of the platform sees the flag ----
        Response sectionCourses = cohortClient.getSectionCourses(capstoneSectionId, adminToken);
        assertThat(sectionCourses.statusCode())
                .as("capstone section-courses read must succeed as the second, independent gamification "
                        + "confirmation. Response: " + sectionCourses.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> mapping = findSectionCourseByCourseMaster(sectionCourses, courseId);
        Object projectionIsGamify = mapping.get("isGamify");
        assertThat(projectionIsGamify)
                .as("section-courses projection reports isGamify=%s for the capstone section %s mapping of "
                        + "CourseMaster %s - the termCourse detail read agrees gamification is on, so the flag "
                        + "has not surfaced in the section-courses projection",
                        projectionIsGamify, capstoneSectionId, courseId)
                .isEqualTo(Boolean.TRUE);

        CapstoneJourneyReport.checkpoint(context, "Capstone Course Gamification Enabled",
                "Capstone TermCourse UUID: " + capstoneTermCourseId,
                "Confirmed by termCourse read",
                "Confirmed by section-courses read");

        CapstoneJourneyReport.checkpoint(context, "Capstone Mini-Setup Complete",
                "1 section + 1 section-course created",
                "No program / cohort / term / course / student created");

        CapstoneJourneyReport.publish(context);

        log.info("==============================\nVerifying Capstone Course Gamification\n"
                        + "Capstone TermCourse: {}\nCapstone Section: {}\n"
                        + "Result: SUCCESS (isGamify confirmed by BOTH the termCourse detail read and the "
                        + "section-courses projection)\n==============================",
                capstoneTermCourseId, capstoneSectionId);
    }

    /**
     * Builds the complete course-edit body for the gamification update out of one TermCourse detail read.
     * A {@link LinkedHashMap} because the wire ORDER matters for parity with the captured request: the
     * thirty keys below appear in the verified order, and {@code updatedBy} - the thirty-first - is
     * stamped by {@code CohortClient.updateTermCourse}, exactly as it is for every other update in the
     * framework, so this method never adds it.
     *
     * <p>Pure by design: no request, no context read, no state. That is what makes it the one piece of
     * this stage that is unit-testable without a live environment, and the payload contract is pinned by
     * {@code CapstoneWorkflowPayloadTest} rather than by a live run. It is PACKAGE-PRIVATE rather than
     * private for exactly that reason - the test lives in the same package and invokes it directly. It is
     * also {@code static}, so the test needs no {@code CapstoneWorkflow} instance and therefore constructs
     * no HTTP client: the contract test stays free of HTTP, context, configuration and credentials.
     *
     * <p>Three transforms exist because the read and update contracts disagree:
     * <ul>
     *   <li>{@code credits} is numeric on read and a string on update;</li>
     *   <li>the four {@code *Id} lookups and {@code departmentId} come from the NESTED association's
     *       {@code uuid}. {@code departmentId} is the dangerous one: the read response's flat
     *       {@code departmentId} is a numeric internal id in a different identifier space, so sending it
     *       would either be rejected or silently bind the course to the wrong department;</li>
     *   <li>{@code subjectWeight} is null on read and must go out as {@code ""}.</li>
     * </ul>
     * Everything else is carried through verbatim, including the API's own {@code workexperienced}
     * spelling. Sixteen read-back-only fields ({@code isMandatory}, {@code isCourseReview},
     * {@code isDraft}, {@code displayOrder}, {@code startDate}, {@code endDate}, {@code sectionId},
     * {@code sessionFormat}, {@code status}, {@code remarks}, {@code moderatedDiscussion},
     * {@code transactionId}, {@code isSubjectWeightEnable}, {@code AttendanceStats},
     * {@code feedbackSummary}, {@code isAttendaceStatsProcessed} - the last misspelling is the API's) are
     * omitted, as are {@code uuid}, {@code createdBy}, {@code createdAt}, {@code updatedAt} and every
     * nested association object, whose {@code *Id} uuid equivalents are sent instead.
     *
     * @param termCourse  the {@code Data.termCourse} map from the TermCourse detail read
     * @param taIds       the TOP-LEVEL {@code Data.taIds} from that read, not a {@code termCourse} field
     * @param facultyIds  the TOP-LEVEL {@code Data.facultyIds} from that read
     */
    static Map<String, Object> buildTermCourseUpdatePayload(Map<String, Object> termCourse, Object taIds,
                                                            Object facultyIds) {
        Object credits = termCourse.get("credits");
        Object subjectWeight = termCourse.get("subjectWeight");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("credits", credits == null ? null : String.valueOf(credits));
        payload.put("courseModeId", nestedUuid(termCourse, "CourseMode"));
        payload.put("courseTypeId", nestedUuid(termCourse, "CourseType"));
        payload.put("courseTagId", nestedUuid(termCourse, "CourseTag"));
        payload.put("courseClassId", nestedUuid(termCourse, "CourseClass"));
        payload.put("hours", termCourse.get("hours"));
        payload.put("isAttendanceCalculated", termCourse.get("isAttendanceCalculated"));
        // NEVER the flat numeric termCourse.departmentId - different identifier space (see Javadoc).
        payload.put("departmentId", nestedUuid(termCourse, "NpsDepartment"));
        payload.put("evaluationCriteria", termCourse.get("evaluationCriteria"));
        payload.put("courseOverview", termCourse.get("courseOverview"));
        payload.put("description", termCourse.get("description"));
        payload.put("courseOutcome", termCourse.get("courseOutcome"));
        payload.put("workexperienced", termCourse.get("workexperienced"));
        payload.put("readingMaterial", termCourse.get("readingMaterial"));
        payload.put("videoLink", termCourse.get("videoLink"));
        payload.put("topicsJson", termCourse.get("topicsJson"));
        // INFERENCE (the single one in this design): the update contract names this domain-related field
        // courseDomain while the read returns it as TermCourseDomains; both are empty in every captured
        // payload, so the two names could not be reconciled from the evidence. An empty list is a faithful
        // no-change value for a course with no domains, which is the case here, and no TermCourseDomains
        // key is sent. What a non-empty value looks like, and whether the update also accepts
        // TermCourseDomains, is unverified and confirmable only by capturing a domain-carrying course edit.
        payload.put("courseDomain", Collections.emptyList());
        payload.put("placementCourse", termCourse.get("placementCourse"));
        payload.put("subjectWeight", subjectWeight == null ? "" : subjectWeight);
        payload.put("showCourseToStudent", termCourse.get("showCourseToStudent"));
        payload.put("isCourseCalculated", termCourse.get("isCourseCalculated"));
        payload.put("taIds", taIds);
        payload.put("facultyIds", facultyIds);
        // The sole value mutation in the whole body.
        payload.put("isGamify", true);
        payload.put("isGamifyDomainWise", termCourse.get("isGamifyDomainWise"));
        payload.put("attendanceType", termCourse.get("attendanceType"));
        payload.put("coursePurpose", termCourse.get("coursePurpose"));
        payload.put("isCreditCalculated", termCourse.get("isCreditCalculated"));
        payload.put("isGpaCalculated", termCourse.get("isGpaCalculated"));
        payload.put("noOfSession", termCourse.get("noOfSession"));
        return payload;
    }

    /**
     * Reads {@code uuid} out of a nested association object, returning null when the association is absent
     * or is not an object - so a missing association surfaces as a null in an assertion message naming the
     * relation, rather than as a ClassCastException from deep inside the payload.
     */
    private static String nestedUuid(Map<String, Object> owner, String association) {
        Object nested = owner.get(association);
        if (!(nested instanceof Map)) {
            return null;
        }
        Object uuid = ((Map<?, ?>) nested).get("uuid");
        return uuid == null ? null : String.valueOf(uuid);
    }

    // =====================================================================================
    // ================= Capstone S2: Configure Capstone (ONE aggregate POST) ==============
    // =====================================================================================
    // See the class Javadoc for the architecture: SIX business steps, ONE configuration write.
    // The first of the six to run performs the POST with the COMPLETE payload; every one of them
    // then asserts its own disjoint slice against what was actually persisted and records its own
    // checkpoint. The slice ownership is:
    //   configureCapstoneMetrics        -> metrics[] + the collection schedule
    //   configureCapstoneMetricsBuilder -> xByXRows[] + xByXColumns[]
    //   configureCapstoneDashboard      -> dashboardDisplay[]
    //   configureCapstoneAnalytics      -> analyticsDisplay[]
    //   configureCapstoneLeaderboard    -> leaderboardType + leaderboard.formula
    //   configureCapstoneResources      -> resources[]
    // configureCapstoneRewards is a separate API and makes two real calls of its own.

    /** Per-scenario attribute holding the aggregate configuration REQUEST body exactly as it was sent. */
    private static final String CONFIG_REQUEST_ATTRIBUTE = "capstoneGamificationConfigRequest";

    /**
     * Per-scenario attribute holding the PERSISTED configuration as last observed - initially the
     * configuration response's {@code Data}, replaced by the termCourse read-back's configuration entry
     * as soon as a step needs a slice the response did not echo. Per-scenario on purpose: it is a cache of
     * an observation, never a source of identity. The identity (uuid + numeric id) lives in
     * {@code ExecutionContext}.
     */
    private static final String CONFIG_PERSISTED_ATTRIBUTE = "capstoneGamificationConfigPersisted";

    private static final String METRIC_REVENUE = "Revenue";
    private static final String METRIC_COST = "Cost";
    private static final String METRIC_NUMBER_OF_SALES = "Number of Sales";
    private static final String METRIC_PROFIT = "Profit";
    private static final String METRIC_CUSTOMERS = "Customers";
    private static final String METRIC_FUNDING_RAISED = "Funding Raised";
    /** Leaderboard-only pseudo-metric: it carries a weightage but is NOT one of the six metrics[]. */
    private static final String METRIC_POINT = "Point";

    private static final String TYPE_AMOUNT = "amount";
    private static final String TYPE_COUNT = "count";
    private static final String TYPE_PERCENTAGE = "percentage";

    private static final String TARGET_REVENUE = "15000";
    private static final String TARGET_NUMBER_OF_SALES = "100";
    private static final String TARGET_PROFIT = "10";

    private static final String TRIGGER_INTERVAL_DAILY = "daily";
    private static final String LEADERBOARD_TYPE_FORMULA = "formula";
    private static final String LEADERBOARD_FORMULA_SET_NAME = "custom formula based calculation";
    private static final String XBYX_COLUMN_ONLINE = "Online";

    /** Deterministic reward identity - no randomness, so the read-back can assert it by value. */
    private static final String REWARD_NAME = "Automation Capstone Reward";
    private static final String REWARD_DESCRIPTION = "Automation-created capstone reward for the capstone journey";
    private static final int REWARD_COIN_COST = 250;

    private static final DateTimeFormatter SCHEDULE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * ISO-8601 UTC with millisecond precision and a literal {@code Z} - the resource {@code date} field is
     * a full timestamp ({@code 2026-09-21T10:55:17.084Z}), not a plain calendar date.
     */
    private static final DateTimeFormatter RESOURCE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // ---------------------------------------------------------------------------------
    // S2 step 0: the precondition guard - S1's state must be complete before configuring
    // ---------------------------------------------------------------------------------

    /**
     * Asserts the S1 (capstone mini-setup) state this configuration stage consumes is complete BEFORE any
     * configuration request is issued, so a half-run S1 names the missing id instead of failing inside a
     * request or - worse - configuring the wrong TermCourse.
     *
     * <p>The one assertion that carries real business weight is {@code capstoneTermCourseId != termCourseId}:
     * the whole point of S1 is that the capstone section yields its OWN TermCourse, and the aggregate
     * configuration below is written against that id. If the two ever converged, this stage would configure
     * gamification on the runtime course the Assignment / Session / Attendance journeys depend on.
     *
     * <p>Issues NO request and creates nothing.
     */
    public void validateCapstoneReadyForConfiguration(ScenarioContext context) {
        String capstoneSectionId = context.getCapstoneSectionId();
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String runtimeTermCourseId = context.getTermCourseId();

        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by the capstone section creation step (S1) before the "
                        + "capstone configuration stage can run")
                .isNotBlank();
        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be resolved by the capstone section-courses read-back (S1) before "
                        + "the capstone configuration stage can run")
                .isNotBlank();
        assertThat(context.getCourseId())
                .as("courseId must be set by Environment Setup - the capstone configuration is written against the "
                        + "capstone instance of that EXISTING course")
                .isNotBlank();
        assertThat(context.getProgramTermId())
                .as("programTermId must be set by Environment Setup before the capstone configuration stage")
                .isNotBlank();
        assertThat(context.getAdminUserId())
                .as("adminUserId must be set by admin login before the capstone configuration stage")
                .isNotBlank();
        assertThat(context.getAdminToken())
                .as("the admin session must still be authenticated before the capstone configuration stage")
                .isNotBlank();

        assertThat(capstoneTermCourseId)
                .as("the capstone TermCourse %s must still be a DISTINCT instance from the runtime TermCourse %s - "
                        + "the capstone configuration is written against the capstone instance ONLY, and the runtime "
                        + "course must stay untouched", capstoneTermCourseId, runtimeTermCourseId)
                .isNotEqualTo(runtimeTermCourseId);

        log.info("==============================\nCapstone Configuration Readiness\nCapstone Section: {}\n"
                        + "Capstone TermCourse: {}\nRuntime Course (CourseMaster): {}\n"
                        + "Runtime TermCourse (must stay untouched): {}\n"
                        + "Result: capstone state ready for configuration (no request issued)\n==============================",
                capstoneSectionId, capstoneTermCourseId, context.getCourseId(), runtimeTermCourseId);
    }

    // ---------------------------------------------------------------------------------
    // S2 steps 1-6: the six business slices of the ONE aggregate configuration
    // ---------------------------------------------------------------------------------

    /**
     * Owns the {@code metrics[]} slice and the collection schedule. Being first in the Gherkin order it is
     * normally the step that performs the single aggregate POST (see the class Javadoc) - but it does not
     * assume it: if another of the six ran first, the configuration is already persisted and this step only
     * verifies its own slice.
     *
     * <p>Asserts the persisted configuration carries all SIX metrics in the sent order and that the three
     * metrics that were given targets kept them, then that the schedule round-tripped: the response returns
     * ISO timestamps where plain {@code yyyy-MM-dd} dates were sent, so the comparison normalises through
     * {@code DateUtils.toCalendarDate} rather than comparing strings.
     */
    public void configureCapstoneMetrics(ScenarioContext context) {
        Map<String, Object> persisted = requirePersistedSlices(context, "metrics and their collection schedule",
                "metrics", "scheduleStartDate", "scheduleEndDate", "triggerInterval");

        List<Map<String, Object>> metrics = mapList(persisted.get("metrics"));
        assertThat(metrics)
                .as("the persisted capstone configuration %s must carry a metrics list - the metrics slice of the "
                        + "aggregate configuration did not land", context.getCapstoneGamificationConfigUuid())
                .isNotNull();
        assertThat(metricNames(metrics))
                .as("the persisted capstone configuration %s must carry the six configured metrics in the order they "
                        + "were sent", context.getCapstoneGamificationConfigUuid())
                .containsExactlyElementsOf(metricNames(buildCapstoneMetrics()));

        assertThat(metricTarget(metrics, METRIC_REVENUE))
                .as("persisted metric '%s' must keep the target that was configured", METRIC_REVENUE)
                .isEqualTo(TARGET_REVENUE);
        assertThat(metricTarget(metrics, METRIC_NUMBER_OF_SALES))
                .as("persisted metric '%s' must keep the target that was configured", METRIC_NUMBER_OF_SALES)
                .isEqualTo(TARGET_NUMBER_OF_SALES);
        assertThat(metricTarget(metrics, METRIC_PROFIT))
                .as("persisted metric '%s' must keep the target that was configured", METRIC_PROFIT)
                .isEqualTo(TARGET_PROFIT);

        assertThat(text(persisted.get("triggerInterval")))
                .as("the persisted capstone configuration must keep the configured collection interval")
                .isEqualTo(TRIGGER_INTERVAL_DAILY);
        assertThat(DateUtils.toCalendarDate(persisted.get("scheduleStartDate")))
                .as("the persisted schedule start must normalise to the calendar date that was sent - the response "
                        + "returns an ISO timestamp where a plain date was sent")
                .isEqualTo(sentScheduleDate(context, "scheduleStartDate"));
        assertThat(DateUtils.toCalendarDate(persisted.get("scheduleEndDate")))
                .as("the persisted schedule end must normalise to the calendar date that was sent - the response "
                        + "returns an ISO timestamp where a plain date was sent")
                .isEqualTo(sentScheduleDate(context, "scheduleEndDate"));

        CapstoneJourneyReport.checkpoint(context, "Capstone Metrics Configured",
                "6 metrics configured (" + String.join(", ", metricNames(metrics)) + ")",
                "Targets: " + METRIC_REVENUE + "=" + TARGET_REVENUE + ", " + METRIC_NUMBER_OF_SALES + "="
                        + TARGET_NUMBER_OF_SALES + ", " + METRIC_PROFIT + "=" + TARGET_PROFIT,
                "Collection interval: " + TRIGGER_INTERVAL_DAILY);

        log.info("==============================\nConfiguring Capstone Metrics\nCapstone TermCourse: {}\n"
                        + "Metrics: {}\nResult: SUCCESS (six metrics and the collection schedule persisted)\n"
                        + "==============================",
                context.getCapstoneTermCourseId(), metricNames(metrics));
    }

    /**
     * Owns the metrics-builder grid slice - {@code xByXRows[]} (which metrics the student enters, with the
     * input type each one expects) and {@code xByXColumns[]} (the channels those entries are broken down by).
     * Part of the ONE aggregate configuration: it performs the POST only if no earlier step already did
     * (see the class Javadoc).
     */
    public void configureCapstoneMetricsBuilder(ScenarioContext context) {
        Map<String, Object> persisted = requirePersistedSlices(context, "metrics builder grid",
                "xByXRows", "xByXColumns");

        List<Map<String, Object>> rows = mapList(persisted.get("xByXRows"));
        assertThat(rows)
                .as("the persisted capstone configuration %s must carry the metrics-builder rows",
                        context.getCapstoneGamificationConfigUuid())
                .isNotNull();
        assertThat(metricNames(rows))
                .as("the persisted metrics-builder rows must be the three configured input metrics, in order")
                .containsExactlyElementsOf(metricNames(buildXByXRows()));

        List<String> expectedInputTypes = new ArrayList<>();
        for (Map<String, Object> expectedRow : buildXByXRows()) {
            expectedInputTypes.add(text(expectedRow.get("inputType")));
        }
        List<String> actualInputTypes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            actualInputTypes.add(text(row.get("inputType")));
        }
        assertThat(actualInputTypes)
                .as("each persisted metrics-builder row must keep the input type it was configured with (the key is "
                        + "inputType on rows, not type)")
                .containsExactlyElementsOf(expectedInputTypes);

        assertThat(stringList(persisted.get("xByXColumns")))
                .as("the persisted metrics-builder columns must be exactly the configured channels")
                .containsExactlyElementsOf(buildXByXColumns());

        CapstoneJourneyReport.checkpoint(context, "Capstone Metrics Builder Configured",
                "Input rows: " + String.join(", ", metricNames(rows)),
                "Input types: " + String.join(", ", actualInputTypes),
                "Columns: " + String.join(", ", buildXByXColumns()));

        log.info("==============================\nConfiguring Capstone Metrics Builder\nCapstone TermCourse: {}\n"
                        + "Rows: {}\nColumns: {}\nResult: SUCCESS (metrics-builder grid persisted)\n"
                        + "==============================",
                context.getCapstoneTermCourseId(), metricNames(rows), buildXByXColumns());
    }

    /**
     * Owns the {@code dashboardDisplay[]} slice - the metrics pinned to the capstone dashboard. Only a
     * SUBSET of the six metrics is pinned (Revenue and Profit), which is exactly why this is its own
     * business step: "all metrics exist" and "the right two are pinned" are different facts. Part of the ONE
     * aggregate configuration (see the class Javadoc).
     */
    public void configureCapstoneDashboard(ScenarioContext context) {
        Map<String, Object> persisted = requirePersistedSlices(context, "dashboard display", "dashboardDisplay");

        List<Map<String, Object>> dashboard = mapList(persisted.get("dashboardDisplay"));
        assertThat(dashboard)
                .as("the persisted capstone configuration %s must carry the dashboard display list",
                        context.getCapstoneGamificationConfigUuid())
                .isNotNull();

        List<String> expectedLabels = new ArrayList<>();
        for (Map<String, Object> expectedEntry : buildDashboardDisplay()) {
            expectedLabels.add(text(expectedEntry.get("label")));
        }
        assertThat(labels(dashboard, "label"))
                .as("only the metrics that were pinned may appear on the capstone dashboard - a pinned set that "
                        + "drifted means the dashboard slice of the aggregate configuration did not land as sent")
                .containsExactlyElementsOf(expectedLabels);

        CapstoneJourneyReport.checkpoint(context, "Capstone Dashboard Configured",
                "Pinned metrics: " + String.join(", ", labels(dashboard, "label")),
                "A subset of the 6 configured metrics, as sent");

        log.info("==============================\nConfiguring Capstone Dashboard\nCapstone TermCourse: {}\n"
                        + "Pinned Metrics: {}\nResult: SUCCESS (dashboard display persisted)\n==============================",
                context.getCapstoneTermCourseId(), labels(dashboard, "label"));
    }

    /**
     * Owns the {@code analyticsDisplay[]} slice - the metrics charted in capstone analytics, each with its
     * detailed-graph flag. NOTE the payload quirk this step verifies against: on an analytics entry the
     * {@code type} key holds the metric NAME (not an amount/count/percentage input type), and the metric
     * object itself sits under {@code data}. Part of the ONE aggregate configuration (see the class Javadoc).
     */
    public void configureCapstoneAnalytics(ScenarioContext context) {
        Map<String, Object> persisted = requirePersistedSlices(context, "analytics display", "analyticsDisplay");

        List<Map<String, Object>> analytics = mapList(persisted.get("analyticsDisplay"));
        assertThat(analytics)
                .as("the persisted capstone configuration %s must carry the analytics display list",
                        context.getCapstoneGamificationConfigUuid())
                .isNotNull();

        List<String> expectedCharted = new ArrayList<>();
        for (Map<String, Object> expectedEntry : buildAnalyticsDisplay()) {
            expectedCharted.add(text(expectedEntry.get("type")));
        }
        assertThat(labels(analytics, "type"))
                .as("the persisted analytics display must chart exactly the configured metrics, in order (on an "
                        + "analytics entry the 'type' key holds the metric NAME)")
                .containsExactlyElementsOf(expectedCharted);

        CapstoneJourneyReport.checkpoint(context, "Capstone Analytics Configured",
                "Charted metrics: " + String.join(", ", labels(analytics, "type")),
                "Detailed visual graph enabled on each");

        log.info("==============================\nConfiguring Capstone Analytics\nCapstone TermCourse: {}\n"
                        + "Charted Metrics: {}\nResult: SUCCESS (analytics display persisted)\n==============================",
                context.getCapstoneTermCourseId(), labels(analytics, "type"));
    }

    /**
     * Owns the leaderboard slice - {@code leaderboardType} plus the two-level
     * {@code leaderboard.formula} weighting. Its own business step because the leaderboard is the only place
     * the pseudo-metric {@code Point} appears (it is NOT one of the six {@code metrics[]}) and the only place
     * weightages exist. Part of the ONE aggregate configuration (see the class Javadoc).
     *
     * <p>Weightages are compared as STRINGS: the sent payload deliberately mixes numeric and string
     * weightages to match the captured contract, so a typed comparison would fail on the payload the API
     * itself was proven to accept.
     */
    public void configureCapstoneLeaderboard(ScenarioContext context) {
        Map<String, Object> persisted = requirePersistedSlices(context, "leaderboard formula",
                "leaderboardType", "leaderboard");

        assertThat(text(persisted.get("leaderboardType")))
                .as("the persisted capstone configuration %s must keep the configured leaderboard type",
                        context.getCapstoneGamificationConfigUuid())
                .isEqualTo(LEADERBOARD_TYPE_FORMULA);

        Map<String, Object> persistedLeaderboard = asMap(persisted.get("leaderboard"));
        assertThat(persistedLeaderboard)
                .as("the persisted capstone configuration %s must carry its leaderboard object",
                        context.getCapstoneGamificationConfigUuid())
                .isNotNull();

        Map<String, Object> formula = asMap(persistedLeaderboard.get("formula"));
        assertThat(formula)
                .as("the persisted leaderboard must carry its formula object - the leaderboard slice of the "
                        + "aggregate configuration did not land")
                .isNotNull();
        assertThat(text(formula.get("setName")))
                .as("the persisted leaderboard formula must keep the configured formula set name")
                .isEqualTo(LEADERBOARD_FORMULA_SET_NAME);

        List<Map<String, Object>> formulaMetrics = mapList(formula.get("metrics"));
        assertThat(formulaMetrics)
                .as("the persisted leaderboard formula must carry its weighted metrics list")
                .isNotNull();
        assertThat(leaderboardLabels(formulaMetrics))
                .as("the persisted leaderboard formula must weight exactly the configured entries, in order - "
                        + "including the leaderboard-only pseudo-metric '%s', which is not one of the six metrics",
                        METRIC_POINT)
                .containsExactlyElementsOf(leaderboardLabels(leaderboardFormulaMetrics()));
        assertThat(leaderboardWeightages(formulaMetrics))
                .as("the persisted leaderboard weightages must match what was sent (compared as strings: the "
                        + "captured contract mixes numeric and string weightages)")
                .containsExactlyElementsOf(leaderboardWeightages(leaderboardFormulaMetrics()));

        CapstoneJourneyReport.checkpoint(context, "Capstone Leaderboard Configured",
                "Leaderboard type: " + LEADERBOARD_TYPE_FORMULA,
                "Formula: " + LEADERBOARD_FORMULA_SET_NAME,
                "Weighted entries: " + String.join(", ", leaderboardLabels(formulaMetrics)),
                "Weightages: " + String.join(", ", leaderboardWeightages(formulaMetrics)));

        log.info("==============================\nConfiguring Capstone Leaderboard\nCapstone TermCourse: {}\n"
                        + "Formula: {}\nWeighted Entries: {}\n"
                        + "Result: SUCCESS (leaderboard formula persisted)\n==============================",
                context.getCapstoneTermCourseId(), LEADERBOARD_FORMULA_SET_NAME, leaderboardLabels(formulaMetrics));
    }

    /**
     * Owns the {@code resources[]} slice - the capstone resource folder and the one link inside it. Part of
     * the ONE aggregate configuration (see the class Javadoc).
     *
     * <p>Asserts the folder name, resource name and link only. The resource {@code date} is a RUNTIME
     * timestamp (generated per run, never a captured literal), so it is not compared by value here.
     */
    public void configureCapstoneResources(ScenarioContext context) {
        Map<String, Object> persisted = requirePersistedSlices(context, "resources", "resources");

        List<Map<String, Object>> resources = mapList(persisted.get("resources"));
        assertThat(resources)
                .as("the persisted capstone configuration %s must carry the resources list",
                        context.getCapstoneGamificationConfigUuid())
                .isNotNull();
        assertThat(resources)
                .as("the persisted capstone configuration must carry the one configured resource folder")
                .hasSize(1);

        Map<String, Object> folder = resources.get(0);
        assertThat(text(folder.get("folderName")))
                .as("the persisted capstone resource folder must keep the configured folder name")
                .isEqualTo(TestDataFactory.capstoneResourceFolderName());

        List<Map<String, Object>> folderData = mapList(folder.get("folderData"));
        assertThat(folderData)
                .as("the persisted capstone resource folder '%s' must carry its folderData entries",
                        TestDataFactory.capstoneResourceFolderName())
                .isNotNull();
        assertThat(folderData)
                .as("the persisted capstone resource folder must carry the one configured resource")
                .hasSize(1);
        assertThat(text(folderData.get(0).get("name")))
                .as("the persisted capstone resource must keep the configured resource name")
                .isEqualTo(TestDataFactory.capstoneResourceName());
        assertThat(text(folderData.get(0).get("link")))
                .as("the persisted capstone resource must keep the configured resource link")
                .isEqualTo(TestDataFactory.capstoneResourceLink());

        CapstoneJourneyReport.checkpoint(context, "Capstone Resources Configured",
                "Folder: " + TestDataFactory.capstoneResourceFolderName(),
                "Resource: " + TestDataFactory.capstoneResourceName(),
                "Link and runtime timestamp persisted");

        log.info("==============================\nConfiguring Capstone Resources\nCapstone TermCourse: {}\n"
                        + "Folder: {}\nResource: {}\nResult: SUCCESS (resources persisted)\n==============================",
                context.getCapstoneTermCourseId(), TestDataFactory.capstoneResourceFolderName(),
                TestDataFactory.capstoneResourceName());
    }

    // ---------------------------------------------------------------------------------
    // S2 step 7: rewards - genuinely a separate API, so genuinely two real calls
    // ---------------------------------------------------------------------------------

    /**
     * Creates the capstone reward. Unlike the six configuration slices above, this step is NOT part of the
     * aggregate write: rewards are their own API keyed off the configuration's NUMERIC id, so this step
     * makes two real calls - the pre-state rewards read the admin UI fires first (status only; before any
     * reward exists the API answers 200 with an EMPTY {@code Data} array, which is not an error), then the
     * create.
     *
     * <p>The reward is created against the NUMERIC configuration id, never the configuration uuid - the
     * rewards API does not accept the uuid.
     *
     * <p>No cleanup registration: the captured contract has no DELETE for a reward (see the class Javadoc).
     */
    public void configureCapstoneRewards(ScenarioContext context) {
        Map<String, Object> persisted = ensureCapstoneConfigurationPersisted(context);
        assertThat(persisted)
                .as("the capstone gamification configuration must be persisted before a reward can be created "
                        + "against it")
                .isNotNull();

        Integer numericConfigId = context.getCapstoneGamificationConfigNumericId();
        assertThat(numericConfigId)
                .as("the NUMERIC capstone configuration id must be captured before rewards can be created - the "
                        + "rewards API keys off the numeric id, never the configuration uuid")
                .isNotNull();

        String adminToken = context.getAdminToken();

        log.info("==============================\nConfiguring Capstone Rewards\nCapstone TermCourse: {}\n"
                        + "Configuration (numeric id): {}\n==============================",
                context.getCapstoneTermCourseId(), numericConfigId);

        // 1. Pre-state rewards read - replayed in the admin UI's own position. Status only: an empty reward
        // list is the expected pre-state and is not asserted about.
        Response preRewards = capstoneClient.getGamificationRewards(numericConfigId, adminToken);
        assertThat(preRewards.statusCode())
                .as("pre-create capstone rewards read must succeed. Response: " + preRewards.getBody().asString())
                .isBetween(200, 299);

        // 2. Create the reward.
        Map<String, Object> payload = buildCapstoneRewardPayload(numericConfigId);
        Response response = capstoneClient.createGamificationReward(payload, adminToken);
        String body = response.getBody().asString();

        assertThat(response.statusCode())
                .as("capstone reward creation must return 201 - actual status %d. Response: %s",
                        response.statusCode(), body)
                .isEqualTo(201);

        String rewardUuid = response.jsonPath().getString("Data.uuid");
        assertThat(rewardUuid)
                .as("the created capstone reward must report its uuid (Data.uuid) - it is what the rewards "
                        + "read-back matches the reward by. Response: " + body)
                .isNotBlank();

        context.setCapstoneRewardUuid(rewardUuid);

        // Checkpoint recorded only after the create assertions passed.
        CapstoneJourneyReport.checkpoint(context, "Capstone Rewards Configured",
                "Reward: " + REWARD_NAME,
                "Coins required: " + REWARD_COIN_COST,
                "Claim questions: 3 (text, multi-select, single-select)",
                "Reward UUID: " + rewardUuid);

        log.info("==============================\nConfiguring Capstone Rewards\nReward: {}\nReward UUID: {}\n"
                        + "Result: SUCCESS (reward created, pending read-back)\n==============================",
                REWARD_NAME, rewardUuid);
    }

    // ---------------------------------------------------------------------------------
    // S2 step 8: verification by read-back - never by remembered status codes
    // ---------------------------------------------------------------------------------

    /**
     * Read-back verification of the whole capstone configuration - the evidence the configuration steps
     * deliberately do not claim from their own response. Two independent reads, and every failure message
     * names WHICH of them disagreed ("termCourse readback" vs "rewards readback"), because a configuration
     * that persisted while its rewards did not (or vice versa) is a real, observable defect and a shared
     * message would hide which half broke.
     *
     * <ol>
     *   <li>the termCourse detail read: the course is still gamified, the S1 relation chain (capstone
     *       section, runtime CourseMaster, runtime program term) still holds, and the configuration this run
     *       created is present with every slice as sent;</li>
     *   <li>the rewards read: the reward this run created is present with its claim cost, its owning
     *       configuration and its three questions.</li>
     * </ol>
     *
     * <p>The configuration is located BY IDENTITY - the {@code GamificationConfigs[]} entry whose
     * {@code uuid} equals the stored one - never by array position, which would silently verify a different
     * configuration as soon as a course carried more than one. On no match the failure lists the uuids that
     * ARE present.
     *
     * <p>Schedule dates normalise through {@code DateUtils.toCalendarDate}: the read returns ISO timestamps
     * ({@code 2026-09-19T00:00:00.000Z}) where plain {@code yyyy-MM-dd} dates were sent, so a string
     * comparison would fail on correct data. Leaderboard weightages compare as strings for the same class of
     * reason - the sent contract mixes numeric and string weightages.
     */
    public void verifyCapstoneConfiguration(ScenarioContext context) {
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String courseId = context.getCourseId();
        String programTermId = context.getProgramTermId();
        String configUuid = context.getCapstoneGamificationConfigUuid();
        Integer numericConfigId = context.getCapstoneGamificationConfigNumericId();
        String rewardUuid = context.getCapstoneRewardUuid();
        String adminToken = context.getAdminToken();

        assertThat(configUuid)
                .as("the capstone configuration uuid must have been captured by the configuration steps before the "
                        + "configuration can be verified by read-back")
                .isNotBlank();
        assertThat(numericConfigId)
                .as("the NUMERIC capstone configuration id must have been captured before the rewards read-back can "
                        + "run - the rewards API keys off it")
                .isNotNull();
        assertThat(rewardUuid)
                .as("the capstone reward uuid must have been captured by the rewards step before the rewards "
                        + "read-back can match the reward by identity")
                .isNotBlank();

        log.info("==============================\nVerifying Capstone Configuration\nCapstone TermCourse: {}\n"
                        + "Capstone Section: {}\nConfiguration UUID: {}\nConfiguration (numeric id): {}\n"
                        + "Reward UUID: {}\n==============================",
                capstoneTermCourseId, capstoneSectionId, configUuid, numericConfigId, rewardUuid);

        // ---- Read 1: the termCourse detail - configuration owner, S1 chain and every configured slice ----
        Response detail = cohortClient.getTermCourseDetail(capstoneTermCourseId, adminToken);
        String detailBody = detail.getBody().asString();
        assertThat(detail.statusCode())
                .as("termCourse readback must succeed to verify the capstone configuration. Response: " + detailBody)
                .isBetween(200, 299);

        Map<String, Object> termCourse = detail.jsonPath().getMap("Data.termCourse");
        assertThat(termCourse)
                .as("termCourse readback must carry Data.termCourse to verify the capstone configuration. Response: "
                        + detailBody)
                .isNotNull();

        assertThat(termCourse.get("isGamify"))
                .as("termCourse readback reports isGamify=%s for the capstone TermCourse %s - a configuration cannot "
                        + "be trusted on a course that is no longer gamified",
                        termCourse.get("isGamify"), capstoneTermCourseId)
                .isEqualTo(Boolean.TRUE);

        assertThat(nestedUuid(termCourse, "Section"))
                .as("termCourse readback reports Section=%s for the capstone TermCourse %s, expected the capstone "
                        + "section %s - the configuration must belong to the capstone chain S1 built",
                        nestedUuid(termCourse, "Section"), capstoneTermCourseId, capstoneSectionId)
                .isEqualTo(capstoneSectionId);
        assertThat(nestedUuid(termCourse, "CourseMaster"))
                .as("termCourse readback reports CourseMaster=%s for the capstone TermCourse %s, expected the "
                        + "runtime course %s", nestedUuid(termCourse, "CourseMaster"), capstoneTermCourseId, courseId)
                .isEqualTo(courseId);
        assertThat(nestedUuid(termCourse, "ProgramTerm"))
                .as("termCourse readback reports ProgramTerm=%s for the capstone TermCourse %s, expected the runtime "
                        + "program term %s", nestedUuid(termCourse, "ProgramTerm"), capstoneTermCourseId, programTermId)
                .isEqualTo(programTermId);

        Map<String, Object> config = locateCapstoneConfiguration(termCourse, configUuid);

        List<Map<String, Object>> metrics = mapList(config.get("metrics"));
        assertThat(metrics)
                .as("termCourse readback: configuration %s must carry its metrics list", configUuid)
                .isNotNull();
        assertThat(metrics)
                .as("termCourse readback: configuration %s must carry the six configured metrics", configUuid)
                .hasSize(6);
        assertThat(metricTarget(metrics, METRIC_REVENUE))
                .as("termCourse readback: metric '%s' target", METRIC_REVENUE).isEqualTo(TARGET_REVENUE);
        assertThat(metricTarget(metrics, METRIC_NUMBER_OF_SALES))
                .as("termCourse readback: metric '%s' target", METRIC_NUMBER_OF_SALES)
                .isEqualTo(TARGET_NUMBER_OF_SALES);
        assertThat(metricTarget(metrics, METRIC_PROFIT))
                .as("termCourse readback: metric '%s' target", METRIC_PROFIT).isEqualTo(TARGET_PROFIT);

        assertThat(text(config.get("triggerInterval")))
                .as("termCourse readback: configuration %s collection interval", configUuid)
                .isEqualTo(TRIGGER_INTERVAL_DAILY);
        assertThat(DateUtils.toCalendarDate(config.get("scheduleStartDate")))
                .as("termCourse readback: configuration %s schedule start must normalise to the date that was sent "
                        + "(the read returns an ISO timestamp)", configUuid)
                .isEqualTo(sentScheduleDate(context, "scheduleStartDate"));
        assertThat(DateUtils.toCalendarDate(config.get("scheduleEndDate")))
                .as("termCourse readback: configuration %s schedule end must normalise to the date that was sent "
                        + "(the read returns an ISO timestamp)", configUuid)
                .isEqualTo(sentScheduleDate(context, "scheduleEndDate"));

        List<Map<String, Object>> dashboard = mapList(config.get("dashboardDisplay"));
        assertThat(dashboard)
                .as("termCourse readback: configuration %s must carry its dashboard display", configUuid).isNotNull();
        assertThat(dashboard)
                .as("termCourse readback: configuration %s must pin exactly the two configured dashboard metrics",
                        configUuid)
                .hasSize(2);
        assertThat(labels(dashboard, "label"))
                .as("termCourse readback: configuration %s dashboard labels", configUuid)
                .containsExactly(METRIC_REVENUE, METRIC_PROFIT);

        List<Map<String, Object>> analytics = mapList(config.get("analyticsDisplay"));
        assertThat(analytics)
                .as("termCourse readback: configuration %s must carry its analytics display", configUuid).isNotNull();
        assertThat(analytics)
                .as("termCourse readback: configuration %s must chart exactly the three configured analytics metrics",
                        configUuid)
                .hasSize(3);

        List<Map<String, Object>> rows = mapList(config.get("xByXRows"));
        assertThat(rows)
                .as("termCourse readback: configuration %s must carry its metrics-builder rows", configUuid).isNotNull();
        assertThat(rows)
                .as("termCourse readback: configuration %s must carry the three configured builder rows", configUuid)
                .hasSize(3);
        List<String> readInputTypes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            readInputTypes.add(text(row.get("inputType")));
        }
        assertThat(readInputTypes)
                .as("termCourse readback: configuration %s builder row input types", configUuid)
                .containsExactly(TYPE_AMOUNT, TYPE_COUNT, TYPE_PERCENTAGE);

        assertThat(stringList(config.get("xByXColumns")))
                .as("termCourse readback: configuration %s builder columns", configUuid)
                .containsExactlyElementsOf(buildXByXColumns());

        assertThat(text(config.get("leaderboardType")))
                .as("termCourse readback: configuration %s leaderboard type", configUuid)
                .isEqualTo(LEADERBOARD_TYPE_FORMULA);
        Map<String, Object> leaderboard = asMap(config.get("leaderboard"));
        assertThat(leaderboard)
                .as("termCourse readback: configuration %s must carry its leaderboard object", configUuid).isNotNull();
        Map<String, Object> formula = asMap(leaderboard.get("formula"));
        assertThat(formula)
                .as("termCourse readback: configuration %s leaderboard must carry its formula object", configUuid)
                .isNotNull();
        assertThat(text(formula.get("setName")))
                .as("termCourse readback: configuration %s leaderboard formula set name", configUuid)
                .isEqualTo(LEADERBOARD_FORMULA_SET_NAME);
        List<Map<String, Object>> formulaMetrics = mapList(formula.get("metrics"));
        assertThat(formulaMetrics)
                .as("termCourse readback: configuration %s leaderboard formula must carry its weighted metrics",
                        configUuid)
                .isNotNull();
        assertThat(leaderboardLabels(formulaMetrics))
                .as("termCourse readback: configuration %s leaderboard formula entries - the leaderboard-only "
                        + "pseudo-metric '%s' included", configUuid, METRIC_POINT)
                .containsExactly(METRIC_POINT, METRIC_REVENUE, METRIC_NUMBER_OF_SALES, METRIC_PROFIT);
        assertThat(leaderboardWeightages(formulaMetrics))
                .as("termCourse readback: configuration %s leaderboard weightages (compared as strings - the sent "
                        + "contract mixes numeric and string weightages)", configUuid)
                .containsExactly("10", "20", "30", "40");

        List<Map<String, Object>> resources = mapList(config.get("resources"));
        assertThat(resources)
                .as("termCourse readback: configuration %s must carry its resources", configUuid).isNotNull();
        assertThat(resources)
                .as("termCourse readback: configuration %s must carry the one configured resource folder", configUuid)
                .hasSize(1);
        assertThat(text(resources.get(0).get("folderName")))
                .as("termCourse readback: configuration %s resource folder name", configUuid)
                .isEqualTo(TestDataFactory.capstoneResourceFolderName());
        List<Map<String, Object>> folderData = mapList(resources.get(0).get("folderData"));
        assertThat(folderData)
                .as("termCourse readback: configuration %s resource folder must carry its folderData", configUuid)
                .isNotNull();
        assertThat(folderData)
                .as("termCourse readback: configuration %s resource folder must carry the one configured resource",
                        configUuid)
                .hasSize(1);
        assertThat(text(folderData.get(0).get("name")))
                .as("termCourse readback: configuration %s resource name", configUuid)
                .isEqualTo(TestDataFactory.capstoneResourceName());
        assertThat(text(folderData.get(0).get("link")))
                .as("termCourse readback: configuration %s resource link", configUuid)
                .isEqualTo(TestDataFactory.capstoneResourceLink());

        // ---- Read 2: the rewards read - the reward is a separate resource with a separate API ----
        Response rewards = capstoneClient.getGamificationRewards(numericConfigId, adminToken);
        String rewardsBody = rewards.getBody().asString();
        assertThat(rewards.statusCode())
                .as("rewards readback must succeed to verify the capstone reward. Response: " + rewardsBody)
                .isBetween(200, 299);

        List<Map<String, Object>> rewardList = mapList(rewards.jsonPath().get("Data"));
        assertThat(rewardList)
                .as("rewards readback: the rewards list (Data) must be present for configuration %d. Response: %s",
                        numericConfigId, rewardsBody)
                .isNotNull();
        assertThat(rewardList)
                .as("rewards readback: configuration %d reports no rewards, but a reward was created against it in "
                        + "this run", numericConfigId)
                .isNotEmpty();

        List<String> rewardUuidsPresent = new ArrayList<>();
        Map<String, Object> reward = null;
        for (Map<String, Object> candidate : rewardList) {
            String candidateUuid = text(candidate.get("uuid"));
            rewardUuidsPresent.add(candidateUuid);
            if (reward == null && rewardUuid.equals(candidateUuid)) {
                reward = candidate;
            }
        }
        assertThat(reward)
                .as("rewards readback: no reward matched the uuid %s created in this run. Reward uuids present: %s",
                        rewardUuid, rewardUuidsPresent)
                .isNotNull();

        assertThat(text(reward.get("rewardName")))
                .as("rewards readback: reward %s name", rewardUuid).isEqualTo(REWARD_NAME);
        assertThat(text(reward.get("coinRequiredToClaimReward")))
                .as("rewards readback: reward %s claim cost in coins", rewardUuid)
                .isEqualTo(String.valueOf(REWARD_COIN_COST));
        assertThat(text(reward.get("gamificationConfigId")))
                .as("rewards readback: reward %s must belong to the capstone configuration %d created in this run",
                        rewardUuid, numericConfigId)
                .isEqualTo(String.valueOf(numericConfigId));

        List<Map<String, Object>> questions = mapList(reward.get("questions"));
        assertThat(questions)
                .as("rewards readback: reward %s must carry its claim questions", rewardUuid).isNotNull();
        assertThat(questions)
                .as("rewards readback: reward %s must carry the three configured claim questions", rewardUuid)
                .hasSize(3);
        for (int i = 0; i < questions.size(); i++) {
            assertThat(text(questions.get(i).get("questionUuid")))
                    .as("rewards readback: reward %s claim question #%d must echo the client-minted questionUuid",
                            rewardUuid, i + 1)
                    .isNotBlank();
        }

        CapstoneJourneyReport.checkpoint(context, "Capstone Configuration Verified",
                "Configuration UUID: " + configUuid,
                "Located by identity in the course's configuration list",
                "Metrics, builder grid, dashboard, analytics, leaderboard and resources all verified as sent",
                "Verified via termCourse readback");

        CapstoneJourneyReport.checkpoint(context, "Capstone Rewards Verified",
                "Reward UUID: " + rewardUuid,
                "Reward: " + REWARD_NAME + " (" + REWARD_COIN_COST + " coins, 3 claim questions)",
                "Verified via rewards readback");

        CapstoneJourneyReport.checkpoint(context, "Capstone Configuration Complete",
                "1 gamification configuration + 1 reward created on the capstone course",
                "No program / cohort / term / section / course / student created",
                "No configuration or reward cleanup exists - the API exposes no delete for either");

        CapstoneJourneyReport.publish(context);

        log.info("==============================\nVerifying Capstone Configuration\nCapstone TermCourse: {}\n"
                        + "Configuration UUID: {}\nReward UUID: {}\n"
                        + "Result: SUCCESS (configuration confirmed by the termCourse readback and the reward by the "
                        + "rewards readback)\n==============================",
                capstoneTermCourseId, configUuid, rewardUuid);
    }

    // ---------------------------------------------------------------------------------
    // S2 internals: the single write, the read-back fallback and the lookup helpers
    // ---------------------------------------------------------------------------------

    /**
     * Returns the persisted configuration, performing the ONE aggregate POST if - and only if - no earlier
     * step in this scenario already did. This is the whole mechanism behind "six business steps, one write":
     * the configuration's identity lives in {@code ExecutionContext}, so a non-blank configuration uuid IS
     * the record that the write already happened.
     */
    private Map<String, Object> ensureCapstoneConfigurationPersisted(ScenarioContext context) {
        Map<String, Object> cached = context.getAttribute(CONFIG_PERSISTED_ATTRIBUTE);
        if (cached != null) {
            return cached;
        }
        return persistCapstoneConfiguration(context);
    }

    /**
     * Returns the persisted configuration guaranteed to carry the named slice keys, performing the aggregate
     * POST first if needed and falling back to a termCourse read-back when the configuration RESPONSE does
     * not echo a slice the calling step owns. That fallback is what keeps each step's assertion about "what
     * was persisted" honest without assuming how much of the aggregate the create response echoes back.
     *
     * @param sliceLabel business name of the calling step's slice, for the failure message
     * @param keys       the payload keys that slice needs
     */
    private Map<String, Object> requirePersistedSlices(ScenarioContext context, String sliceLabel, String... keys) {
        Map<String, Object> persisted = ensureCapstoneConfigurationPersisted(context);

        boolean complete = true;
        for (String key : keys) {
            if (persisted.get(key) == null) {
                complete = false;
                break;
            }
        }
        String source = "capstone configuration response";
        if (!complete) {
            persisted = readBackPersistedConfiguration(context);
            context.putAttribute(CONFIG_PERSISTED_ATTRIBUTE, persisted);
            source = "termCourse readback";
        }

        for (String key : keys) {
            assertThat(persisted.get(key))
                    .as("the %s slice of the capstone configuration %s is missing key '%s' in the %s - that slice of "
                            + "the aggregate configuration did not persist", sliceLabel,
                            context.getCapstoneGamificationConfigUuid(), key, source)
                    .isNotNull();
        }
        return persisted;
    }

    /**
     * Performs the stage's ONE configuration write: builds the complete aggregate payload, POSTs it, and
     * captures BOTH identifiers the rest of the stage needs - {@code Data.uuid} (how the configuration is
     * located inside the course's configuration list) and the NUMERIC {@code Data.id} (what the rewards API
     * keys off). They are not interchangeable, which is why both are asserted and stored.
     *
     * <p>Every value sent is either a stored runtime id or generated in this run: the schedule window is
     * relative to today, the resource timestamp comes from {@code Instant.now()}, and no captured uuid or
     * captured date appears anywhere.
     *
     * <p>No cleanup registration is made for the created configuration - the captured contract exposes no
     * DELETE for it (see the class Javadoc).
     */
    private Map<String, Object> persistCapstoneConfiguration(ScenarioContext context) {
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();

        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be resolved by S1 before the capstone configuration can be written")
                .isNotBlank();
        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by S1 before the capstone configuration can be written")
                .isNotBlank();

        String scheduleStartDate = SCHEDULE_DATE.format(LocalDate.now().minusDays(2));
        String scheduleEndDate = SCHEDULE_DATE.format(LocalDate.now().plusDays(2));
        String resourceTimestamp = RESOURCE_TIMESTAMP.format(Instant.now());

        Map<String, Object> payload = buildCapstoneConfigurationPayload(capstoneTermCourseId, capstoneSectionId,
                scheduleStartDate, scheduleEndDate, resourceTimestamp);
        context.putAttribute(CONFIG_REQUEST_ATTRIBUTE, payload);

        log.info("==============================\nWriting the Capstone Configuration (one aggregate request)\n"
                        + "Capstone TermCourse: {}\nCapstone Section: {}\nSchedule: {} -> {}\n"
                        + "Runtime TermCourse (must stay untouched): {}\n==============================",
                capstoneTermCourseId, capstoneSectionId, scheduleStartDate, scheduleEndDate,
                context.getTermCourseId());

        Response response = capstoneClient.createCapstoneGamification(payload, context.getAdminToken());
        String body = response.getBody().asString();

        assertThat(response.statusCode())
                .as("capstone gamification configuration creation must return 201 - actual status %d. Response: %s",
                        response.statusCode(), body)
                .isEqualTo(201);

        String configUuid = response.jsonPath().getString("Data.uuid");
        assertThat(configUuid)
                .as("the created capstone configuration must report its uuid (Data.uuid) - it is how the "
                        + "configuration is located by identity in the read-back. Response: " + body)
                .isNotBlank();

        Object rawNumericId = response.jsonPath().get("Data.id");
        assertThat(rawNumericId)
                .as("the created capstone configuration must report its NUMERIC id (Data.id) - the rewards API keys "
                        + "off it and does not accept the uuid. Response: " + body)
                .isNotNull();
        Integer numericConfigId = rawNumericId instanceof Number
                ? ((Number) rawNumericId).intValue()
                : Integer.valueOf(String.valueOf(rawNumericId).trim());
        assertThat(numericConfigId)
                .as("the created capstone configuration's numeric id must be a real identifier. Response: " + body)
                .isGreaterThan(0);

        context.setCapstoneGamificationConfigUuid(configUuid);
        context.setCapstoneGamificationConfigNumericId(numericConfigId);

        Map<String, Object> persisted = response.jsonPath().getMap("Data");
        if (persisted == null) {
            persisted = new LinkedHashMap<>();
        }
        context.putAttribute(CONFIG_PERSISTED_ATTRIBUTE, persisted);

        log.info("==============================\nWriting the Capstone Configuration (one aggregate request)\n"
                        + "Configuration UUID: {}\nConfiguration (numeric id): {}\n"
                        + "Result: created (each configuration step verifies its own slice; the stage's closing step "
                        + "verifies the whole configuration by read-back)\n==============================",
                configUuid, numericConfigId);

        return persisted;
    }

    /**
     * Re-reads the configuration from the authoritative termCourse detail endpoint and returns the entry
     * matched BY IDENTITY. Used as the fallback source for any slice the create response did not echo, and
     * it is the SAME lookup {@link #verifyCapstoneConfiguration(ScenarioContext)} uses, so the two can never
     * drift into matching the configuration by different criteria.
     */
    private Map<String, Object> readBackPersistedConfiguration(ScenarioContext context) {
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String configUuid = context.getCapstoneGamificationConfigUuid();

        assertThat(configUuid)
                .as("the capstone configuration uuid must be captured before the configuration can be read back")
                .isNotBlank();

        Response detail = cohortClient.getTermCourseDetail(capstoneTermCourseId, context.getAdminToken());
        String detailBody = detail.getBody().asString();
        assertThat(detail.statusCode())
                .as("termCourse readback must succeed to read the capstone configuration back. Response: " + detailBody)
                .isBetween(200, 299);

        Map<String, Object> termCourse = detail.jsonPath().getMap("Data.termCourse");
        assertThat(termCourse)
                .as("termCourse readback must carry Data.termCourse to read the capstone configuration back. "
                        + "Response: " + detailBody)
                .isNotNull();

        return locateCapstoneConfiguration(termCourse, configUuid);
    }

    /**
     * Locates one gamification configuration inside a termCourse read BY IDENTITY - the
     * {@code GamificationConfigs[]} entry whose {@code uuid} equals the one this run created. NEVER by array
     * index: index-based selection silently verifies a different configuration the moment a course carries
     * more than one, and the failure would read as a data mismatch rather than as a lookup bug.
     *
     * <p>On no match the failure lists every configuration uuid actually present, which distinguishes "the
     * configuration did not persist" from "it persisted on a different course".
     */
    private Map<String, Object> locateCapstoneConfiguration(Map<String, Object> termCourse, String configUuid) {
        List<Map<String, Object>> configs = mapList(termCourse.get("GamificationConfigs"));
        assertThat(configs)
                .as("termCourse readback: the course's GamificationConfigs list must be present to locate the "
                        + "capstone configuration %s", configUuid)
                .isNotNull();

        List<String> uuidsPresent = new ArrayList<>();
        Map<String, Object> match = null;
        for (Map<String, Object> candidate : configs) {
            String candidateUuid = text(candidate.get("uuid"));
            uuidsPresent.add(candidateUuid);
            if (match == null && configUuid.equals(candidateUuid)) {
                match = candidate;
            }
        }

        assertThat(match)
                .as("termCourse readback: no gamification configuration matched the uuid %s created in this run. "
                        + "Configuration uuids present: %s", configUuid, uuidsPresent)
                .isNotNull();
        return match;
    }

    /**
     * The schedule date that was actually SENT, read from this scenario's recorded request body so the
     * read-back compares against the wire value rather than against a re-computed "today ± 2", which could
     * differ if a run crossed midnight.
     */
    private static LocalDate sentScheduleDate(ScenarioContext context, String key) {
        Map<String, Object> request = context.getAttribute(CONFIG_REQUEST_ATTRIBUTE);
        assertThat(request)
                .as("the capstone configuration request body must have been recorded in this scenario before '%s' "
                        + "can be compared against what was sent - the configuration steps and their verification "
                        + "must run in the SAME scenario", key)
                .isNotNull();
        LocalDate sent = DateUtils.toCalendarDate(request.get(key));
        assertThat(sent)
                .as("the recorded capstone configuration request must carry '%s'", key)
                .isNotNull();
        return sent;
    }

    // ================= S3: the capstone team =================

    // S3 ("Admin creates a Capstone team and verifies the team configuration") CONSUMES the suite-scoped
    // state S1 and S2 produced - the capstone section, the capstone TermCourse, the capstone gamification
    // configuration, the runtime student and the admin session - and creates exactly ONE new entity: the
    // capstone team (the API's sectionGroup). No Program, Cohort, Program Term, Section, CourseMaster,
    // TermCourse or Student is created here, and no request is addressed to the runtime section or the
    // runtime TermCourse the Assignment / Session / Attendance journeys depend on.

    /**
     * Per-scenario attribute holding S3's OWN admin {@code getAllSectionGroups} response, so the admin-side
     * steps assert over the SAME evidence instead of each issuing its own read. Declared here, S3-owned, and
     * deliberately distinct from S2's {@code CONFIG_PERSISTED_ATTRIBUTE}: caching an observation WITHIN one
     * scenario is safe because producer and consumer run in the same scenario, whereas reading S2's cache
     * would be reading a bag that was emptied when this scenario started. Every consumer therefore goes
     * through {@link #adminTeamRead(ScenarioContext)}, which issues the read itself when the attribute is
     * absent - so a step run out of order still works.
     */
    private static final String S3_ADMIN_TEAM_READ_ATTRIBUTE = "capstoneS3AdminTeamRead";

    /**
     * Per-scenario attribute holding S3's OWN student-side capstone response, cached for the same reason and
     * under the same rule as {@link #S3_ADMIN_TEAM_READ_ATTRIBUTE}: an observation, never a source of
     * identity. The team identity lives in {@code ExecutionContext}.
     */
    private static final String S3_STUDENT_CAPSTONE_READ_ATTRIBUTE = "capstoneS3StudentCapstoneRead";

    /** The team status both actors' reads must report - exact lowercase, asserted by value on both sides. */
    private static final String TEAM_STATUS_ACCEPTED = "accepted";

    /** Named once so the failure messages of every later S3 step point at the same producing step. */
    private static final String TEAM_CREATION_STEP =
            "the team-creation step \"When the admin creates a Capstone team with the runtime student\" "
                    + "(createCapstoneTeamWithRuntimeStudent)";

    /**
     * Asserts the S1 + S2 + Environment Setup state this team-formation stage consumes is complete BEFORE
     * any request is issued, so a partially-run suite names the missing state and the stage that produces
     * it instead of failing inside a request - or, worse, forming a team against the wrong identity.
     *
     * <p>Unlike the earlier per-id guards in this class, the eight consumed String values and the numeric
     * configuration id are evaluated TOGETHER and reported in ONE aggregated failure: a run launched with
     * {@code -Dcucumber.filter.tags=@team-management} alone is missing EVERYTHING, and being told about one
     * blank id per re-run is what makes that diagnosis expensive. Each entry in the message carries the
     * stage that produces it (Environment Setup / S1 / S2), so the failure doubles as the fix - the tag
     * filter to re-launch with.
     *
     * <p>The runtime-identity collision guard carries the same business weight it does in
     * {@link #validateCapstoneReadyForConfiguration}: the whole point of S1 is that the capstone section
     * yields its OWN section and TermCourse, and this stage writes a team against those ids. Had either
     * converged on the runtime identity, the team would be created inside the environment the Assignment,
     * Session and Attendance journeys depend on. A runtime id that is simply ABSENT is not a collision -
     * {@code termCourseId} in particular is resolved by {@code AssignmentWorkflow}, not by Environment
     * Setup, so it is legitimately null when {@code @capstone} runs without {@code @assignment}.
     *
     * <p>Issues NO request, creates nothing, and writes NOTHING - neither to the suite-scoped
     * {@code ExecutionContext} nor to the per-scenario attribute bag. No token value reaches any log line,
     * report line or assertion message: a blank token is reported by NAME only.
     */
    public void validateCapstoneReadyForTeamFormation(ScenarioContext context) {
        String capstoneSectionId = context.getCapstoneSectionId();
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneGamificationConfigUuid = context.getCapstoneGamificationConfigUuid();
        Integer capstoneGamificationConfigNumericId = context.getCapstoneGamificationConfigNumericId();
        String programTermId = context.getProgramTermId();
        String studentUuid = context.getStudentUuid();

        String runtimeSectionId = context.getSectionId();
        String runtimeTermCourseId = context.getTermCourseId();

        // Name -> value, where the name already carries the producing stage. The VALUES are never printed:
        // two of them are session tokens, and only the names reach the failure message.
        Map<String, String> consumed = new LinkedHashMap<>();
        consumed.put("capstoneSectionId (produced by S1 - capstone outclass section creation)", capstoneSectionId);
        consumed.put("capstoneTermCourseId (produced by S1 - capstone section-courses read-back)",
                capstoneTermCourseId);
        consumed.put("capstoneGamificationConfigUuid (produced by S2 - capstone configuration)",
                capstoneGamificationConfigUuid);
        consumed.put("programTermId (produced by Environment Setup)", programTermId);
        consumed.put("studentUuid (produced by Environment Setup)", studentUuid);
        consumed.put("studentToken (produced by Environment Setup - student login)", context.getStudentToken());
        consumed.put("adminUserId (produced by Environment Setup - admin login)", context.getAdminUserId());
        consumed.put("adminToken (produced by Environment Setup - admin login)", context.getAdminToken());

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : consumed.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.trim().isEmpty()) {
                missing.add(entry.getKey());
            }
        }
        if (capstoneGamificationConfigNumericId == null || capstoneGamificationConfigNumericId <= 0) {
            missing.add("capstoneGamificationConfigNumericId (produced by S2 - capstone configuration; observed "
                    + capstoneGamificationConfigNumericId + ", must be non-null and greater than zero)");
        }

        assertThat(missing)
                .as("the capstone team-formation stage (S3) consumes state produced by earlier stages, and every "
                        + "consumed value listed here is absent, empty or out of bounds. Each is named with the stage "
                        + "that produces it, so re-launch the run with those stages in the tag filter (e.g. "
                        + "\"@environment-setup or @assignment or @capstone or @cleanup\") rather than with "
                        + "@team-management alone. Missing: %s", missing)
                .isEmpty();

        if (runtimeTermCourseId != null) {
            assertThat(capstoneTermCourseId)
                    .as("the capstone TermCourse %s has converged on the runtime TermCourse %s - the capstone identity "
                            + "IS the runtime identity that the Assignment, Session and Attendance journeys depend on, "
                            + "so forming a team here would write into the runtime environment those journeys consume",
                            capstoneTermCourseId, runtimeTermCourseId)
                    .isNotEqualTo(runtimeTermCourseId);
        }
        if (runtimeSectionId != null) {
            assertThat(capstoneSectionId)
                    .as("the capstone section %s has converged on the runtime section %s - the capstone identity IS "
                            + "the runtime identity that the Assignment, Session and Attendance journeys depend on, so "
                            + "forming a team here would write into the runtime environment those journeys consume",
                            capstoneSectionId, runtimeSectionId)
                    .isNotEqualTo(runtimeSectionId);
        }

        CapstoneJourneyReport.checkpoint(context, "Capstone Team Formation Readiness",
                "Capstone Section: " + capstoneSectionId,
                "Capstone TermCourse: " + capstoneTermCourseId,
                "Capstone GamificationConfig (numeric id): " + capstoneGamificationConfigNumericId,
                "Program Term: " + programTermId,
                "Runtime Student: " + studentUuid,
                "No request issued - suite state only");

        log.info("==============================\nCapstone Team Formation Readiness\nCapstone Section: {}\n"
                        + "Capstone TermCourse: {}\nCapstone GamificationConfig (numeric id): {}\nProgram Term: {}\n"
                        + "Runtime Student: {}\nRuntime Section (must stay untouched): {}\n"
                        + "Runtime TermCourse (must stay untouched): {}\n"
                        + "Result: capstone state ready for team formation (no request issued, nothing written)\n"
                        + "==============================",
                capstoneSectionId, capstoneTermCourseId, capstoneGamificationConfigNumericId, programTermId,
                studentUuid, runtimeSectionId, runtimeTermCourseId);
    }

    /**
     * Creates the ONE capstone team this stage adds, containing the runtime student as its single member and
     * its group representative, through a single {@code POST /api/v1/program/addStudentsInGroup}. The body is
     * built by the pure {@link #buildCapstoneTeamPayload} from the three deterministic
     * {@code TestDataFactory} team helpers and the capstone ids in the suite-scoped context; there is no
     * retry and no scenario-specific timeout - the framework's configured timeout stands.
     *
     * <p>The create response is read for IDENTITY ONLY. {@code Data} is an ARRAY even though exactly one
     * team is created, so the team is {@code Data[0]} and never {@code Data} itself - reading it as an
     * object is the mistake this method's assertions are shaped to catch. {@code gamificationConfigId} is
     * compared against {@code capstoneGamificationConfigNumericId} AS NUMBERS: the API returns a JSON number
     * and the context holds an {@code Integer}, so a String comparison would pass or fail on formatting
     * rather than on the binding it is meant to prove.
     *
     * <p>A 201 records NO checkpoint here, deliberately, for the same reason
     * {@link #addRuntimeCourseToCapstoneSection} records none: an accepted request is not evidence the team
     * persisted. The persistence verdict belongs to the separate admin read-back step, which is also where
     * the checkpoint is recorded.
     *
     * <p>{@code capstoneTeamUuid} and {@code capstoneTeamName} are written ONCE, only after every assertion
     * above has passed, and are OVERWRITTEN unconditionally: if a previous S3 run in the same suite left a
     * team id behind, the later verification steps must assert against the team this run just created, not
     * against a stale one. On any failure both stay unset, so a later step fails naming this creation step
     * rather than silently verifying a previous team.
     */
    public void createCapstoneTeamWithRuntimeStudent(ScenarioContext context) {
        String capstoneSectionId = context.getCapstoneSectionId();
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String adminUserId = context.getAdminUserId();
        String studentUuid = context.getStudentUuid();
        Integer capstoneGamificationConfigNumericId = context.getCapstoneGamificationConfigNumericId();

        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by the capstone section creation step (S1) before a capstone "
                        + "team can be created")
                .isNotBlank();
        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be resolved by the capstone section-courses read-back (S1) before a "
                        + "capstone team can be created")
                .isNotBlank();
        assertThat(adminUserId)
                .as("adminUserId must be set by admin login - it is sent as the team's createdBy")
                .isNotBlank();
        assertThat(studentUuid)
                .as("studentUuid must be set by Environment Setup - the runtime student is the team's single member "
                        + "and its group representative")
                .isNotBlank();
        assertThat(capstoneGamificationConfigNumericId)
                .as("capstoneGamificationConfigNumericId must be captured by the capstone configuration stage (S2) - "
                        + "the created team's gamificationConfigId is compared against it")
                .isNotNull();

        String teamName = TestDataFactory.capstoneTeamName();
        String teamDescription = TestDataFactory.capstoneTeamDescription();
        String representativeLabel = TestDataFactory.capstoneTeamRepresentativeLabel();

        Map<String, Object> payload = buildCapstoneTeamPayload(capstoneSectionId, capstoneTermCourseId, adminUserId,
                teamName, teamDescription, studentUuid, representativeLabel);

        log.info("==============================\nCreating Capstone Team\nCapstone Section: {}\n"
                        + "Capstone TermCourse: {}\nTeam Name: {}\nMember (runtime student): {}\n"
                        + "Runtime Section (must stay untouched): {}\n==============================",
                capstoneSectionId, capstoneTermCourseId, teamName, studentUuid, context.getSectionId());

        // NO CLEANUP REGISTRATION for the created team, and that is deliberate rather than an oversight -
        // the same shape of documented gap the gamification configuration and the reward carry (see the class
        // Javadoc). No ResourceType value represents a team, and inventing one - together with the cleanup
        // path it would need - is out of scope for this stage, so ResourceType and CleanupManager are left
        // exactly as they stand. The HAR's DELETE /api/v1/program/group/{uuid}/deleteSectionGroup stays
        // deliberately UNUSED: team deletion is out of scope, so no delete call is introduced here. Teardown
        // of the team is expected to happen transitively with the platform's section-scoped lifecycle (the
        // capstone section, and through it the runtime Cohort), and that cascade is UNVERIFIED - nothing here
        // asserts cascade behaviour in either direction.
        Response response = capstoneClient.addStudentsInGroup(payload, context.getAdminToken());
        String body = response.getBody().asString();

        assertThat(response.statusCode())
                .as("capstone team creation (POST /api/v1/program/addStudentsInGroup) must return 201. Response: "
                        + body)
                .isEqualTo(201);

        Object rawData = response.jsonPath().get("Data");
        assertThat(rawData)
                .as("the capstone team create response must carry Data as an ARRAY - the single created team is "
                        + "Data[0], never Data itself. Response: " + body)
                .isInstanceOf(List.class);

        List<Map<String, Object>> createdTeams = mapList(rawData);
        assertThat(createdTeams)
                .as("the capstone team create response must carry EXACTLY one created team in Data. Response: " + body)
                .hasSize(1);

        Map<String, Object> createdTeam = createdTeams.get(0);
        String capstoneTeamUuid = text(createdTeam.get("uuid"));
        String createdTeamName = text(createdTeam.get("name"));

        assertThat(capstoneTeamUuid)
                .as("the created capstone team must carry its identity at Data[0].uuid. Response: " + body)
                .isNotBlank();
        assertThat(createdTeamName)
                .as("the created capstone team %s must echo the name that was sent. Response: %s", capstoneTeamUuid,
                        body)
                .isEqualTo(teamName);

        Object rawConfigId = createdTeam.get("gamificationConfigId");
        assertThat(rawConfigId)
                .as("the created capstone team %s must carry gamificationConfigId as a JSON NUMBER - the numeric "
                        + "configuration id and the configuration uuid are not interchangeable. Response: %s",
                        capstoneTeamUuid, body)
                .isInstanceOf(Number.class);

        long observedConfigId = ((Number) rawConfigId).longValue();
        long expectedConfigId = capstoneGamificationConfigNumericId.longValue();
        assertThat(observedConfigId)
                .as("the created capstone team %s must carry a gamificationConfigId greater than zero",
                        capstoneTeamUuid)
                .isGreaterThan(0L);
        assertThat(observedConfigId)
                .as("the created capstone team %s is bound to GamificationConfig %d, not to the configuration the "
                        + "capstone configuration stage (S2) created (%d) - the team inherited a DIFFERENT capstone "
                        + "configuration. Both values are compared as NUMBERS, never as strings", capstoneTeamUuid,
                        observedConfigId, expectedConfigId)
                .isEqualTo(expectedConfigId);

        // Written only now, and overwritten unconditionally: a second S3 run in the same suite must verify
        // the team THIS run created, never the one the previous run left behind.
        context.setCapstoneTeamUuid(capstoneTeamUuid);
        context.setCapstoneTeamName(createdTeamName);

        log.info("==============================\nCreating Capstone Team\nTeam Name: {}\nTeam UUID: {}\n"
                        + "GamificationConfig (numeric id): {}\nMember (runtime student): {}\n"
                        + "Result: creation accepted (pending the admin getAllSectionGroups read-back)\n"
                        + "==============================",
                createdTeamName, capstoneTeamUuid, observedConfigId, studentUuid);
    }

    /**
     * The PERSISTENCE VERDICT for the created team, taken from a separate admin read rather than from the
     * create response's 201 - which is why this step, not the creation step, records the first S3 checkpoint.
     * Issues one {@code getAllSectionGroups} read against the CAPSTONE TermCourse and the CAPSTONE section,
     * and asserts the 200 BEFORE any body field is read: a field pulled out of an error payload would produce
     * a null-versus-expected failure that hides the real cause, which was the status.
     *
     * <p>The team is located by {@code uuid} equality ONLY - see {@link #findTeamByUuid} - and the uuid comes
     * from {@code ExecutionContext}, where the creation step resolved it ONCE. Nothing here re-derives the
     * identity from the list, so a second team in the same section (from a previous run, or created by
     * something else entirely) cannot be mistaken for this run's team.
     *
     * <p>On the located team three values carry business weight: {@code name} proves the team the admin sees
     * is the team this run named, {@code groupSize} 1 proves the single-member team was formed as a
     * single-member team rather than as an empty or over-filled one, and {@code status} {@code accepted}
     * proves the membership is live rather than a pending invitation.
     *
     * <p>The response is cached under {@link #S3_ADMIN_TEAM_READ_ATTRIBUTE} so the admin-side membership step
     * asserts over exactly the evidence this step verified, with no second read of its own.
     */
    public void verifyCapstoneTeamCreated(ScenarioContext context) {
        String capstoneTeamUuid = context.getCapstoneTeamUuid();
        String capstoneTeamName = context.getCapstoneTeamName();
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();

        assertThat(capstoneTeamUuid)
                .as("capstoneTeamUuid is absent - it is produced by %s, so that step either did not run or did not "
                        + "pass its assertions. Nothing can be verified against an unresolved team identity",
                        TEAM_CREATION_STEP)
                .isNotBlank();

        log.info("==============================\nVerifying the Capstone Team Was Created\nCapstone Section: {}\n"
                        + "Capstone TermCourse: {}\nExpected Team UUID: {}\nExpected Team Name: {}\n"
                        + "==============================",
                capstoneSectionId, capstoneTermCourseId, capstoneTeamUuid, capstoneTeamName);

        Response adminRead = adminTeamRead(context);
        Map<String, Object> team = findTeamByUuid(adminRead, capstoneTeamUuid);

        assertThat(text(team.get("name")))
                .as("the capstone team %s is named '%s' in the admin read but was created as '%s'", capstoneTeamUuid,
                        text(team.get("name")), capstoneTeamName)
                .isEqualTo(capstoneTeamName);

        Object rawGroupSize = team.get("groupSize");
        assertThat(rawGroupSize)
                .as("the capstone team %s must report groupSize as a JSON NUMBER in the admin read", capstoneTeamUuid)
                .isInstanceOf(Number.class);
        assertThat(((Number) rawGroupSize).longValue())
                .as("the capstone team %s must report groupSize 1 - it was created with the runtime student as its "
                        + "single member", capstoneTeamUuid)
                .isEqualTo(1L);

        assertThat(text(team.get("status")))
                .as("the capstone team %s must report status '%s' - any other status means the membership is not live",
                        capstoneTeamUuid, TEAM_STATUS_ACCEPTED)
                .isEqualTo(TEAM_STATUS_ACCEPTED);

        CapstoneJourneyReport.checkpoint(context, "Capstone Team Created",
                "Team Name: " + capstoneTeamName,
                "Team UUID: " + capstoneTeamUuid,
                "Group Size: 1, Status: " + TEAM_STATUS_ACCEPTED,
                "Verified via the admin getAllSectionGroups read-back, matched by team uuid");

        log.info("==============================\nVerifying the Capstone Team Was Created\nTeam Name: {}\n"
                        + "Team UUID: {}\nGroup Size: 1\nStatus: {}\n"
                        + "Result: SUCCESS (located by uuid in the admin getAllSectionGroups read-back)\n"
                        + "==============================",
                capstoneTeamName, capstoneTeamUuid, TEAM_STATUS_ACCEPTED);
    }

    /**
     * The ADMIN-side half of the cross-actor membership agreement: the runtime student appears exactly ONCE
     * in the created team's member list, and is the team's group representative.
     *
     * <p>Issues NO request of its own. The evidence is the admin read
     * {@link #verifyCapstoneTeamCreated} already verified, taken from the per-scenario cache through
     * {@link #adminTeamRead(ScenarioContext)} - which re-issues the read only if this step somehow runs
     * without the previous one having cached it, so the step is correct in isolation and makes no second
     * call in the normal order.
     *
     * <p>{@code Count} IS NOT A MEMBERSHIP COUNT. The admin projection's {@code Count} is the number of
     * TEAMS in the capstone section, so a section holding three teams reports {@code Count: 3} no matter how
     * many members any one team has. The membership verdict is therefore derived from the located team's
     * {@code UserGroups} array and from nothing else - reading {@code Count} as "members in my team" would
     * pass on an empty team in a busy section and fail on a correct team in an empty one.
     *
     * <p>Member identity is compared by EXACT case-sensitive String equality with no trimming (see
     * {@link #memberEntriesFor}): a uuid differing by case or by surrounding whitespace is a different
     * identity, not the same one formatted differently. On any count other than one the failure reports the
     * observed count AND every member uuid the team returned, which separates "the team is empty" from "the
     * team holds someone else" from "the student was added twice".
     */
    public void verifyAdminSeesRuntimeStudentInTeam(ScenarioContext context) {
        String capstoneTeamUuid = context.getCapstoneTeamUuid();
        String studentUuid = context.getStudentUuid();

        assertThat(capstoneTeamUuid)
                .as("capstoneTeamUuid is absent - it is produced by %s, so the admin-side membership of a team that "
                        + "was never resolved cannot be verified", TEAM_CREATION_STEP)
                .isNotBlank();
        assertThat(studentUuid)
                .as("studentUuid must be set by Environment Setup - it is the membership the admin read is searched for")
                .isNotBlank();

        Response adminRead = adminTeamRead(context);
        Map<String, Object> team = findTeamByUuid(adminRead, capstoneTeamUuid);

        List<Map<String, Object>> memberEntries = memberEntriesFor(team, studentUuid);
        List<String> memberUuidsReturned = memberUuids(team);
        assertThat(memberEntries)
                .as("the capstone team %s must carry EXACTLY one member entry for the runtime student %s in the admin "
                        + "read; %d matched. Member uuids returned in UserGroups: %s (membership is derived from "
                        + "UserGroups only - the response's Count is the number of TEAMS in the section)",
                        capstoneTeamUuid, studentUuid, memberEntries.size(), memberUuidsReturned)
                .hasSize(1);

        Map<String, Object> memberEntry = memberEntries.get(0);
        assertThat(memberEntry.get("isGroupRepresentative"))
                .as("the runtime student %s must be marked isGroupRepresentative in the capstone team %s - the team was "
                        + "created with that student as its groupRepresentative.value", studentUuid, capstoneTeamUuid)
                .isEqualTo(Boolean.TRUE);

        CapstoneJourneyReport.checkpoint(context, "Admin-Side Membership Verified",
                "Team UUID: " + capstoneTeamUuid,
                "Member UUID: " + studentUuid,
                "Group Representative: yes",
                "Verified over the cached admin getAllSectionGroups read - no additional request issued");

        log.info("==============================\nVerifying Admin-Side Capstone Team Membership\nTeam UUID: {}\n"
                        + "Member UUID: {}\nGroup Representative: yes\nMember Entries Returned: {}\n"
                        + "Result: SUCCESS (asserted over the cached admin read, zero HTTP calls in this step)\n"
                        + "==============================",
                capstoneTeamUuid, studentUuid, memberUuidsReturned.size());
    }

    /**
     * The STUDENT-side half of the cross-actor membership agreement, and the only S3 call issued as the
     * student. Proves the student's OWN capstone view exposes the SAME team the admin created, with the
     * student in it - two independent actors agreeing on one team, which is the whole point of the step. One
     * request, no retry, the framework's configured timeout.
     *
     * <p>Issued with {@code studentToken}, never {@code adminToken}. {@code adminToken} and
     * {@code adminUserId} are neither read as the credential nor written here, so the admin session is
     * exactly as it was before this step. No token value reaches any log line, report line or assertion
     * message.
     *
     * <p>The 200 is asserted BEFORE any body field is read, and the failure names the observed status with
     * the requested {@code programTermId}, {@code courseId} and {@code sectionId} - the three inputs that
     * decide which capstone view was asked for - rather than quoting a body no field was read from.
     *
     * <p>THE SAME-TEAM CROSS-CHECK IS THE ORDERING CONSTRAINT IN THIS METHOD. {@code Data.userGroup.uuid} is
     * compared against {@code capstoneTeamUuid} BEFORE any membership assertion, and on a mismatch the step
     * fails reporting both uuids and asserts NOTHING about the observed team's members: a student sitting in
     * a different team that happens to contain them would otherwise satisfy every membership assertion and
     * report a pass for the wrong team.
     *
     * <p>{@code doubtDiscussion.groupMember} is a secondary projection of the same membership and is
     * EVIDENCE ONLY - no pass or fail verdict is derived from it anywhere in this method. The verdict comes
     * from {@code Data.userGroup.UserGroups} alone.
     */
    public void verifyStudentSeesCapstoneTeamMembership(ScenarioContext context) {
        String programTermId = context.getProgramTermId();
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();
        String capstoneTeamUuid = context.getCapstoneTeamUuid();
        String capstoneTeamName = context.getCapstoneTeamName();
        String studentUuid = context.getStudentUuid();

        assertThat(capstoneTeamUuid)
                .as("capstoneTeamUuid is absent - it is produced by %s, so there is no admin-created team for the "
                        + "student-side view to be cross-checked against", TEAM_CREATION_STEP)
                .isNotBlank();
        assertThat(studentUuid)
                .as("studentUuid must be set by Environment Setup - it is the membership the student-side view is "
                        + "searched for")
                .isNotBlank();
        // Reported by NAME only: no token value is ever interpolated into a message or a log line.
        assertThat(context.getStudentToken())
                .as("studentToken must be set by Environment Setup's student login - this read is issued as the "
                        + "STUDENT, never as the admin")
                .isNotBlank();

        log.info("==============================\nVerifying Student-Side Capstone Team Membership\n"
                        + "Program Term: {}\nCapstone TermCourse (sent as courseId): {}\nCapstone Section: {}\n"
                        + "Expected Team UUID: {}\nRuntime Student: {}\nActor: the runtime STUDENT session\n"
                        + "==============================",
                programTermId, capstoneTermCourseId, capstoneSectionId, capstoneTeamUuid, studentUuid);

        // courseId carries the capstone TERMCOURSE uuid - the API's parameter name disagrees with the identity
        // it keys off, and sending the CourseMaster uuid here would be a defect.
        Response studentRead = studentCapstoneClient.getCapstoneGamificationForTerm(programTermId,
                capstoneTermCourseId, capstoneSectionId, context.getStudentToken());

        // Status FIRST, and on a non-200 ZERO body fields are read: the message carries the observed status
        // and the three requested identifiers, which is what identifies the view that was asked for.
        assertThat(studentRead.statusCode())
                .as("the student-side capstone read (GET /api/v1/program/capstoneGamifications/term/{programTermId}) "
                        + "must return 200 - observed %d for programTermId %s, courseId %s (the capstone TermCourse "
                        + "uuid) and sectionId %s. No field is read from a non-200 response body",
                        studentRead.statusCode(), programTermId, capstoneTermCourseId, capstoneSectionId)
                .isEqualTo(200);

        // Cached as soon as the status is proven, so it is one read per scenario however many steps consume it.
        context.putAttribute(S3_STUDENT_CAPSTONE_READ_ATTRIBUTE, studentRead);

        // 'cousreData' is the API's OWN misspelling of "courseData" and is addressed verbatim. Correcting it
        // would address a field the API does not expose, so every assertion below would fail on a null.
        Map<String, Object> courseData = asMap(studentRead.jsonPath().get("Data.cousreData"));
        assertThat(courseData)
                .as("the student-side capstone read must carry Data.cousreData - the API's own spelling, not "
                        + "'courseData' - for programTermId %s, courseId %s and sectionId %s", programTermId,
                        capstoneTermCourseId, capstoneSectionId)
                .isNotNull();

        assertThat(text(courseData.get("uuid")))
                .as("the student-side capstone view reports TermCourse %s while the capstone TermCourse is %s - the "
                        + "student is looking at a different course instance", text(courseData.get("uuid")),
                        capstoneTermCourseId)
                .isEqualTo(capstoneTermCourseId);
        assertThat(courseData.get("isGamify"))
                .as("the capstone TermCourse %s must report isGamify as the JSON boolean true in the student-side "
                        + "view - the gamification S2 enabled is what makes the capstone view exist",
                        capstoneTermCourseId)
                .isEqualTo(Boolean.TRUE);
        assertThat(nestedUuid(courseData, "Section"))
                .as("the student-side capstone view reports Section %s while the capstone section is %s - the student "
                        + "is looking at a different section", nestedUuid(courseData, "Section"), capstoneSectionId)
                .isEqualTo(capstoneSectionId);

        Map<String, Object> userGroup = asMap(studentRead.jsonPath().get("Data.userGroup"));
        assertThat(userGroup != null && !userGroup.isEmpty())
                .as("the student-side capstone view exposes NO team for the requested capstone TermCourse %s and "
                        + "Section %s - Data.userGroup is absent, null or an empty object - while the admin-created "
                        + "team %s was expected there", capstoneTermCourseId, capstoneSectionId, capstoneTeamUuid)
                .isTrue();

        // THE CROSS-CHECK, before any membership assertion: a different team containing this student would
        // satisfy every membership assertion below and report a pass for the wrong team.
        String observedTeamUuid = text(userGroup.get("uuid"));
        assertThat(observedTeamUuid)
                .as("the student observes capstone team %s while the admin created team %s - the two actors are "
                        + "looking at DIFFERENT teams, so no membership assertion is made against the observed team",
                        observedTeamUuid, capstoneTeamUuid)
                .isEqualTo(capstoneTeamUuid);

        assertThat(text(userGroup.get("name")))
                .as("the student-visible capstone team %s is named '%s' but was created as '%s'", capstoneTeamUuid,
                        text(userGroup.get("name")), capstoneTeamName)
                .isEqualTo(capstoneTeamName);
        assertThat(text(userGroup.get("status")))
                .as("the student-visible capstone team %s must report status '%s' - exact lowercase, by value",
                        capstoneTeamUuid, TEAM_STATUS_ACCEPTED)
                .isEqualTo(TEAM_STATUS_ACCEPTED);

        List<Map<String, Object>> memberEntries = memberEntriesFor(userGroup, studentUuid);
        List<String> memberUuidsReturned = memberUuids(userGroup);
        assertThat(memberEntries)
                .as("the student-visible capstone team %s must carry EXACTLY one member entry for the runtime student "
                        + "%s; %d matched. Member uuids returned in Data.userGroup.UserGroups: %s", capstoneTeamUuid,
                        studentUuid, memberEntries.size(), memberUuidsReturned)
                .hasSize(1);

        // doubtDiscussion.groupMember is a second projection of the same membership and is EVIDENCE ONLY -
        // no pass or fail verdict is derived from it. The verdict above comes from Data.userGroup.UserGroups.

        CapstoneJourneyReport.checkpoint(context, "Student-Side Membership Verified",
                "Team UUID: " + capstoneTeamUuid,
                "Member UUID: " + studentUuid,
                "Team Status: " + TEAM_STATUS_ACCEPTED,
                "Same team as the admin-created one (cross-checked by team uuid)",
                "Verified with the runtime STUDENT session");

        log.info("==============================\nVerifying Student-Side Capstone Team Membership\nTeam UUID: {}\n"
                        + "Member UUID: {}\nTeam Status: {}\nMember Entries Returned: {}\n"
                        + "Result: SUCCESS (the student observes the SAME team the admin created, with the runtime "
                        + "student as its single matching member)\n==============================",
                capstoneTeamUuid, studentUuid, TEAM_STATUS_ACCEPTED, memberUuidsReturned.size());
    }

    /**
     * The created team carries the SIX capstone metrics the configuration defines, field for field. Issues no
     * request of its own beyond the two shared cached reads: the TEAM-VISIBLE side is the team entry located
     * in the admin {@code getAllSectionGroups} read, and the CONFIGURATION side is {@code GamificationConfig}
     * from the student-side capstone read - both S3's own authoritative reads, never S2's per-scenario cache
     * (which is empty by the time this scenario starts).
     *
     * <p>THE COMPARISON IS ORDER-INDEPENDENT AND KEYED BY THE {@code metric} NAME FIELD. Two server-side
     * projections of the same configuration are under no contract to serialise their metrics in the same
     * order, so pairing by array position would decide the verdict by serialisation order rather than by
     * content: a pure ordering difference PASSES here (R6.4), while a genuinely different {@code target}
     * fails naming both observed values.
     *
     * <p>Every compared field keeps its ORIGINAL JSON TYPE, so the String {@code "100"} never satisfies the
     * number {@code 100} - the two projections are the same stored object seen twice, so type fidelity
     * between them is part of the fact under test. An empty-String {@code target} is compared by value as the
     * configured value (R6.5): three of the six metrics are deliberately tracked without a target, and
     * treating the empty String as "no expectation" would stop asserting them altogether.
     */
    public void verifyCapstoneTeamInheritsMetrics(ScenarioContext context) {
        String capstoneTeamUuid = context.getCapstoneTeamUuid();

        assertThat(capstoneTeamUuid)
                .as("capstoneTeamUuid is absent - it is produced by %s, so there is no team whose inherited metrics "
                        + "could be compared against the configured ones", TEAM_CREATION_STEP)
                .isNotBlank();

        log.info("==============================\nVerifying Capstone Team Metric Inheritance\nTeam UUID: {}\n"
                        + "Team-visible side: the located team in the admin getAllSectionGroups read\n"
                        + "Configuration side: Data.GamificationConfig in the student-side capstone read\n"
                        + "Comparison: order-independent set keyed by the 'metric' name, JSON types preserved\n"
                        + "==============================",
                capstoneTeamUuid);

        Map<String, Object> team = findTeamByUuid(adminTeamRead(context), capstoneTeamUuid);
        Map<String, Object> gamificationConfig = requireGamificationConfig(context);

        List<Map<String, Object>> teamMetrics = mapList(team.get("metrics"));
        List<Map<String, Object>> configMetrics = mapList(gamificationConfig.get("metrics"));

        assertMetricSetsEqual(teamMetrics, configMetrics);

        CapstoneJourneyReport.checkpoint(context, "Capstone Metrics Inherited",
                "Inherited Metrics: " + teamMetrics.size(),
                "Team UUID: " + capstoneTeamUuid,
                "Paired by the 'metric' name only - metric ordering is not part of the contract",
                "Compared fields: target, type, showDashboard, currency (original JSON types preserved)",
                "Team-visible metrics from the admin team listing, configured metrics from GamificationConfig");

        log.info("==============================\nVerifying Capstone Team Metric Inheritance\nTeam UUID: {}\n"
                        + "Inherited Metrics: {}\nMetric Names: {}\n"
                        + "Result: SUCCESS (the team's metrics equal the configured metrics as a set keyed by name, "
                        + "with every value's JSON type preserved)\n==============================",
                capstoneTeamUuid, teamMetrics.size(), metricNames(teamMetrics));
    }

    /**
     * THE FINAL S3 STEP: the created team's configuration echo equals the capstone gamification configuration
     * on exactly the 9 fields the API genuinely echoes on both sides, and on ZERO others. Issues no request
     * of its own beyond the two shared cached reads - the team entry located in the admin team listing
     * (team-visible side) and {@code Data.GamificationConfig} from the student-side capstone read
     * (configuration side). Both are S3's OWN authoritative reads; no value cached by S2 in the per-scenario
     * attribute bag is substituted for a configuration value (R7.10, R8.2).
     *
     * <p>IDENTIFIER DISCIPLINE. The configuration has TWO identities and they are not interchangeable: the
     * numeric {@code id} the team object echoes as {@code gamificationConfigId}, and the uuid the
     * configuration endpoints expose. The compared pair is numeric-to-numeric, S2's
     * {@code capstoneGamificationConfigNumericId} is the numeric identity named in the report, and
     * {@code capstoneGamificationConfigUuid} is used ONLY where the API exposes the configuration's uuid.
     * Interchanging them is a defect, not a formatting choice (R7.5).
     *
     * <p>Exactly 6 fields are EXCLUDED - see {@link #CONFIGURATION_EXCLUSION_LIST}, where each carries its
     * HAR-observed rationale adjacent to it. The step PASSES when those 6 differ in value or are absent:
     * they are projection differences, not retention failures, and asserting them would produce a permanent
     * false failure.
     *
     * <p>Every remaining pair is evaluated before anything fails, so one run reports the COMPLETE set of
     * mismatches - each naming the field, the team-visible value with its JSON type and the configuration
     * value with its JSON type - instead of one mismatch per re-run. A field ABSENT from one object is
     * reported as an absence naming which object omitted it, distinct from a field present with a null value
     * (R7.8).
     *
     * <p>This is where the capstone journey report is PUBLISHED, once, because this is S3's last step.
     */
    public void verifyCapstoneTeamRetainsConfiguration(ScenarioContext context) {
        String capstoneTeamUuid = context.getCapstoneTeamUuid();
        // The NUMERIC configuration identity (S2), used wherever a numeric configuration id is required. The
        // uuid below is used ONLY where the API exposes the configuration's uuid - the two are not swappable.
        Integer capstoneGamificationConfigNumericId = context.getCapstoneGamificationConfigNumericId();
        String capstoneGamificationConfigUuid = context.getCapstoneGamificationConfigUuid();

        assertThat(capstoneTeamUuid)
                .as("capstoneTeamUuid is absent - it is produced by %s, so there is no team whose configuration echo "
                        + "could be compared against the configured one", TEAM_CREATION_STEP)
                .isNotBlank();

        log.info("==============================\nVerifying Capstone Team Configuration Retention\nTeam UUID: {}\n"
                        + "Capstone GamificationConfig (numeric id): {}\nCapstone GamificationConfig (uuid): {}\n"
                        + "Compared pairs: gamificationConfigId, metrics, leaderboard, xByXRows, xByXColumns, "
                        + "resources, scheduleStartDate, scheduleEndDate, allowTeamFormation\n"
                        + "Excluded fields ({}): {}\n==============================",
                capstoneTeamUuid, capstoneGamificationConfigNumericId, capstoneGamificationConfigUuid,
                CONFIGURATION_EXCLUSION_LIST.size(), CONFIGURATION_EXCLUSION_LIST);

        Map<String, Object> team = findTeamByUuid(adminTeamRead(context), capstoneTeamUuid);
        Map<String, Object> gamificationConfig = requireGamificationConfig(context);

        assertRetainedConfiguration(team, gamificationConfig);

        CapstoneJourneyReport.checkpoint(context, "Capstone Configuration Retained",
                "Team UUID: " + capstoneTeamUuid,
                "Capstone GamificationConfig (numeric id): " + capstoneGamificationConfigNumericId,
                "Compared field groups: configuration id (as numbers), metrics (order-independent set), "
                        + "leaderboard (JSON-type-strict), xByXRows / xByXColumns / resources (index-wise), "
                        + "scheduleStartDate / scheduleEndDate (exact Strings), allowTeamFormation (value + type)",
                "Excluded fields: " + CONFIGURATION_EXCLUSION_LIST.size() + " ("
                        + String.join(", ", CONFIGURATION_EXCLUSION_LIST) + ")",
                "Compared read-against-read: the admin team listing vs GamificationConfig - no S2-cached value "
                        + "and no literal date");

        // S3's last step, so the accumulated capstone block is attached exactly once, here.
        CapstoneJourneyReport.publish(context);

        log.info("==============================\nVerifying Capstone Team Configuration Retention\nTeam UUID: {}\n"
                        + "Compared Pairs: 9\nExcluded Fields: {}\n"
                        + "Result: SUCCESS (the team's configuration echo matches the capstone gamification "
                        + "configuration on every compared pair, with JSON types preserved)\n"
                        + "==============================",
                capstoneTeamUuid, CONFIGURATION_EXCLUSION_LIST.size());
    }

    // ---------------------------------------------------------------------------------
    // S3 internals: the one shared admin read and the two uuid-only lookup helpers
    // ---------------------------------------------------------------------------------

    /**
     * Returns S3's admin {@code getAllSectionGroups} response - from the per-scenario cache when an earlier
     * S3 step already issued it, otherwise by issuing it here. One read per scenario however many steps
     * consume it, and every one of them correct when run in isolation: the cache is an optimisation, never a
     * precondition. This is intra-S3 reuse and is NOT a dependency on S2's per-scenario cache, which is
     * empty by the time this scenario starts.
     *
     * <p>The 200 is asserted before the response is cached or returned, so no consumer can read a field out
     * of an error payload.
     */
    private Response adminTeamRead(ScenarioContext context) {
        Response cached = context.getAttribute(S3_ADMIN_TEAM_READ_ATTRIBUTE);
        if (cached != null) {
            return cached;
        }

        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();

        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be resolved by S1 before the admin capstone team read can be issued")
                .isNotBlank();
        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by S1 before the admin capstone team read can be issued")
                .isNotBlank();

        Response response = capstoneClient.getAllSectionGroups(capstoneTermCourseId, capstoneSectionId,
                context.getAdminToken());

        assertThat(response.statusCode())
                .as("the admin capstone team read (GET .../termCourse/%s/section/%s/getAllSectionGroups) must return "
                        + "200 - observed %d. No body field is read on any other status", capstoneTermCourseId,
                        capstoneSectionId, response.statusCode())
                .isEqualTo(200);

        context.putAttribute(S3_ADMIN_TEAM_READ_ATTRIBUTE, response);
        return response;
    }

    /**
     * Selects the capstone team entry for one team uuid out of an admin {@code getAllSectionGroups} response.
     * Matching is on {@code uuid} equality ONLY - never on {@code name} (neither unique nor stable, so a
     * same-named team from a previous run would satisfy it), never on array POSITION (the projection's order
     * is not part of the contract) and never through {@code Count} (which counts teams in the section, not
     * members and not a match).
     *
     * <p>Deliberately mirrors {@link #findSectionCourseByCourseMaster}'s discipline, including its failure
     * reporting: on no match the message carries the expected uuid, EVERY team uuid actually present in
     * {@code Data} and the reported {@code Count} - enough to tell "the create did not persist" apart from
     * "the section holds other teams but not this one" or "the projection is paginated past it", rather than
     * a bare "not found".
     */
    private Map<String, Object> findTeamByUuid(Response adminRead, String teamUuid) {
        List<Map<String, Object>> teams = mapList(adminRead.jsonPath().get("Data"));
        // Count is the number of TEAMS in the capstone section - reported for diagnosis only, never used to
        // decide a match and never read as a membership count.
        Object count = adminRead.jsonPath().get("Count");

        assertThat(teams)
                .as("the capstone section's team list must be present at Data to match team %s. Response: %s",
                        teamUuid, adminRead.getBody().asString())
                .isNotNull();

        List<String> teamUuidsPresent = new ArrayList<>();
        Map<String, Object> match = null;
        for (Map<String, Object> candidate : teams) {
            String candidateUuid = text(candidate.get("uuid"));
            teamUuidsPresent.add(candidateUuid);
            if (match == null && teamUuid.equals(candidateUuid)) {
                match = candidate;
            }
        }

        assertThat(match)
                .as("no capstone team in the admin getAllSectionGroups read carries uuid %s. Team uuids returned in "
                        + "Data: %s. Count (the number of TEAMS in the capstone section): %s", teamUuid,
                        teamUuidsPresent, count)
                .isNotNull();

        return match;
    }

    /**
     * The member entries of one team that identify a given student, out of the team's {@code UserGroups}
     * array. Works for BOTH sides of the cross-actor check because both projections carry membership the same
     * way: the admin read's {@code Data[i].UserGroups} and the student read's
     * {@code Data.userGroup.UserGroups}.
     *
     * <p>Comparison is EXACT case-sensitive String equality with NO trimming: a uuid that differs by case or
     * by surrounding whitespace is a different identity, and normalising it here would let a near-miss pass
     * as a match. Returns every matching entry rather than the first, so the caller can tell "no member" from
     * "one member" from "the same student recorded twice" - a duplicate membership is a defect, not a
     * detail, so it must be visible instead of being swallowed by a first-match lookup.
     *
     * <p>An absent or non-array {@code UserGroups} yields an EMPTY list rather than a null or an exception,
     * so the caller's single count assertion reports it the same way it reports any other wrong count.
     */
    private static List<Map<String, Object>> memberEntriesFor(Map<String, Object> team, String studentUuid) {
        List<Map<String, Object>> matches = new ArrayList<>();
        List<Map<String, Object>> entries = mapList(team.get("UserGroups"));
        if (entries == null) {
            return matches;
        }
        for (Map<String, Object> entry : entries) {
            if (studentUuid.equals(nestedUuid(entry, "User"))) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /**
     * Every member uuid a team's {@code UserGroups} returns, in order - the diagnostic companion to
     * {@link #memberEntriesFor}, so a wrong member count reports WHO was actually in the team instead of only
     * how many. A member entry with no {@code User} association contributes a null, which is itself the
     * finding.
     */
    private static List<String> memberUuids(Map<String, Object> team) {
        List<String> uuids = new ArrayList<>();
        List<Map<String, Object>> entries = mapList(team.get("UserGroups"));
        if (entries == null) {
            return uuids;
        }
        for (Map<String, Object> entry : entries) {
            uuids.add(nestedUuid(entry, "User"));
        }
        return uuids;
    }

    /**
     * Returns S3's student-side capstone response - from the per-scenario cache when the student-side
     * membership step already issued it, otherwise by issuing it here. The exact counterpart of
     * {@link #adminTeamRead(ScenarioContext)} and for the same reason: one read per scenario however many
     * steps consume it, and every consumer still correct when its step runs out of order, because the cache
     * is an optimisation and never a precondition.
     *
     * <p>Issued with {@code studentToken}, never {@code adminToken} - this is the student's own view of the
     * capstone - and no token value reaches a log line, a report line or an assertion message. The 200 is
     * asserted before the response is cached or returned, so no consumer can read a configuration field out
     * of an error payload (R8.3).
     *
     * <p>This is intra-S3 reuse. It is NOT a dependency on S2's per-scenario cache, which is empty by the
     * time this scenario starts and is never read for a configuration value (R7.10, R8.2).
     */
    private Response studentCapstoneRead(ScenarioContext context) {
        Response cached = context.getAttribute(S3_STUDENT_CAPSTONE_READ_ATTRIBUTE);
        if (cached != null) {
            return cached;
        }

        String programTermId = context.getProgramTermId();
        String capstoneTermCourseId = context.getCapstoneTermCourseId();
        String capstoneSectionId = context.getCapstoneSectionId();

        assertThat(programTermId)
                .as("programTermId must be set by Environment Setup before the student-side capstone read can be "
                        + "issued")
                .isNotBlank();
        assertThat(capstoneTermCourseId)
                .as("capstoneTermCourseId must be resolved by S1 before the student-side capstone read can be issued")
                .isNotBlank();
        assertThat(capstoneSectionId)
                .as("capstoneSectionId must be captured by S1 before the student-side capstone read can be issued")
                .isNotBlank();
        // Reported by NAME only - no token value is ever interpolated into a message.
        assertThat(context.getStudentToken())
                .as("studentToken must be set by Environment Setup's student login - the student-side capstone read "
                        + "is issued as the STUDENT, never as the admin")
                .isNotBlank();

        // courseId carries the capstone TERMCOURSE uuid, matching the student-side membership step.
        Response response = studentCapstoneClient.getCapstoneGamificationForTerm(programTermId, capstoneTermCourseId,
                capstoneSectionId, context.getStudentToken());

        assertThat(response.statusCode())
                .as("the student-side capstone read (GET /api/v1/program/capstoneGamifications/term/{programTermId}) "
                        + "must return 200 - observed %d for programTermId %s, courseId %s (the capstone TermCourse "
                        + "uuid) and sectionId %s. No field is read from a non-200 response body",
                        response.statusCode(), programTermId, capstoneTermCourseId, capstoneSectionId)
                .isEqualTo(200);

        context.putAttribute(S3_STUDENT_CAPSTONE_READ_ATTRIBUTE, response);
        return response;
    }

    /**
     * The CONFIGURATION side of both comparisons: {@code Data.GamificationConfig} out of S3's student-side
     * capstone read. Fails naming WHICH authoritative read is unavailable rather than comparing against a
     * null or quietly falling back - and it never falls back to the configuration S2 cached in the
     * per-scenario attribute bag, which is exactly the substitution R7.10 forbids.
     */
    private Map<String, Object> requireGamificationConfig(ScenarioContext context) {
        Response studentRead = studentCapstoneRead(context);
        Map<String, Object> gamificationConfig = asMap(studentRead.jsonPath().get("Data.GamificationConfig"));

        assertThat(gamificationConfig)
                .as("the authoritative read that carries the capstone configuration - the student-side capstone read "
                        + "GET /api/v1/program/capstoneGamifications/term/{programTermId} - returned no "
                        + "Data.GamificationConfig object, so there is no configuration side to compare the team "
                        + "against. No value cached by S2 is substituted for it")
                .isNotNull();

        return gamificationConfig;
    }

    // ---------------------------------------------------------------------------------
    // S3 comparison model: the metric set comparator and the 9-pair configuration
    // comparator - both PURE and both JSON-TYPE-STRICT, because the two sides are two
    // server-side projections of the SAME stored object, so type fidelity between them
    // is itself the fact under test
    // ---------------------------------------------------------------------------------

    /** The capstone configuration defines exactly six metrics, so both projections must carry six. */
    private static final int EXPECTED_CAPSTONE_METRIC_COUNT = 6;

    /**
     * The fields compared on two metric entries paired by {@code metric} name. The name itself is the key, so
     * it is not in this list; everything else a metric object carries is.
     */
    private static final List<String> METRIC_COMPARED_FIELDS =
            Collections.unmodifiableList(Arrays.asList("target", "type", "showDashboard", "currency"));

    /**
     * The 6 fields EXCLUDED from the configuration comparison, each with the HAR-observed difference that
     * earns its exclusion. These are PROJECTION differences between the team object and the configuration
     * object - not retention failures - so comparing them would produce a permanent false failure. The step
     * passes when they differ in value or are absent (R7.3, R7.4).
     */
    private static final List<String> CONFIGURATION_EXCLUSION_LIST = Collections.unmodifiableList(Arrays.asList(
            // HAR: null on the team object vs "formula" on the configuration object.
            "leaderboardType",
            // HAR: null on the team object vs "daily" on the configuration object.
            "triggerInterval",
            // HAR: null on the team object vs the empty String "" on the configuration object.
            "triggerDay",
            // HAR: null on the team object vs the empty String "" on the configuration object.
            "allowSkipUntil",
            // HAR: ABSENT from the team object, present on the configuration object.
            "analyticsDisplay",
            // HAR: ABSENT from the team object, present on the configuration object.
            "dashboardDisplay"));

    /**
     * Asserts the team's metric collection equals the configured metric collection as a SET keyed by the
     * {@code metric} name, failing ONCE with every difference found (R6.6). Thin by design: the comparison
     * itself lives in {@link #metricSetDifferences} so the configuration comparator can fold the same
     * differences into its own batch report instead of reimplementing the pairing.
     */
    private static void assertMetricSetsEqual(List<Map<String, Object>> teamMetrics,
                                              List<Map<String, Object>> configMetrics) {
        List<String> differences = metricSetDifferences(teamMetrics, configMetrics);

        assertThat(differences)
                .as("the capstone team must inherit exactly the %d configured metrics: both collections compared as "
                        + "SETS keyed by the 'metric' name, with target, type, showDashboard and currency equal and "
                        + "their original JSON types preserved. Metric ORDER is not part of the contract, so an "
                        + "ordering-only difference is not listed here. Differences found: %s",
                        EXPECTED_CAPSTONE_METRIC_COUNT, differences)
                .isEmpty();
    }

    /**
     * Every difference between a team-visible metric collection and a configured metric collection: the
     * cardinality of each side, the names present in one side only, and - per shared name - each of
     * {@code target}, {@code type}, {@code showDashboard} and {@code currency} whose value or JSON type
     * differs, with BOTH observed values.
     *
     * <p>PAIRING IS BY NAME ONLY. Array position is never consulted, so two collections carrying the same six
     * metrics in different orders produce an EMPTY difference list (R6.4). An empty-String {@code target} is
     * compared by value like any other value, so a metric tracked without a target is still asserted (R6.5).
     *
     * <p>Pure: no context, no request, no assertion - it reports, the caller decides.
     */
    private static List<String> metricSetDifferences(List<Map<String, Object>> teamMetrics,
                                                     List<Map<String, Object>> configMetrics) {
        List<String> differences = new ArrayList<>();

        if (teamMetrics == null) {
            differences.add("metrics: the team-visible object exposes no metrics ARRAY (absent, null or not an array)");
        }
        if (configMetrics == null) {
            differences.add("metrics: the configuration object exposes no metrics ARRAY (absent, null or not an "
                    + "array)");
        }
        if (teamMetrics == null || configMetrics == null) {
            return differences;
        }

        if (teamMetrics.size() != EXPECTED_CAPSTONE_METRIC_COUNT) {
            differences.add("metrics: the team-visible collection holds " + teamMetrics.size() + " metrics, expected "
                    + EXPECTED_CAPSTONE_METRIC_COUNT);
        }
        if (configMetrics.size() != EXPECTED_CAPSTONE_METRIC_COUNT) {
            differences.add("metrics: the configuration collection holds " + configMetrics.size() + " metrics, "
                    + "expected " + EXPECTED_CAPSTONE_METRIC_COUNT);
        }

        Map<String, Map<String, Object>> teamByName = indexMetricsByName(teamMetrics, "team-visible", differences);
        Map<String, Map<String, Object>> configByName = indexMetricsByName(configMetrics, "configuration",
                differences);

        for (String name : teamByName.keySet()) {
            if (!configByName.containsKey(name)) {
                differences.add("metric '" + name + "': present in the TEAM-VISIBLE collection only");
            }
        }
        for (String name : configByName.keySet()) {
            if (!teamByName.containsKey(name)) {
                differences.add("metric '" + name + "': present in the CONFIGURATION collection only");
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : teamByName.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> configMetric = configByName.get(name);
            if (configMetric == null) {
                continue;
            }
            Map<String, Object> teamMetric = entry.getValue();
            for (String field : METRIC_COMPARED_FIELDS) {
                boolean teamHas = teamMetric.containsKey(field);
                boolean configHas = configMetric.containsKey(field);
                if (!teamHas || !configHas) {
                    differences.add("metric '" + name + "' field '" + field + "': ABSENT from the "
                            + (!teamHas && !configHas
                                    ? "team-visible metric AND the configuration metric"
                                    : !teamHas
                                            ? "team-visible metric (configuration value "
                                                    + describe(configMetric.get(field)) + ")"
                                            : "configuration metric (team-visible value "
                                                    + describe(teamMetric.get(field)) + ")")
                            + " - an absent field is not the same as a field present with a null value");
                } else if (!typeStrictEquals(teamMetric.get(field), configMetric.get(field))) {
                    differences.add("metric '" + name + "' field '" + field + "': team-visible "
                            + describe(teamMetric.get(field)) + " vs configuration "
                            + describe(configMetric.get(field)));
                }
            }
        }

        return differences;
    }

    /**
     * Indexes a metric collection by its {@code metric} name, preserving encounter order for readable
     * reporting. A duplicate name or a nameless entry is recorded as a difference rather than silently
     * overwriting an entry: either one makes pairing by name ambiguous, which is a finding in itself.
     */
    private static Map<String, Map<String, Object>> indexMetricsByName(List<Map<String, Object>> metrics,
                                                                      String sideLabel, List<String> differences) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> entry : metrics) {
            String name = text(entry.get("metric"));
            if (name == null) {
                differences.add("metrics: the " + sideLabel + " collection holds an entry with no 'metric' name "
                        + "field, so it cannot be paired by name");
                continue;
            }
            if (byName.put(name, entry) != null) {
                differences.add("metrics: the metric name '" + name + "' appears more than once in the " + sideLabel
                        + " collection, so the two collections cannot be paired by name unambiguously");
            }
        }
        return byName;
    }

    /**
     * Asserts the team's configuration echo equals the capstone configuration on EXACTLY the 9 compared pairs
     * and on nothing else, failing ONCE with every mismatch found (R7.6).
     *
     * <p>The 6 {@link #CONFIGURATION_EXCLUSION_LIST} fields are never addressed here, which is what makes the
     * exclusion real rather than documented: the step passes when they differ or are absent.
     *
     * <p>Pure: no context, no request, and it reads nothing beyond the two maps handed to it.
     */
    private static void assertRetainedConfiguration(Map<String, Object> team, Map<String, Object> config) {
        List<String> mismatches = new ArrayList<>();

        // 1. The configuration identity, compared as NUMBERS - the team echoes the numeric id, never the uuid.
        compareConfigurationId(mismatches, team, config);

        // 2. metrics - order-independent set keyed by the 'metric' name. Delegated to the metric comparator so
        // this method holds exactly one definition of metric equality (R7.9).
        if (bothExpose(mismatches, "metrics", team, "metrics", config, "metrics")) {
            mismatches.addAll(metricSetDifferences(mapList(team.get("metrics")), mapList(config.get("metrics"))));
        }

        // 3. leaderboard - the WHOLE formula object, JSON-TYPE-STRICT. The authored formula deliberately mixes
        // numeric weightages (10, 20, 30) with the String "40", so the number 40 must NOT satisfy "40" and a
        // type difference is a mismatch even when the textual values are equal (R7.2). This DELIBERATELY
        // differs from S2's leaderboard check, which compares weightages as Strings because it compares what
        // it SENT against what was persisted; S3 compares two server-side projections of the same stored
        // object, so type fidelity between them is exactly the fact under test.
        compareTypeStrict(mismatches, "leaderboard", team, "leaderboard", config, "leaderboard");

        // 4-6. Index-wise collections: the HAR shows a stable single-element or fixed authored order, so a
        // positional difference here is a genuine difference rather than a serialisation detail (R7.9).
        compareIndexWise(mismatches, "xByXRows", team, "xByXRows", config, "xByXRows");
        compareIndexWise(mismatches, "xByXColumns", team, "xByXColumns", config, "xByXColumns");
        compareIndexWise(mismatches, "resources", team, "resources", config, "resources");

        // 7-8. The schedule window, compared as EXACT Strings: zero date parsing, zero format normalization and
        // zero timezone conversion, so a silently re-rendered timestamp is reported instead of being absorbed.
        compareExactString(mismatches, "scheduleStartDate", team, "scheduleStartDate", config, "scheduleStartDate");
        compareExactString(mismatches, "scheduleEndDate", team, "scheduleEndDate", config, "scheduleEndDate");

        // 9. allowTeamFormation - value AND JSON type, so the String "false" never satisfies the boolean false.
        compareTypeStrict(mismatches, "allowTeamFormation", team, "allowTeamFormation", config,
                "allowTeamFormation");

        assertThat(mismatches)
                .as("the capstone team must retain the configured capstone configuration on exactly 9 compared pairs "
                        + "(gamificationConfigId as numbers, metrics as an order-independent set, leaderboard as a "
                        + "JSON-type-strict object, xByXRows / xByXColumns / resources index-wise, scheduleStartDate "
                        + "and scheduleEndDate as exact Strings, allowTeamFormation by value and type). The %d "
                        + "documented exclusions %s are NOT compared and are not listed here. Mismatches found: %s",
                        CONFIGURATION_EXCLUSION_LIST.size(), CONFIGURATION_EXCLUSION_LIST, mismatches)
                .isEmpty();
    }

    /**
     * The configuration identity pair: the team's {@code gamificationConfigId} against
     * {@code GamificationConfig.id}, compared as NUMBERS. Both sides must expose it as a JSON number - a
     * String-shaped id or a uuid in a numeric field is reported as a mismatch naming the observed types,
     * because interchanging the numeric configuration id with the configuration uuid is a defect (R7.5).
     */
    private static void compareConfigurationId(List<String> mismatches, Map<String, Object> team,
                                               Map<String, Object> config) {
        String label = "gamificationConfigId <-> GamificationConfig.id";
        if (!bothExpose(mismatches, label, team, "gamificationConfigId", config, "id")) {
            return;
        }

        Object teamValue = team.get("gamificationConfigId");
        Object configValue = config.get("id");
        if (!(teamValue instanceof Number) || !(configValue instanceof Number)) {
            mismatches.add(label + ": both sides must expose the configuration identity as a JSON NUMBER - "
                    + "team-visible " + describe(teamValue) + " vs configuration " + describe(configValue)
                    + " (the numeric configuration id and the configuration uuid are not interchangeable)");
            return;
        }
        if (!typeStrictEquals(teamValue, configValue)) {
            mismatches.add(label + ": team-visible " + describe(teamValue) + " vs configuration "
                    + describe(configValue) + " (compared as numbers)");
        }
    }

    /**
     * Compares one pair with full JSON-type strictness, whether it is a scalar or a whole nested object.
     * Records at most one mismatch, carrying both observed values WITH their JSON types.
     */
    private static void compareTypeStrict(List<String> mismatches, String label, Map<String, Object> team,
                                          String teamKey, Map<String, Object> config, String configKey) {
        if (!bothExpose(mismatches, label, team, teamKey, config, configKey)) {
            return;
        }
        Object teamValue = team.get(teamKey);
        Object configValue = config.get(configKey);
        if (!typeStrictEquals(teamValue, configValue)) {
            mismatches.add(label + ": team-visible " + describe(teamValue) + " vs configuration "
                    + describe(configValue) + " (value and JSON type must both match)");
        }
    }

    /**
     * Compares one pair as EXACT Strings - no parsing, no reformatting, no timezone conversion - so a value
     * that only looks equivalent is reported rather than normalised into a pass.
     */
    private static void compareExactString(List<String> mismatches, String label, Map<String, Object> team,
                                           String teamKey, Map<String, Object> config, String configKey) {
        if (!bothExpose(mismatches, label, team, teamKey, config, configKey)) {
            return;
        }
        Object teamValue = team.get(teamKey);
        Object configValue = config.get(configKey);
        if (!(teamValue instanceof String) || !(configValue instanceof String)) {
            mismatches.add(label + ": both sides must expose the value as a JSON String - team-visible "
                    + describe(teamValue) + " vs configuration " + describe(configValue));
            return;
        }
        if (!teamValue.equals(configValue)) {
            mismatches.add(label + ": team-visible " + describe(teamValue) + " vs configuration "
                    + describe(configValue) + " (compared as exact Strings - no date, format or timezone "
                    + "normalization)");
        }
    }

    /**
     * Compares two collections INDEX-WISE and type-strictly, reporting the differing index rather than a
     * whole-array inequality, so a one-entry difference in a long array is readable.
     */
    private static void compareIndexWise(List<String> mismatches, String label, Map<String, Object> team,
                                         String teamKey, Map<String, Object> config, String configKey) {
        if (!bothExpose(mismatches, label, team, teamKey, config, configKey)) {
            return;
        }

        Object teamRaw = team.get(teamKey);
        Object configRaw = config.get(configKey);
        if (!(teamRaw instanceof List) || !(configRaw instanceof List)) {
            mismatches.add(label + ": both sides must expose a JSON ARRAY - team-visible " + describe(teamRaw)
                    + " vs configuration " + describe(configRaw));
            return;
        }

        List<?> teamEntries = (List<?>) teamRaw;
        List<?> configEntries = (List<?>) configRaw;
        if (teamEntries.size() != configEntries.size()) {
            mismatches.add(label + ": the team-visible array holds " + teamEntries.size()
                    + " entries while the configuration array holds " + configEntries.size());
            return;
        }
        for (int index = 0; index < teamEntries.size(); index++) {
            if (!typeStrictEquals(teamEntries.get(index), configEntries.get(index))) {
                mismatches.add(label + "[" + index + "]: team-visible " + describe(teamEntries.get(index))
                        + " vs configuration " + describe(configEntries.get(index)));
            }
        }
    }

    /**
     * Whether BOTH objects expose the field at all. An ABSENT field is recorded as an absence naming the
     * object that omitted it, which is deliberately distinct from a field present with a {@code null} value
     * (R7.8): "the projection does not carry this field" and "the projection carries it as null" are
     * different findings with different causes, and collapsing them hides which one occurred.
     *
     * @return true when both sides expose the field, so the caller can go on to compare the values
     */
    private static boolean bothExpose(List<String> mismatches, String label, Map<String, Object> team, String teamKey,
                                      Map<String, Object> config, String configKey) {
        boolean teamHas = team.containsKey(teamKey);
        boolean configHas = config.containsKey(configKey);
        if (teamHas && configHas) {
            return true;
        }

        String detail;
        if (!teamHas && !configHas) {
            detail = "ABSENT from the team-visible object AND from the configuration object";
        } else if (!teamHas) {
            detail = "ABSENT from the team-visible object (the configuration object carries it as "
                    + describe(config.get(configKey)) + ")";
        } else {
            detail = "ABSENT from the configuration object (the team-visible object carries it as "
                    + describe(team.get(teamKey)) + ")";
        }
        mismatches.add(label + ": " + detail + " - an absent field is not the same as a field present with a null "
                + "value");
        return false;
    }

    /**
     * JSON-TYPE-STRICT deep equality. The type CATEGORY must match before any value is compared, so the
     * String {@code "40"} is never satisfied by the number {@code 40} and the String {@code "100"} is never
     * satisfied by the number {@code 100} - the distinction the leaderboard and metric comparisons exist to
     * make (R6.3, R7.2).
     *
     * <p>Within the number category the comparison is by numeric VALUE rather than by boxed class, so an
     * Integer-vs-Long difference invented by two JSON parses of the same number is not reported as a
     * mismatch. That is a parser artefact, not a difference between the two projections; the String-vs-number
     * distinction the requirement is about is preserved by the category check above it.
     */
    private static boolean typeStrictEquals(Object teamValue, Object configValue) {
        if (teamValue == null || configValue == null) {
            return teamValue == null && configValue == null;
        }
        if (teamValue instanceof String) {
            return configValue instanceof String && teamValue.equals(configValue);
        }
        if (teamValue instanceof Boolean) {
            return configValue instanceof Boolean && teamValue.equals(configValue);
        }
        if (teamValue instanceof Number) {
            return configValue instanceof Number
                    && new BigDecimal(String.valueOf(teamValue))
                            .compareTo(new BigDecimal(String.valueOf(configValue))) == 0;
        }
        if (teamValue instanceof Map) {
            if (!(configValue instanceof Map)) {
                return false;
            }
            Map<?, ?> teamMap = (Map<?, ?>) teamValue;
            Map<?, ?> configMap = (Map<?, ?>) configValue;
            if (!teamMap.keySet().equals(configMap.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : teamMap.entrySet()) {
                if (!typeStrictEquals(entry.getValue(), configMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (teamValue instanceof List) {
            if (!(configValue instanceof List)) {
                return false;
            }
            List<?> teamEntries = (List<?>) teamValue;
            List<?> configEntries = (List<?>) configValue;
            if (teamEntries.size() != configEntries.size()) {
                return false;
            }
            for (int index = 0; index < teamEntries.size(); index++) {
                if (!typeStrictEquals(teamEntries.get(index), configEntries.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return teamValue.equals(configValue);
    }

    /**
     * Renders a value WITH its JSON type for a failure message, so "40 vs 40" can never be the whole story:
     * Strings are quoted and every value is tagged, which is what makes a type-only mismatch readable.
     */
    private static String describe(Object value) {
        if (value == null) {
            return "null (null)";
        }
        String rendered = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        return rendered + " (" + jsonType(value) + ")";
    }

    /** The JSON type category of a parsed value, as it is named in the failure messages. */
    private static String jsonType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "String";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return value.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------------------------
    // S2 payload builders - package-private and PURE (no request, no context, no state)
    // so the whole aggregate contract is unit-testable without a live environment
    // ---------------------------------------------------------------------------------

    /**
     * Builds the COMPLETE aggregate capstone configuration body. A {@link LinkedHashMap} because the wire
     * ORDER matters for parity with the captured request: the twenty keys appear below in the verified order.
     *
     * <p>Pure by design: no request, no context read, no state - the same reason
     * {@link #buildTermCourseUpdatePayload} is, and package-private for the same reason (the contract test
     * in this package invokes it directly).
     *
     * <p>Contract details that look like mistakes and are not:
     * <ul>
     *   <li>{@code mounthTriggerDate} / {@code mounthSkipDate} - the API's own misspellings, preserved;</li>
     *   <li>{@code timezone} is an EXPLICIT JSON null, which survives onto the wire because
     *       {@code CapstoneClient} serialises with {@code serializeNulls()};</li>
     *   <li>{@code triggerDay}, {@code allowSkipUntil} and {@code minBalanceThreshold} are empty STRINGS,
     *       not nulls and not absent.</li>
     * </ul>
     *
     * @param resourceDateIso the resource timestamp, injected so this builder stays pure - the caller
     *                        generates it at runtime, and it is never a captured literal
     */
    static Map<String, Object> buildCapstoneConfigurationPayload(String capstoneTermCourseId,
                                                                 String capstoneSectionId,
                                                                 String scheduleStartDate,
                                                                 String scheduleEndDate,
                                                                 String resourceDateIso) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("termCourseId", capstoneTermCourseId);
        payload.put("sectionId", capstoneSectionId);
        payload.put("metrics", buildCapstoneMetrics());
        payload.put("scheduleStartDate", scheduleStartDate);
        payload.put("scheduleEndDate", scheduleEndDate);
        payload.put("triggerInterval", TRIGGER_INTERVAL_DAILY);
        payload.put("triggerDay", "");
        payload.put("allowSkipUntil", "");
        payload.put("allowSkip", false);
        // The two misspellings below are the API's own field names - preserved deliberately.
        payload.put("mounthTriggerDate", "");
        payload.put("mounthSkipDate", "");
        payload.put("dashboardDisplay", buildDashboardDisplay());
        payload.put("analyticsDisplay", buildAnalyticsDisplay());
        payload.put("xByXRows", buildXByXRows());
        payload.put("xByXColumns", buildXByXColumns());
        payload.put("leaderboardType", LEADERBOARD_TYPE_FORMULA);
        // EXPLICIT null, not an omission: CapstoneClient serialises with serializeNulls().
        payload.put("timezone", null);
        payload.put("leaderboard", buildLeaderboard());
        payload.put("resources", buildResources(resourceDateIso));
        payload.put("minBalanceThreshold", "");
        return payload;
    }

    /**
     * One metric object - EXACTLY five keys in the verified order. NOTE the name key is {@code metric}, not
     * {@code name}: a metric object names itself under {@code metric}, which is also why the metrics-builder
     * rows (which use {@code inputType} instead of {@code type}) are built separately.
     */
    static Map<String, Object> metric(String name, String target, String type) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("metric", name);
        metric.put("target", target);
        metric.put("type", type);
        metric.put("showDashboard", false);
        metric.put("currency", "rupee");
        return metric;
    }

    /**
     * The six configured metrics, in the verified order. Three of them carry an EMPTY target on purpose -
     * the captured configuration sets targets on Revenue, Number of Sales and Profit only, and a metric
     * without a target is a valid, tracked-but-untargeted metric.
     */
    static List<Map<String, Object>> buildCapstoneMetrics() {
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric(METRIC_REVENUE, TARGET_REVENUE, TYPE_AMOUNT));
        metrics.add(metric(METRIC_COST, "", TYPE_AMOUNT));
        metrics.add(metric(METRIC_NUMBER_OF_SALES, TARGET_NUMBER_OF_SALES, TYPE_COUNT));
        metrics.add(metric(METRIC_PROFIT, TARGET_PROFIT, TYPE_PERCENTAGE));
        metrics.add(metric(METRIC_CUSTOMERS, "", TYPE_COUNT));
        metrics.add(metric(METRIC_FUNDING_RAISED, "", TYPE_AMOUNT));
        return metrics;
    }

    /**
     * The dashboard display - a SUBSET of the metrics (Revenue and Profit), each wrapped as
     * {@code {value:<full metric>, label:<metric name>}}.
     */
    static List<Map<String, Object>> buildDashboardDisplay() {
        List<Map<String, Object>> dashboard = new ArrayList<>();
        dashboard.add(dashboardEntry(metric(METRIC_REVENUE, TARGET_REVENUE, TYPE_AMOUNT), METRIC_REVENUE));
        dashboard.add(dashboardEntry(metric(METRIC_PROFIT, TARGET_PROFIT, TYPE_PERCENTAGE), METRIC_PROFIT));
        return dashboard;
    }

    private static Map<String, Object> dashboardEntry(Map<String, Object> value, String label) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("value", value);
        entry.put("label", label);
        return entry;
    }

    /**
     * The analytics display - three charted metrics. NOTE the shape: {@code type} holds the metric NAME (not
     * an amount/count/percentage input type) and the metric object itself sits under {@code data}.
     */
    static List<Map<String, Object>> buildAnalyticsDisplay() {
        List<Map<String, Object>> analytics = new ArrayList<>();
        analytics.add(analyticsEntry(METRIC_REVENUE, metric(METRIC_REVENUE, TARGET_REVENUE, TYPE_AMOUNT)));
        analytics.add(analyticsEntry(METRIC_PROFIT, metric(METRIC_PROFIT, TARGET_PROFIT, TYPE_PERCENTAGE)));
        analytics.add(analyticsEntry(METRIC_NUMBER_OF_SALES,
                metric(METRIC_NUMBER_OF_SALES, TARGET_NUMBER_OF_SALES, TYPE_COUNT)));
        return analytics;
    }

    private static Map<String, Object> analyticsEntry(String metricName, Map<String, Object> data) {
        Map<String, Object> entry = new LinkedHashMap<>();
        // 'type' here is the metric NAME, not an input type - the captured contract's own naming.
        entry.put("type", metricName);
        entry.put("isDetailedVisualGraph", true);
        entry.put("data", data);
        return entry;
    }

    /**
     * The metrics-builder rows - EXACTLY three keys each, and the input type key is {@code inputType}, NOT
     * {@code type}. These are deliberately not full metric objects: the builder grid only needs which metric
     * is entered, how it is entered and whether proof is required.
     */
    static List<Map<String, Object>> buildXByXRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(xByXRow(METRIC_REVENUE, TYPE_AMOUNT));
        rows.add(xByXRow(METRIC_NUMBER_OF_SALES, TYPE_COUNT));
        rows.add(xByXRow(METRIC_PROFIT, TYPE_PERCENTAGE));
        return rows;
    }

    private static Map<String, Object> xByXRow(String metricName, String inputType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", metricName);
        row.put("inputType", inputType);
        row.put("proofRequired", false);
        return row;
    }

    /** The metrics-builder columns - PLAIN STRINGS, not objects. */
    static List<String> buildXByXColumns() {
        return List.of(XBYX_COLUMN_ONLINE);
    }

    /**
     * The leaderboard - {@code type} plus a TWO-LEVEL {@code formula} whose {@code metrics[]} entries each
     * carry {@code {metric:{value, label}, weightage}}.
     *
     * <p>Two asymmetries here are real, verified parts of the contract rather than oversights:
     * <ul>
     *   <li>the {@code Point} entry's value is a REDUCED two-key object ({@code metric} + {@code type} only,
     *       with no target/showDashboard/currency) while the other three carry full five-key metrics;</li>
     *   <li>the weightages mix TYPES - three numbers and one string (see
     *       {@link #leaderboardFormulaMetrics()}).</li>
     * </ul>
     * {@code Point} is also a leaderboard-only pseudo-metric: it is weighted here but is NOT one of the six
     * entries in {@code metrics[]}.
     */
    static Map<String, Object> buildLeaderboard() {
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("setName", LEADERBOARD_FORMULA_SET_NAME);
        formula.put("metrics", leaderboardFormulaMetrics());

        Map<String, Object> leaderboard = new LinkedHashMap<>();
        leaderboard.put("type", LEADERBOARD_TYPE_FORMULA);
        leaderboard.put("formula", formula);
        return leaderboard;
    }

    /**
     * The four weighted leaderboard entries. The {@code Profit} weightage is deliberately the STRING
     * {@code "40"} while the other three are NUMBERS: the captured contract mixes the two types in this one
     * array, and it is reproduced exactly rather than normalised, because normalising would mean sending a
     * body that was never proven against the API. Every read-back comparison of a weightage therefore goes
     * through {@code String.valueOf}.
     */
    static List<Map<String, Object>> leaderboardFormulaMetrics() {
        List<Map<String, Object>> metrics = new ArrayList<>();

        // REDUCED two-key value object for the leaderboard-only pseudo-metric 'Point'.
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("metric", METRIC_POINT);
        point.put("type", TYPE_COUNT);

        metrics.add(leaderboardEntry(point, METRIC_POINT, 10));
        metrics.add(leaderboardEntry(metric(METRIC_REVENUE, TARGET_REVENUE, TYPE_AMOUNT), METRIC_REVENUE, 20));
        metrics.add(leaderboardEntry(metric(METRIC_NUMBER_OF_SALES, TARGET_NUMBER_OF_SALES, TYPE_COUNT),
                METRIC_NUMBER_OF_SALES, 30));
        // STRING weightage, on purpose - see this method's Javadoc.
        metrics.add(leaderboardEntry(metric(METRIC_PROFIT, TARGET_PROFIT, TYPE_PERCENTAGE), METRIC_PROFIT, "40"));
        return metrics;
    }

    private static Map<String, Object> leaderboardEntry(Map<String, Object> value, String label, Object weightage) {
        Map<String, Object> metricRef = new LinkedHashMap<>();
        metricRef.put("value", value);
        metricRef.put("label", label);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("metric", metricRef);
        entry.put("weightage", weightage);
        return entry;
    }

    /**
     * The one resource folder with its one link. Folder name, resource name and link come from
     * {@code TestDataFactory} (deterministic, so the read-back can assert them by value); {@code file} is an
     * EXPLICIT null, which reaches the wire because {@code CapstoneClient} serialises with
     * {@code serializeNulls()}.
     *
     * @param resourceDateIso a full ISO-8601 UTC timestamp with millis and a trailing {@code Z}
     *                        ({@code 2026-09-21T10:55:17.084Z}) - NOT a plain calendar date. Injected so
     *                        this builder stays pure, and generated at runtime by the caller rather than
     *                        being a captured literal.
     */
    static List<Map<String, Object>> buildResources(String resourceDateIso) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("name", TestDataFactory.capstoneResourceName());
        resource.put("link", TestDataFactory.capstoneResourceLink());
        resource.put("date", resourceDateIso);
        resource.put("file", null);

        Map<String, Object> folder = new LinkedHashMap<>();
        folder.put("folderName", TestDataFactory.capstoneResourceFolderName());
        folder.put("folderData", Collections.singletonList(resource));

        return Collections.singletonList(folder);
    }

    /**
     * Builds the reward body. {@code gamificationConfigId} is the NUMERIC configuration id as a NUMBER, and
     * {@code coinRequiredToClaimReward} is a NUMBER too; {@code totalClaimLimit}, {@code perTeamClaimLimit}
     * and {@code fileUpload} are EXPLICIT nulls that survive onto the wire through
     * {@code CapstoneClient}'s {@code serializeNulls()}.
     *
     * <p>Pure apart from the {@code questionUuid} values, which are freshly minted per call - see
     * {@link #rewardQuestion}.
     */
    static Map<String, Object> buildCapstoneRewardPayload(int gamificationConfigId) {
        List<Map<String, Object>> questions = new ArrayList<>();
        questions.add(rewardQuestion("Describe how your team earned this reward", "text",
                Collections.emptyList(), true, true));
        questions.add(rewardQuestion("Which capstone milestones did your team complete?", "multi_select",
                List.of("Idea validation", "Prototype delivered", "First revenue"), true, true));
        questions.add(rewardQuestion("Which channel contributed the most?", "single_select",
                List.of("Online", "Offline"), true, true));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gamificationConfigId", gamificationConfigId);
        payload.put("rewardName", REWARD_NAME);
        payload.put("coinRequiredToClaimReward", REWARD_COIN_COST);
        payload.put("description", REWARD_DESCRIPTION);
        payload.put("totalClaimLimit", null);
        payload.put("perTeamClaimLimit", null);
        payload.put("fileUpload", null);
        payload.put("questions", questions);
        return payload;
    }

    /**
     * One reward claim question - EXACTLY six keys in the verified order.
     *
     * <p>{@code questionUuid} is minted here with {@code UUID.randomUUID()} at RUNTIME, never taken from a
     * captured literal. EVIDENCE that it is client-minted rather than server reference data: the capture
     * shows {@code questionUuid} present in the reward-create REQUEST while the rewards GET fired
     * immediately BEFORE that create returned an EMPTY {@code Data} array - so there was no server-side
     * question the client could have read it from, and no lookup endpoint supplies one.
     */
    static Map<String, Object> rewardQuestion(String title, String type, List<String> options,
                                              boolean isRequired, boolean isMandatory) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("questionUuid", UUID.randomUUID().toString());
        question.put("title", title);
        question.put("type", type);
        question.put("options", options);
        question.put("isRequired", isRequired);
        question.put("isMandatory", isMandatory);
        return question;
    }

    // ---------------------------------------------------------------------------------
    // S3 payload builder - package-private and PURE (no request, no context, no state)
    // so the team-creation wire contract is unit-testable without a live environment
    // ---------------------------------------------------------------------------------

    /**
     * Builds the capstone TEAM-creation body for {@code POST /api/v1/program/addStudentsInGroup}. A nested
     * {@link LinkedHashMap} structure because the wire ORDER matters for parity with the captured request:
     * EXACTLY five top-level keys in the verified order {@code sectionId}, {@code termCourseId},
     * {@code createdBy}, {@code sectionGroup}, {@code allowTeamFormation}, with {@code sectionGroup[0]}
     * carrying EXACTLY six keys in the order {@code name}, {@code description}, {@code image}, {@code size},
     * {@code userGroup}, {@code groupRepresentative}.
     *
     * <p>Wire types that look like mistakes and are HAR-PROVEN rather than incidental:
     * <ul>
     *   <li>{@code size} is the JSON STRING {@code "1"}, NOT the number 1 - the captured request sends a
     *       string, and "tidying" it to a number would mean sending a body never proven against the API;</li>
     *   <li>{@code image} is the EMPTY STRING {@code ""} - not null and not absent; an image-less team still
     *       carries the key, which is why it is asserted by value rather than by presence;</li>
     *   <li>{@code allowTeamFormation} is a JSON boolean {@code false} and sits at TOP LEVEL, a sibling of
     *       {@code sectionGroup} rather than one of {@code sectionGroup[0]}'s keys;</li>
     *   <li>{@code userGroup} is a FLAT array of member uuid Strings here - the API names the same concept
     *       {@code userGroup} as an OBJECT on the student-side read, and the two shapes are not
     *       interchangeable;</li>
     *   <li>{@code groupRepresentative} is an object of exactly {@code value} then {@code label}, where
     *       {@code value} is the member uuid and {@code label} is a cosmetic display string - the membership
     *       verdict is always derived from {@code value}, never from {@code label}.</li>
     * </ul>
     *
     * <p>Pure by design: no request, no context read, no assertion and no {@code TestDataFactory} call - the
     * caller injects the generated team name, description and representative label, the same reason
     * {@link #buildCapstoneConfigurationPayload} takes its dates as parameters. Package-private for the same
     * reason too: the contract test in this package invokes it directly.
     *
     * @param capstoneSectionId    the CAPSTONE section uuid - never the runtime {@code sectionId}
     * @param capstoneTermCourseId the CAPSTONE termCourse uuid - never the runtime {@code termCourseId}
     * @param adminUserId          the admin user id the team is created by
     * @param teamName             the generated team name, deterministic within one suite run so the
     *                             read-back can assert it by value
     * @param teamDescription      the generated team description, deterministic for the same reason
     * @param studentUuid          the runtime student uuid - it appears TWICE, as the single
     *                             {@code userGroup} element and as {@code groupRepresentative.value}
     * @param representativeLabel  the representative's cosmetic display label
     */
    static Map<String, Object> buildCapstoneTeamPayload(String capstoneSectionId,
                                                        String capstoneTermCourseId,
                                                        String adminUserId,
                                                        String teamName,
                                                        String teamDescription,
                                                        String studentUuid,
                                                        String representativeLabel) {
        Map<String, Object> groupRepresentative = new LinkedHashMap<>();
        groupRepresentative.put("value", studentUuid);
        groupRepresentative.put("label", representativeLabel);

        Map<String, Object> sectionGroup = new LinkedHashMap<>();
        sectionGroup.put("name", teamName);
        sectionGroup.put("description", teamDescription);
        // Empty STRING, not null and not absent.
        sectionGroup.put("image", "");
        // STRING "1", not the number 1.
        sectionGroup.put("size", "1");
        // FLAT array of member uuid Strings - the object-shaped userGroup belongs to the student-side read.
        sectionGroup.put("userGroup", Collections.singletonList(studentUuid));
        sectionGroup.put("groupRepresentative", groupRepresentative);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sectionId", capstoneSectionId);
        payload.put("termCourseId", capstoneTermCourseId);
        payload.put("createdBy", adminUserId);
        payload.put("sectionGroup", Collections.singletonList(sectionGroup));
        // TOP-LEVEL boolean, deliberately OUTSIDE sectionGroup[0].
        payload.put("allowTeamFormation", false);
        return payload;
    }

    // ---------------------------------------------------------------------------------
    // S2 payload-navigation helpers - null-tolerant so a missing slice surfaces as a
    // named assertion failure rather than a ClassCastException from deep in the payload
    // ---------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            if (item instanceof Map) {
                entries.add((Map<String, Object>) item);
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            values.add(text(item));
        }
        return values;
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    /** The {@code metric} values of a metric-shaped list (metrics[] or xByXRows[]), in order. */
    private static List<String> metricNames(List<Map<String, Object>> entries) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            names.add(text(entry.get("metric")));
        }
        return names;
    }

    /** The named metric's {@code target}, or null when the list holds no metric by that name. */
    private static String metricTarget(List<Map<String, Object>> metrics, String metricName) {
        for (Map<String, Object> entry : metrics) {
            if (metricName.equals(text(entry.get("metric")))) {
                return text(entry.get("target"));
            }
        }
        return null;
    }

    /** The named key's values across a display list (dashboard {@code label} / analytics {@code type}). */
    private static List<String> labels(List<Map<String, Object>> entries, String key) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            values.add(text(entry.get(key)));
        }
        return values;
    }

    /** The {@code metric.label} of each weighted leaderboard entry, in order. */
    private static List<String> leaderboardLabels(List<Map<String, Object>> formulaMetrics) {
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> entry : formulaMetrics) {
            Map<String, Object> metricRef = asMap(entry.get("metric"));
            labels.add(metricRef == null ? null : text(metricRef.get("label")));
        }
        return labels;
    }

    /**
     * The {@code weightage} of each weighted leaderboard entry AS A STRING - the sent contract mixes numeric
     * and string weightages, so string comparison is the only comparison that holds for both.
     */
    private static List<String> leaderboardWeightages(List<Map<String, Object>> formulaMetrics) {
        List<String> weightages = new ArrayList<>();
        for (Map<String, Object> entry : formulaMetrics) {
            weightages.add(text(entry.get("weightage")));
        }
        return weightages;
    }
}
