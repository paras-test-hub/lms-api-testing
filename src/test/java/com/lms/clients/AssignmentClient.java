package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * The single client for the entire assignment domain - admin create/verify AND student
 * retrieve/submit (later: admin evaluate/grade, student view grade/feedback). All assignment
 * operations stay in ONE client rather than being split by actor, since they all belong to the
 * same backend domain; only the bearer token passed by the caller differs (admin token for
 * create/verify, student token for retrieve/submit). Mirrors the existing precedent of
 * {@link AuthenticationClient}, which likewise serves both admin and student login from one class
 * at this same top-level {@code com.lms.clients} package (not an actor-specific subpackage).
 *
 * <p>Endpoints verified from real HAR captures, NOT the OpenAPI spec:
 * <ul>
 *   <li>Admin create/verify (hawkeye-apis_2026-07-21_22-22-20.har, re-confirmed field-for-field
 *       against hawkeye-apis_2026-07-23_09-54-52.har): POST/GET on
 *       {@code .../program/termCourse/{termCourseId}/section/{sectionId}/...}</li>
 *   <li>Student retrieve/submit (hawkeye-apis_2026-07-23_09-54-52.har): GET on
 *       {@code .../program/assignments/term/{programTermId}/getAllStudentAssignments} and GET/POST
 *       on {@code .../organization/assignmentUpload/{assignmentUuid}}</li>
 * </ul>
 * Returns the raw Response; the caller ({@code AssignmentWorkflow}) builds payloads and verifies.
 */
public class AssignmentClient {

    private final RestClient restClient;

    public AssignmentClient() {
        this.restClient = new RestClient();
    }

    private static String termCourseSectionBase(String termCourseId, String sectionId) {
        return TestConfig.API_V1 + "/program/termCourse/" + termCourseId + "/section/" + sectionId;
    }

    // ---- Admin: create + verify on a termCourse/section ----

    /**
     * POST .../createAssignmentDetails - creates (and, when {@code isDraft=false}, publishes) an
     * assignment on the given termCourse/section. The full field set (including createdBy) is built
     * by the caller and passed as {@code payload}, mirroring the real frontend request. Verified
     * response: 201 with {@code { IsSuccess, Data:{ uuid, name, isDraft, submissionDate, ... }, message }}.
     */
    public Response createAssignment(String termCourseId, String sectionId, Map<String, Object> payload, String bearerToken) {
        return restClient.post(
                termCourseSectionBase(termCourseId, sectionId) + "/createAssignmentDetails",
                new HashMap<>(payload), bearerToken);
    }

    /**
     * GET .../getAllSessionAssignmentDetails?searchParam= - the assignments attached to this
     * termCourse/section, used to read an assignment back after creation. Verified response:
     * {@code { IsSuccess, Data:[ { uuid, name, assignmentDetails, isDraft, submissionDate,
     * marksReleaseDate, totalPoints, weightagePercentage, AssignmentResource:{uuid,name}, ... } ],
     * Count, message }}.
     */
    public Response getAssignments(String termCourseId, String sectionId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        return restClient.get(
                termCourseSectionBase(termCourseId, sectionId) + "/getAllSessionAssignmentDetails",
                queryParams, bearerToken);
    }

    // ---- Student: retrieve + submit ----

    /**
     * GET .../program/assignments/term/{programTermId}/getAllStudentAssignments?sort=submissionDate&amp;
     * type=all&amp;search= - the student's visible assignment list for a program term (spans every
     * course in that term, not just the runtime one - the caller must locate its own assignment by
     * uuid, never by name). Verified response: {@code { message, data:{ assignment:[ { uuid, name,
     * totalPoints, weightagePercentage, submissionDate, marksReleaseDate, assignmentDetails, isDraft,
     * id, termCourseId, sectionId, TermCourse:{uuid, CourseMaster:{name,courseCode}, ProgramTerm:{uuid}},
     * AssignmentResource:{name}, latestSubmissionExists, ... } ] } }.
     *
     * <p>Deliberately NOT using GET /api/v1/users/allAssignments: verified live in the HAR to return
     * {@code isSuccess:false} with an unpopulated {@code programTermId=undefined} query param in this
     * capture - looks like an unrelated/broken frontend call, not the real "list my assignments" path.
     */
    public Response getAllStudentAssignments(String programTermId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("sort", "submissionDate");
        queryParams.put("type", "all");
        queryParams.put("search", "");
        return restClient.get(
                TestConfig.API_V1 + "/program/assignments/term/" + programTermId + "/getAllStudentAssignments",
                queryParams, bearerToken);
    }

    /**
     * GET .../organization/assignmentUpload/{assignmentUuid} - full assignment detail as seen by a
     * student, including the {@code TermCourse} relation (uuid, CourseMaster, ProgramTerm) needed to
     * cross-verify the assignment belongs to the runtime course/program-term. Verified response:
     * {@code { message, data:{ AssignmentDetail:{ uuid, name, assignmentDetails, submissionDate,
     * marksReleaseDate, totalPoints, weightagePercentage, TermCourse:{uuid, CourseMaster:{name,
     * courseCode}, ProgramTerm:{uuid}}, isEditable, ... }, resubmissionPopup:{...} } }.
     */
    public Response getAssignmentDetail(String assignmentUuid, String bearerToken) {
        return restClient.get(TestConfig.API_V1 + "/organization/assignmentUpload/" + assignmentUuid, bearerToken);
    }

    /**
     * POST .../organization/assignmentUpload/{assignmentUuid} - submits the assignment. The real
     * frontend request also carries file-upload fields (fileName/url/filestack/fileKey) backed by a
     * genuine third-party Filestack multipart upload (prefetch/start/upload/complete against
     * filestackapi.com) - deliberately NOT replicated here (would require a separate third-party
     * integration + credentials this framework doesn't have). This submits a TEXT/LINK-only payload
     * instead ({@code assignmentDescription}/{@code driveLink}/{@code driveFileName}/{@code
     * submittedDate}), a reduced but non-invented variant: the assignment's own {@code
     * assignmentFormatType.types = ["desc","file","link"]} (set at creation) declares description/link
     * submissions as independently valid, not exclusively tied to a file upload. Verified response:
     * 200 with {@code { message, data:{ uuid, userId, submittedDate, studentSubmissionReview,
     * assignmentDescription, driveLink, AssignmentDetail:{uuid,...}, ... } }.
     */
    public Response submitAssignment(String assignmentUuid, Map<String, Object> payload, String bearerToken) {
        return restClient.post(
                TestConfig.API_V1 + "/organization/assignmentUpload/" + assignmentUuid,
                new HashMap<>(payload), bearerToken);
    }

    // ---- Admin: update (e.g. Draft -> Published transition) ----

    /**
     * PATCH .../program/assignmentDetail/{assignmentUuid} - updates an assignment (verified via HAR
     * capture hawkeye-apis_2026-07-23_17-59-37.har as the mechanism the admin UI uses to flip
     * {@code isDraft} true -&gt; false, publishing a draft). Unlike create, the path carries only the
     * assignment's own uuid (no termCourseId/sectionId). It is a full-payload "resubmit unchanged,
     * change the one field" PATCH - the real UI loads the assignment's current state and resends it
     * with the edited field(s) changed, not a partial/sparse patch. The caller ({@code
     * AssignmentWorkflow}) is responsible for building that payload from the assignment's current
     * state (e.g. via {@link #getAssignments}) so unrelated fields are never mutated as a side effect.
     * Verified response: 200 with {@code { IsSuccess, Data:{ uuid, name, isDraft, ... }, message:
     * "Assignment updated successfully" } } (a Google Calendar {@code gcEventId} object also appears on
     * publish as a side effect - not something the caller sends or needs to assert on).
     */
    public Response updateAssignment(String assignmentUuid, Map<String, Object> payload, String bearerToken) {
        return restClient.patch(
                TestConfig.API_V1 + "/program/assignmentDetail/" + assignmentUuid,
                new HashMap<>(payload), bearerToken);
    }
}
