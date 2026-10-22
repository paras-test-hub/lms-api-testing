package com.lms.steps.workflows;

import com.lms.cleanup.ResourceType;
import com.lms.clients.admin.CohortClient;
import com.lms.clients.admin.ProgramMasterClient;
import com.lms.clients.admin.ReferenceDataClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.EnvironmentSetupReport;
import com.lms.utils.JsonSearch;
import com.lms.utils.TestDataFactory;
import com.lms.workflows.BootstrapWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for Environment Setup (features/cross-functional/EnvironmentSetup.feature) - the
 * one-time provisioning of a complete, isolated academic environment that every Business
 * Workflow runs against afterward. This is NOT a business workflow and NOT a test in itself;
 * it is the "build a fresh academic institution" step that precedes Assignment, Attendance,
 * Quiz, Announcement, Grading, Event, Certificate, Placement, and every other Business
 * Workflow. Those workflows must consume the ids this produces via ScenarioContext and must
 * never create their own Program/Cohort/Program Term/Section/Course/Student.
 *
 * This is grown incrementally, one verified link at a time, against the live dev backend -
 * each new step here has been proven against a real response before being added, following
 * the same discipline as ProgramMasterSteps. Business logic will be consolidated into a
 * BootstrapWorkflow orchestrator once the full chain is verified end-to-end (keeping step
 * definitions thin is the target end state, not necessarily true at every intermediate commit
 * while the chain is still being built out link by link).
 *
 * Reuses the canonical "the admin logs in with valid credentials" step from
 * ProgramMasterSteps (admin session, cached) rather than redefining it.
 *
 * TODO: rename this class to EnvironmentSetupSteps (and BootstrapWorkflow to
 * EnvironmentSetupWorkflow) in a dedicated refactoring pass - see BootstrapWorkflow's class
 * Javadoc for the same note. Kept as-is for now to avoid a large, ripple-heavy rename across
 * every call site in the same change as the enrollment work.
 */
public class BootstrapSteps {

    private final ScenarioContext context;
    private final ProgramMasterClient programMasterClient;
    private final ReferenceDataClient referenceDataClient;
    private final CohortClient cohortClient;
    private final BootstrapWorkflow bootstrapWorkflow;

    public BootstrapSteps(ScenarioContext context) {
        this.context = context;
        this.programMasterClient = new ProgramMasterClient();
        this.referenceDataClient = new ReferenceDataClient();
        this.cohortClient = new CohortClient();
        this.bootstrapWorkflow = new BootstrapWorkflow();
    }

    @Given("a valid category id is available for bootstrap")
    public void aValidCategoryIdIsAvailableForBootstrap() {
        Response categoriesResponse = referenceDataClient.listCategories(context.getAdminOrgId(), context.getAdminToken());
        Object body = categoriesResponse.jsonPath().getMap("$");
        Object categoryId = JsonSearch.findFirstValue(body, "uuid");
        Assumptions.assumeTrue(categoryId != null,
                "no category id found via GET .../category - seed at least one category for this org first");
        context.putAttribute("categoryId", categoryId.toString());
    }

    /**
     * Sources gradingTemplate from GET /api/v1/organization/{orgId}/getAllGradingTemplates -
     * the org-level MASTER template list the cohort-creation form's dropdown actually uses
     * (verified in lms-admin-frontend CreateCohort.js -> allTemplateData thunk). Extract the
     * item's "uuid".
     *
     * This replaced an earlier attempt using GET .../program/termCourse/gradingTemplate, whose
     * uuids are per-instance APPLIED-template records and get rejected (404) when posted back
     * as a cohort's gradingTemplate.
     */
    @Given("a valid grading template id is available for bootstrap")
    public void aValidGradingTemplateIdIsAvailableForBootstrap() {
        Response response = referenceDataClient.listGradingTemplates(context.getAdminOrgId(), context.getAdminToken());
        Object body = response.jsonPath().getMap("$");
        Object templateId = JsonSearch.findFirstValue(body, "uuid");
        Assumptions.assumeTrue(templateId != null,
                "no grading template found via GET .../getAllGradingTemplates - seed at least one for this org first");
        context.putAttribute("gradingTemplateId", templateId.toString());
    }

    /**
     * Verified live: GET .../getAllCourseTypes returns
     * {@code { IsSuccess, Data: [ {uuid, name, ...}, ... ] } } - same shape/convention as the
     * program master list.
     */
    @Given("a valid course type id is available for bootstrap")
    public void aValidCourseTypeIdIsAvailableForBootstrap() {
        Response response = referenceDataClient.listCourseTypes(context.getAdminOrgId(), context.getAdminToken());
        Object body = response.jsonPath().getMap("$");
        Object courseTypeId = JsonSearch.findFirstValue(body, "uuid");
        Assumptions.assumeTrue(courseTypeId != null,
                "no course type found via GET .../getAllCourseTypes - seed at least one for this org first");
        context.putAttribute("courseTypeId", courseTypeId.toString());
    }

    @Given("a program is created for the isolated environment")
    public void aProgramIsCreatedForTheIsolatedEnvironment() {
        String name = TestDataFactory.programName();
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("description", "Created by automated environment setup workflow");
        fields.put("programMode", "Online");
        fields.put("programSystem", "Semester");
        fields.put("numberOfTerms", "2");
        fields.put("durationInMonths", "12");
        fields.put("gradingMechanism", "Percentage");
        fields.put("timezone", "Asia/Kolkata");
        fields.put("isDraft", false);
        fields.put("isActive", true);
        if (context.hasAttribute("categoryId")) {
            fields.put("categoryId", context.getAttribute("categoryId"));
        }

        Response createResponse = programMasterClient.create(
                context.getAdminOrgId(), fields, context.getAdminUserId(), context.getAdminToken());
        assertThat(createResponse.statusCode())
                .as("program master creation must succeed to set up an isolated environment")
                .isBetween(200, 299);

        Response listResponse = programMasterClient.list(context.getAdminOrgId(), context.getAdminToken());
        Object listBody = listResponse.jsonPath().getMap("$");
        Object programId = JsonSearch.findIdByField(listBody, "name", name, "uuid");
        assertThat(programId).as("created program must be findable in the list response").isNotNull();

        context.setProgramId(programId.toString());
        context.setProgramName(name);
        context.registerCreated(ResourceType.PROGRAM, programId.toString());

        EnvironmentSetupReport.checkpoint(context, "Program Created",
                "Name: " + name, "UUID: " + programId);
    }

    @Then("the isolated environment has a program")
    public void theIsolatedEnvironmentHasAProgram() {
        assertThat(context.getProgramId()).isNotBlank();
        assertThat(context.getProgramName()).isNotBlank();
    }

    @Given("a cohort is created under the program for the isolated environment")
    public void aCohortIsCreatedUnderTheProgramForTheIsolatedEnvironment() {
        String name = TestDataFactory.cohortName();
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        Map<String, Object> fields = new HashMap<>();
        fields.put("batchName", name);
        fields.put("enrolledStudents", 30);
        fields.put("startDate", LocalDate.now().minusMonths(1).format(iso));
        fields.put("endDate", LocalDate.now().plusMonths(6).format(iso));
        fields.put("numberOfTerms", 1);
        // sectionSize is the NUMBER OF SECTIONS the backend auto-generates per term, NOT the number
        // of students per section - verified from the live admin UI, where sectionSize=30 produced
        // "Section 1".."Section 30" in the cohort's section dropdown (with enrolledStudents=30 a
        // students-per-section reading would instead have produced a single section).
        //
        // Kept at 1: the isolated environment only ever needs ONE section (Environment Setup creates
        // its own section, attaches the course to it, and enrolls the single runtime student there).
        // The previous value of 30 made the backend generate 30 sections PER TERM x numberOfTerms=2
        // = 60 sections on every run, which is what pushed cohort creation past the 90s request
        // timeout (REQUEST_TIMEOUT in .env) and failed Environment Setup - and, through it, every
        // business journey and the cleanup that depend on the runtime environment.
        fields.put("sectionSize", 1);
        fields.put("description", "Created by automated environment setup workflow");
        fields.put("timezone", "Asia/Kolkata");
        if (context.hasAttribute("gradingTemplateId")) {
            fields.put("gradingTemplate", context.getAttribute("gradingTemplateId"));
        }
        // courseTypeArray is a required field (frontend sends [{ typeFormatuuid, minCourseCount }]);
        // omitting it made the create endpoint hang/read-timeout rather than fast-fail.
        if (context.hasAttribute("courseTypeId")) {
            Map<String, Object> courseType = new HashMap<>();
            courseType.put("typeFormatuuid", context.getAttribute("courseTypeId"));
            courseType.put("minCourseCount", 1);
            fields.put("courseTypeArray", java.util.Collections.singletonList(courseType));
        }

        Response createResponse = cohortClient.createCohort(
                context.getProgramId(), fields, context.getAdminUserId(), context.getAdminToken());
        assertThat(createResponse.statusCode())
                .as("cohort creation must succeed to set up an isolated environment. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        Object body = createResponse.jsonPath().getMap("$");
        Object cohortId = JsonSearch.findFirstValue(body, "uuid");
        assertThat(cohortId).as("created cohort must have a discoverable uuid in the create response").isNotNull();

        context.setCohortId(cohortId.toString());
        context.setCohortName(name);
        context.registerCreated(ResourceType.COHORT, cohortId.toString());

        EnvironmentSetupReport.checkpoint(context, "Cohort Created",
                "Name: " + name, "UUID: " + cohortId);
    }

    @Then("the isolated environment has a cohort")
    public void theIsolatedEnvironmentHasACohort() {
        assertThat(context.getCohortId()).isNotBlank();
        assertThat(context.getCohortName()).isNotBlank();
    }

    // ---- Student creation (thin - delegates to BootstrapWorkflow) ----

    @Given("a student is created in the program and cohort for the isolated environment")
    public void aStudentIsCreatedInTheProgramAndCohortForTheIsolatedEnvironment() {
        bootstrapWorkflow.createAndVerifyStudent(context);
    }

    @Then("the isolated environment has a student")
    public void theIsolatedEnvironmentHasAStudent() {
        assertThat(context.getStudentUuid()).isNotBlank();
        assertThat(context.execution().getStudentEmail()).isNotBlank();
        assertThat(context.execution().getStudentPassword()).isNotBlank();
        assertThat(context.getStudentProgramId()).isEqualTo(context.getProgramId());
        assertThat(context.getStudentCohortId()).isEqualTo(context.getCohortId());
    }

    // ---- Program Term / Section (thin - delegates to BootstrapWorkflow) ----

    @Given("a program term is created under the cohort for the isolated environment")
    public void aProgramTermIsCreatedUnderTheCohortForTheIsolatedEnvironment() {
        bootstrapWorkflow.createAndVerifyProgramTerm(context);
    }

    @Then("the isolated environment has a program term")
    public void theIsolatedEnvironmentHasAProgramTerm() {
        assertThat(context.getProgramTermId()).isNotBlank();
        assertThat(context.getProgramTermName()).isEqualTo("Term 1");
        assertThat(context.getProgramTermStartDate()).isNotBlank();
        assertThat(context.getProgramTermEndDate()).isNotBlank();

        // The runtime term window must contain today (the invariant Environment Setup now guarantees).
        LocalDate today = LocalDate.now();
        LocalDate start = LocalDate.parse(context.getProgramTermStartDate());
        LocalDate end = LocalDate.parse(context.getProgramTermEndDate());
        assertThat(!today.isBefore(start) && !today.isAfter(end))
                .as("today (%s) must fall inside the runtime Term 1 window [%s, %s]", today, start, end)
                .isTrue();
    }

    @Given("a section is created under the program term for the isolated environment")
    public void aSectionIsCreatedUnderTheProgramTermForTheIsolatedEnvironment() {
        bootstrapWorkflow.createAndVerifySection(context);
    }

    @Then("the isolated environment has a section")
    public void theIsolatedEnvironmentHasASection() {
        assertThat(context.getSectionId()).isNotBlank();
    }

    // ---- Course creation + attach (thin - delegates to BootstrapWorkflow) ----

    @Given("a course is created and configured for the isolated environment")
    public void aCourseIsCreatedAndConfiguredForTheIsolatedEnvironment() {
        bootstrapWorkflow.createAndVerifyCourse(context);
    }

    @Then("the isolated environment has a course")
    public void theIsolatedEnvironmentHasACourse() {
        assertThat(context.getCourseId()).isNotBlank();
        assertThat(context.getCourseName()).isNotBlank();
    }

    @Given("the course is attached to the section for the isolated environment")
    public void theCourseIsAttachedToTheSectionForTheIsolatedEnvironment() {
        bootstrapWorkflow.attachCourseToSection(context);
    }

    @Then("the isolated environment has the course attached to the section")
    public void theIsolatedEnvironmentHasTheCourseAttachedToTheSection() {
        assertThat(context.getCourseId()).isNotBlank();
        assertThat(context.getSectionId()).isNotBlank();
    }

    // ---- Student enrollment (thin - delegates to BootstrapWorkflow) ----

    @Given("the student is assigned to the section for the isolated environment")
    public void theStudentIsAssignedToTheSectionForTheIsolatedEnvironment() {
        bootstrapWorkflow.enrollStudentInSection(context);
    }

    @Then("the isolated environment has the student assigned to the section")
    public void theIsolatedEnvironmentHasTheStudentAssignedToTheSection() {
        assertThat(context.getStudentUuid()).isNotBlank();
        assertThat(context.getSectionId()).isNotBlank();
    }
}
