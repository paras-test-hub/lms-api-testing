package com.lms.clients.admin;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin "program" tag - Cohort/Batch provisioning subset, covering the chain from an
 * existing Program Master down to a course-attached section: createCohort -> createProgramTerm
 * -> createSection -> attachCourseToSection -> enrollStudents. Part of the Environment Setup
 * workflow (see BootstrapSteps - TODO: rename to EnvironmentSetupSteps), not a standalone
 * per-tag CRUD client.
 *
 * Paths and REQUEST shapes verified directly from lms-admin-frontend/src/redux/program/programSlice.js
 * (frontend thunks createBatch, createProgramTerm, customSection, sectionCourseAdd,
 * enrollStudents) - NOT the OpenAPI spec, whose request bodies are unreliable (see
 * ProgramMasterClient's Javadoc for the same finding on a sibling endpoint).
 */
public class CohortClient {

    private final RestClient restClient;

    public CohortClient() {
        this.restClient = new RestClient();
    }

    private static String programBase() {
        return TestConfig.API_V1 + "/program";
    }

    /** POST /api/v1/program/programMaster/{programMasterId} - createBatch thunk. */
    public Response createCohort(String programMasterId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(programBase() + "/programMaster/" + programMasterId, payload, bearerToken);
    }

    /** POST /api/v1/program/{batchId}/programTerm - createProgramTerm thunk (batchId = the cohort id).
     * NOTE: the create response is bare ({@code {IsSuccess, message}}, no uuid) - verified live.
     * Look up the created term's id via {@link #listProgramTerms} afterward. */
    public Response createProgramTerm(String batchId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(programBase() + "/" + batchId + "/programTerm", payload, bearerToken);
    }

    /** GET /api/v1/program/{batchId}/programTerm?searchParam=&pageSize=10&pageNo=1 - "Get Program Terms
     * For Batch" (getProgramTerm thunk). Verified live: omitting the pagination query params returns
     * Count:0 even with terms present - they are required, not optional. Used to find a just-created
     * term's uuid by its "term" name. */
    public Response listProgramTerms(String batchId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", 10);
        queryParams.put("pageNo", 1);
        return restClient.get(programBase() + "/" + batchId + "/programTerm", queryParams, bearerToken);
    }

    /** POST /api/v1/program/programTerm/{programTermId}/section - customSection thunk. */
    public Response createSection(String programTermId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(programBase() + "/programTerm/" + programTermId + "/section", payload, bearerToken);
    }

    /**
     * POST /api/v1/program/{cohortId}/termSection - creates a section directly under a cohort's term.
     * Distinct from {@link #createSection} (POST .../programTerm/{ptId}/section) - a different path
     * shape, kept separate: this is an addition, never a rename or a widening of that operation.
     *
     * <p>Path and shapes verified from a HAR capture of the admin capstone flow: body
     * {@code { programTermId, isCapstoneSection, createdBy }} (the caller supplies the first two in
     * {@code fields}; this method only stamps {@code createdBy}); response 201
     * {@code { Data:{ uuid, name, isCapstoneSection, programTermId, programId, createdBy } }}, where
     * {@code name} is backend-generated. NOTE: {@code Data.programTermId}, {@code Data.programId} and
     * {@code Data.createdBy} are NUMERIC internal ids, not uuids - unusable for uuid comparisons.
     */
    public Response createTermSection(String cohortId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(programBase() + "/" + cohortId + "/termSection", payload, bearerToken);
    }

    /** POST /api/v1/program/programTerm/{programTermId}/sectionCourse - sectionCourseAdd thunk. */
    public Response attachCourseToSection(String programTermId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(programBase() + "/programTerm/" + programTermId + "/sectionCourse", payload, bearerToken);
    }

    /**
     * GET /api/v1/program/programTerm/{programTermId}/section/{sectionId} - section detail. The
     * admin frontend calls this immediately after {@link #attachCourseToSection} to refresh the
     * section's course list (verified via HAR hawkeye-apis_2026-07-15_07-50-21.har). Response:
     * {@code { Data: { data: [ { uuid (sectionCourse mapping id), credits, status,
     * CourseType:{name}, CourseMaster:{name,courseCode,uuid} }, ... ] } } } - used to confirm the
     * just-attached course appears by matching CourseMaster.uuid.
     */
    public Response getSectionDetail(String programTermId, String sectionId, String bearerToken) {
        return restClient.get(programBase() + "/programTerm/" + programTermId + "/section/" + sectionId, bearerToken);
    }

    /**
     * GET /api/v1/program/section/{sectionId}/sectionCourses?searchParam=&pageSize=10&pageNo=1&toggle=
     * - the courses mapped to a section, keyed by section id alone (no programTerm segment, unlike
     * {@link #getSectionDetail}). The pagination query params are required, the same finding as on
     * {@link #listProgramTerms}; they are sent as strings, mirroring {@link #getSectionStudents}'s
     * convention.
     *
     * <p>Response shape verified from a HAR capture of the admin capstone flow:
     * {@code { IsSuccess, Data:[ { uuid, CourseMaster:{uuid,name,courseCode}, CourseType:{uuid},
     * CourseTag:{uuid}, CourseClass:{uuid}, TermCourseDomains:[], Section:{uuid,name}, isGamify, ... } ],
     * Count, message }}, where {@code Data[].uuid} is the TermCourse id.
     *
     * <p>NOTE: this is a PROJECTION, not the full termCourse - it carries NO {@code CourseMode} and NO
     * {@code NpsDepartment}. It therefore cannot be used to build the termCourse update payload (which
     * needs {@code courseModeId} and {@code departmentId}); {@link #getTermCourseDetail} is required for
     * that.
     */
    public Response getSectionCourses(String sectionId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", "10");
        queryParams.put("pageNo", "1");
        queryParams.put("toggle", "");
        return restClient.get(programBase() + "/section/" + sectionId + "/sectionCourses", queryParams, bearerToken);
    }

    /** POST /api/v1/program/programTerm/{programTermId}/section/{sectionId}/enrollStudents - enrollStudents thunk. */
    public Response enrollStudents(String programTermId, String sectionId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(
                programBase() + "/programTerm/" + programTermId + "/section/" + sectionId + "/enrollStudents",
                payload, bearerToken);
    }

    /**
     * GET /api/v1/program/programTerm/{programTermId}/section/{sectionId}/getSectionStudents
     * ?searchParam=&pageSize=&pageNo= - the roster of students already enrolled in a section.
     * Verified via HAR hawkeye-apis_2026-07-16_10-03-18.har. Response: {@code { Data: [
     * { uuid, firstName, lastName, officialEmail, UserSections:[ { uuid (enrollment mapping id),
     * programTermId, Section:{uuid,name,programTermId} } ] }, ... ] } }.
     */
    public Response getSectionStudents(String programTermId, String sectionId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", "");
        queryParams.put("pageNo", "");
        return restClient.get(
                programBase() + "/programTerm/" + programTermId + "/section/" + sectionId + "/getSectionStudents",
                queryParams, bearerToken);
    }

    /**
     * GET /api/v1/program/{batchId}/term/{programTermId}/getUnassignedUser?sectionId={sectionId}
     * - despite the name, this does NOT return a pre-filtered "unassigned only" list: it returns
     * EVERY member of the Program (Data.Program.UserPrograms[].User), each annotated with their
     * own UserSections (empty if not yet assigned to ANY section). Verified live: entries with
     * non-empty UserSections for OTHER sections are present alongside genuinely unassigned ones.
     * The caller must find the target student by uuid and check their UserSections itself.
     *
     * NOTE: the first path segment is the COHORT/batch id (same "batchId" convention as
     * createProgramTerm/listProgramTerms), NOT the Program Master id - the Program Master id is
     * rejected with 400 "Program not found" (verified live). In the HAR this segment's uuid
     * carries a batchName and matches the id getCohortDetails is keyed by.
     */
    public Response getUnassignedUsers(String batchId, String programTermId, String sectionId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("sectionId", sectionId);
        return restClient.get(
                programBase() + "/" + batchId + "/term/" + programTermId + "/getUnassignedUser",
                queryParams, bearerToken);
    }

    /** DELETE /api/v1/program/{cohortId} - "Delete Batch/Cohort" endpoint (logical delete). */
    public Response deleteCohort(String cohortId, String bearerToken) {
        return restClient.delete(programBase() + "/" + cohortId, bearerToken);
    }

    /**
     * PATCH /api/v1/program/programTerm/{programTermId} - configures an existing Program Term
     * (the backend auto-creates "Term 1" on cohort creation with null startDate/endDate; this is
     * how the admin UI sets its date window and course-count limits afterward).
     *
     * <p>Request/response shapes verified from a real HAR capture of the admin flow against this
     * framework's OWN runtime cohort (references/hawkeye-apis_2026-09-14_12-24-52.har, PATCH entry):
     * body {@code { startDate, endDate, skipFeedback, minCourseCount, maxCourseCount, country,
     * documentRequirements, courseFormatArray:[{courseFormatId, minTermCount}], programId, updatedBy }};
     * response {@code { IsSuccess:true, Data:{ uuid, term, startDate (ISO), endDate (ISO), ... },
     * message:"Program term updated" }}.
     *
     * <p>NOTE: {@code programId} in this payload is the COHORT/batch uuid (same convention as
     * {@link #getUnassignedUsers}), NOT the Program Master id. {@code updatedBy} is the admin user id.
     * The caller supplies startDate/endDate/courseFormatArray/etc. in {@code fields}; this method
     * only stamps {@code updatedBy}. Fields are sent verbatim (HAR-proven), none are invented.
     */
    public Response updateProgramTerm(String programTermId, Map<String, Object> fields, String updatedBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("updatedBy", updatedBy);
        return restClient.patch(programBase() + "/programTerm/" + programTermId, payload, bearerToken);
    }

    /**
     * GET /api/v1/program/{cohortId}/getCohortDetails - the cohort's full term + section detail.
     * Verified from the same HAR capture: response {@code { IsSuccess:true, Data:{ program:{...},
     * ProgramTerm:[ { uuid, term, startDate, endDate, minCredit, maxCredit, isActiveTerm,
     * minCourseCount, maxCourseCount, Sections:[...], CourseFormats:[{uuid, minTermCount, CourseType}] },
     * ... ] }, Message } }. This is the only GET that exposes the FULL Program Term state (dates,
     * credits, course counts, CourseFormats); the reduced GET /program/{cohortId} does not. Used to
     * read back a Program Term after {@link #updateProgramTerm} and to source its CourseFormats.
     */
    public Response getCohortDetails(String cohortId, String bearerToken) {
        return restClient.get(programBase() + "/" + cohortId + "/getCohortDetails", bearerToken);
    }

    /**
     * GET /api/v1/program/termCourse/{termCourseId} - full termCourse detail, including its whole
     * relation chain in one call: {@code Data.termCourse.CourseMaster.uuid} (the courseMaster id),
     * {@code Data.termCourse.ProgramTerm.uuid}, {@code Data.termCourse.ProgramTerm.Program.uuid} (the
     * cohort id), and {@code Data.termCourse.ProgramTerm.Program.ProgramMaster.uuid} (the program id).
     * Verified via HAR capture (hawkeye-apis_2026-07-23_09-54-52.har). Used to cross-verify that a
     * business entity attached to a termCourse (e.g. an assignment) genuinely belongs to the runtime
     * Program/Cohort/ProgramTerm/Course chain, not just "some" termCourse.
     */
    public Response getTermCourseDetail(String termCourseId, String bearerToken) {
        return restClient.get(programBase() + "/termCourse/" + termCourseId, bearerToken);
    }

    /**
     * PATCH /api/v1/program/termCourse/{termCourseId} - the admin "edit course" form submit for a
     * course already attached to a section (this is how the UI toggles gamification, credits, hours,
     * TAs/faculty, etc.). Stamps {@code updatedBy}, exactly as {@link #updateProgramTerm} does; the
     * caller supplies every other field verbatim.
     *
     * <p>CRITICAL, verified from a HAR capture of the admin capstone flow: this endpoint expects a
     * COMPLETE course-edit object (31 keys), NOT a sparse patch. Despite the HTTP verb, sending only
     * the changed field risks the backend treating the absent fields as cleared. The caller is
     * therefore responsible for a read-modify-write: read the current state via
     * {@link #getTermCourseDetail}, build the full object from it, and pass that whole object in
     * {@code fields}. This method only stamps {@code updatedBy} (the admin user id) - it neither
     * defaults nor invents any field.
     */
    public Response updateTermCourse(String termCourseId, Map<String, Object> fields, String updatedBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("updatedBy", updatedBy);
        return restClient.patch(programBase() + "/termCourse/" + termCourseId, payload, bearerToken);
    }
}
