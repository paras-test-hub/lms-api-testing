package com.lms.clients.admin;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin course-master CRUD (create/read/update). Paths and request shapes verified directly
 * from a real HAR network capture (hawkeye-apis_2026-07-15_06-48-11.har) of the admin
 * frontend's "add course" flow - NOT the OpenAPI spec.
 *
 * The real flow is POST create -> PATCH (called TWICE with progressively fuller bodies) ->
 * GET verify. Both PATCHes hit the SAME endpoint below - {@link #update} is called twice by
 * the workflow with different field maps, not two separate client methods.
 *
 * No delete() - course deletion is out of scope for now; created courses are only registered
 * in the cleanup registry (ResourceType.COURSE) for a future cleanup handler.
 */
public class CourseClient {

    private final RestClient restClient;

    public CourseClient() {
        this.restClient = new RestClient();
    }

    private static String orgBase(String orgId) {
        return TestConfig.API_V1 + "/organization/" + orgId;
    }

    /** POST /api/v1/organization/{orgId}/courseMaster - creates a course master. */
    public Response create(String orgId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(orgBase(orgId) + "/courseMaster", payload, bearerToken);
    }

    /** GET /api/v1/organization/{orgId}/courseMaster/{courseId} - fetch a single course master. */
    public Response getById(String orgId, String courseId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/courseMaster/" + courseId, bearerToken);
    }

    /** PATCH /api/v1/organization/{orgId}/courseMaster/{courseId} - real flow calls this twice; see class Javadoc. */
    public Response update(String orgId, String courseId, Map<String, Object> fields, String updatedBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("updatedBy", updatedBy);
        return restClient.patch(orgBase(orgId) + "/courseMaster/" + courseId, payload, bearerToken);
    }
}
