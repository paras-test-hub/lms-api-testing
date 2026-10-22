package com.lms.clients.admin;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin "program" tag - Program Master CRUD subset. The "program" tag has 250+ endpoints
 * total spanning many unrelated sub-domains (attendance, NPS, placement, quizzes,
 * proctoring, zoom...); this client deliberately covers only the 5 core Program Master
 * lifecycle operations, per explicit scope decision (see PROGRAM_MASTER_CRUD_FLOW.md).
 *
 * Paths and REQUEST shapes verified directly from
 * lms-admin-frontend/src/redux/program/programSlice.js - NOT from the OpenAPI spec, whose
 * documented POST/PATCH request body (fields wrapped under an "obj" key) does not match the
 * real frontend, which spreads fields at the top level instead:
 * {@code { ...fields, createdBy }} / {@code { ...fields, updatedBy }}.
 *
 *   GET    /api/v1/organization/{orgId}/programMasterMain                  (list, paginated)
 *   POST   /api/v1/organization/{orgId}/programMaster                     (create)
 *   GET    /api/v1/organization/{orgId}/programMaster/{programMasterId}   (get single)
 *   PATCH  /api/v1/organization/{orgId}/programMaster/{programMasterId}   (update)
 *   DELETE /api/v1/organization/{orgId}/programMaster/{programMasterId}   (delete)
 *
 * orgId is per-admin-account, not a fixed config value - read from the admin login response
 * at Data.user.Organization.uuid (confirmed via programSlice.js's setTokenValues()).
 * Response BODIES are only partially verified - see class Javadoc in ProgramMasterSteps.
 */
public class ProgramMasterClient {

    private final RestClient restClient;

    public ProgramMasterClient() {
        this.restClient = new RestClient();
    }

    private static String orgBase(String orgId) {
        return TestConfig.API_V1 + "/organization/" + orgId;
    }

    /**
     * Verified live: this endpoint 500s ("Internal Server Error") when called with NO query
     * params at all, even though the spec marks searchParam/pageSize/pageNo/filter as all
     * optional - almost certainly a backend bug (likely parseInt(undefined) -> NaN feeding a
     * Mongo .limit()/.skip()). The real frontend always sends these four params with defaults
     * (programSlice.js:71: searchParam="", pageSize=10, pageNo=1, filter=""), so this client
     * does the same rather than relying on the spec's "optional" claim.
     */
    public Response list(String orgId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", 10);
        queryParams.put("pageNo", 1);
        queryParams.put("filter", "");
        return restClient.get(orgBase(orgId) + "/programMasterMain", queryParams, bearerToken);
    }

    public Response create(String orgId, Map<String, Object> fields, String createdBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("createdBy", createdBy);
        return restClient.post(orgBase(orgId) + "/programMaster", payload, bearerToken);
    }

    public Response getById(String orgId, String programMasterId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/programMaster/" + programMasterId, bearerToken);
    }

    public Response update(String orgId, String programMasterId, Map<String, Object> fields, String updatedBy, String bearerToken) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("updatedBy", updatedBy);
        return restClient.patch(orgBase(orgId) + "/programMaster/" + programMasterId, payload, bearerToken);
    }

    public Response delete(String orgId, String programMasterId, String bearerToken) {
        return restClient.delete(orgBase(orgId) + "/programMaster/" + programMasterId, bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgId}/category - not part of Program Master CRUD itself,
     * but a hard dependency confirmed live: creating a Program Master 400s with
     * "\"categoryId\" is required" without one. Used to fetch a real, existing categoryId
     * rather than fabricating one.
     */
    public Response listCategories(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/category", bearerToken);
    }
}
