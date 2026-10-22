package com.lms.clients.student;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Student-token Capstone read - the student-side view of a Capstone team and its gamification
 * configuration, exposing exactly ONE endpoint:
 *
 * <pre>
 *   GET /api/v1/program/capstoneGamifications/term/{programTermId}
 *          ?courseId={capstoneTermCourseId}&amp;sectionId={capstoneSectionId}
 * </pre>
 *
 * <p>Placed under {@code clients/student} rather than added to {@code clients/admin/CapstoneClient}
 * because that client is admin-scoped; this class follows the shape of its package sibling
 * {@link AcademicSummaryClient}.
 *
 * <p><b>The bearer token is always supplied by the caller.</b> This class sources NO credential of
 * its own - no static credential accessor, no configuration lookup, no environment read. The single
 * "valid student" in this framework is the one Environment Setup creates and authenticates at
 * runtime, whose session lives in {@code ScenarioContext}; the architectural guard test
 * {@code StudentCredentialIsolationTest} exists specifically to forbid clients and workflows from
 * introducing a second student identity, and this class must keep it green.
 *
 * <p>Response shape (HAR-verified): {@code Data} carries {@code cousreData}, {@code GamificationConfig},
 * {@code userGroup} and {@code doubtDiscussion}. <b>{@code cousreData} is the API's own misspelling of
 * "courseData" and MUST be preserved verbatim by consumers</b> - addressed exactly as spelled, with
 * zero correction.
 *
 * <p>Scope note: request shaping only. No assertions, no orchestration, no business logic, no retry,
 * no timeout override - the caller owns all verification.
 */
public class StudentCapstoneClient {

    private static final String PROGRAM_BASE = TestConfig.API_V1 + "/program";

    private final RestClient restClient;

    public StudentCapstoneClient() {
        this.restClient = new RestClient();
    }

    /**
     * GET /api/v1/program/capstoneGamifications/term/{programTermId}
     * ?courseId={capstoneTermCourseId}&amp;sectionId={capstoneSectionId} -&gt; 200.
     *
     * <p>{@code programTermId} is a PATH segment; {@code courseId} and {@code sectionId} are QUERY
     * parameters.
     *
     * <p><b>CRITICAL - {@code courseId} is keyed by the TermCourse uuid, NOT the CourseMaster uuid.</b>
     * The API's parameter name disagrees with the identity it actually keys off: passing the
     * CourseMaster uuid here is a defect, not an alternative. Callers must pass the Capstone
     * TermCourse uuid ({@code capstoneTermCourseId}).
     *
     * <p>The {@code studentToken} argument is the bearer credential and is supplied by the caller;
     * this class sources no credential itself and never logs the token.
     *
     * @param programTermId          program term uuid, sent as a path segment
     * @param capstoneTermCourseId   Capstone <b>TermCourse</b> uuid, sent as the {@code courseId} query parameter
     * @param capstoneSectionId      Capstone Section uuid, sent as the {@code sectionId} query parameter
     * @param studentToken           runtime student bearer token, supplied by the caller
     * @return the raw response, unasserted
     */
    public Response getCapstoneGamificationForTerm(String programTermId,
                                                   String capstoneTermCourseId,
                                                   String capstoneSectionId,
                                                   String studentToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("courseId", capstoneTermCourseId);
        queryParams.put("sectionId", capstoneSectionId);
        return restClient.get(PROGRAM_BASE + "/capstoneGamifications/term/" + programTermId,
                queryParams, studentToken);
    }
}
