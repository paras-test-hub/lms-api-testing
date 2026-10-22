package com.lms.clients.admin;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin user provisioning. Paths/bodies verified from lms-admin-frontend
 * organizationSlice.js (createUser / getUserById thunks) - NOT the OpenAPI spec.
 *
 *   POST /api/v1/users/org/{orgId}              - create a user; body { ...fields, createdBy, organizationId }
 *   GET  /api/v1/users/org/{orgId}/user/{userId} - fetch a single user (read-back verification)
 *
 * Follows the existing client convention: owns a RestClient, injects createdBy/organizationId
 * the same way the frontend thunk does, takes the bearer token last, and returns the raw
 * Response. No assertions or business logic here - that lives in the Environment Setup
 * workflow orchestrator (BootstrapWorkflow - TODO: rename to EnvironmentSetupWorkflow).
 */
public class UserClient {

    private final RestClient restClient;

    public UserClient() {
        this.restClient = new RestClient();
    }

    private static String usersOrgBase(String orgId) {
        return TestConfig.API_V1 + "/users/org/" + orgId;
    }

    /** POST /api/v1/users/org/{orgId} - createUser thunk. */
    public Response createStudent(String orgId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        payload.put("organizationId", orgId);
        return restClient.post(usersOrgBase(orgId), payload, bearerToken);
    }

    /** GET /api/v1/users/org/{orgId}/user/{userId} - getUserById thunk. */
    public Response getStudentById(String orgId, String userId, String bearerToken) {
        return restClient.get(usersOrgBase(orgId) + "/user/" + userId, bearerToken);
    }
}
