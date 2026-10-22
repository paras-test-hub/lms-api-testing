package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Authenticated student read endpoints, ported from the already-verified
 * lms-api-testing/projects/api/clients/student.js. These 8 endpoints were captured from a
 * live authenticated session and confirmed to return 200 with a valid bearer token, 401
 * without one - not derived from the OpenAPI spec, whose response schemas are unverified
 * placeholders ({ success: true }) since the spec was generated from frontend route
 * extraction rather than real response capture.
 *
 * Deliberately grouped in one client rather than split per OpenAPI tag: the endpoints span
 * 5 different tags (program, course, event, allyBot, authentication - see student_openapi.json)
 * because that's how they were actually verified together as one authenticated "dashboard"
 * probe, not because they share a domain. Split into per-tag clients (AcademicSummaryClient,
 * CourseClient, etc.) once those tags' own endpoints get independently verified against the
 * live API in a later phase - don't force a premature split based on tag name alone.
 */
public class StudentDashboardClient {

    private static final String USERS_BASE = TestConfig.API_V1 + "/users";

    private static final Map<String, String> ENDPOINTS = new HashMap<>();

    static {
        ENDPOINTS.put("program", USERS_BASE + "/program");
        ENDPOINTS.put("courses", USERS_BASE + "/courses");
        ENDPOINTS.put("allAssignments", USERS_BASE + "/allAssignments");
        ENDPOINTS.put("pendingActionCounts", USERS_BASE + "/getPendingActionCounts");
        ENDPOINTS.put("upcomingBirthdays", USERS_BASE + "/birthdays/upcoming");
        ENDPOINTS.put("notifications", USERS_BASE + "/notification/get-user-Notification");
        ENDPOINTS.put("allEvents", TestConfig.API_V1 + "/event/getAllEvents");
    }

    private final RestClient restClient;

    public StudentDashboardClient() {
        this.restClient = new RestClient();
    }

    /** GET a simple (no-path-param) endpoint by key, with the bearer token attached (or null for unauthenticated). */
    public Response get(String key, String bearerToken) {
        String path = ENDPOINTS.get(key);
        if (path == null) {
            throw new IllegalArgumentException("Unknown student dashboard endpoint key: " + key
                    + ". Known keys: " + ENDPOINTS.keySet());
        }
        return restClient.get(path, bearerToken);
    }

    /** GET /api/v1/users/{uuid}/userProfile - the one endpoint here needing a path param. */
    public Response getUserProfile(String uuid, String bearerToken) {
        return restClient.get(USERS_BASE + "/" + uuid + "/userProfile", bearerToken);
    }
}
