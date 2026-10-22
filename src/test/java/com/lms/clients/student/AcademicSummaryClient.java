package com.lms.clients.student;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

/**
 * Student "academicSummary" tag - 3 endpoints, all bearer-auth-only, confirmed directly
 * from student_openapi.json (paths/params/security are trustworthy; response BODIES are
 * not - the spec's response schemas are unverified placeholders, { "success": true },
 * generated from frontend route extraction rather than real response capture):
 *
 *   GET /api/v1/users/stats/getUserAcademicsStats
 *   GET /api/v1/users/stats/{id}/getUserAcademicsStatsDomainWise
 *   GET /api/v1/program/{programTermId}/getLastestAssignmentsEventsClubs
 *
 * The real values for {id} (a domain id) and {programTermId} have not been confirmed yet -
 * pending real student credentials to inspect an authenticated response and find them (see
 * AcademicSummary.feature's @pending-real-id scenarios).
 */
public class AcademicSummaryClient {

    private static final String STATS_BASE = TestConfig.API_V1 + "/users/stats";
    private static final String PROGRAM_BASE = TestConfig.API_V1 + "/program";

    private final RestClient restClient;

    public AcademicSummaryClient() {
        this.restClient = new RestClient();
    }

    public Response getAcademicStats(String bearerToken) {
        return restClient.get(STATS_BASE + "/getUserAcademicsStats", bearerToken);
    }

    public Response getAcademicStatsDomainWise(String domainId, String bearerToken) {
        return restClient.get(STATS_BASE + "/" + domainId + "/getUserAcademicsStatsDomainWise", bearerToken);
    }

    public Response getLatestAssignmentsEventsClubs(String programTermId, String bearerToken) {
        return restClient.get(PROGRAM_BASE + "/" + programTermId + "/getLastestAssignmentsEventsClubs", bearerToken);
    }
}
