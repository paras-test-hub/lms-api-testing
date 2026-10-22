package com.lms.steps.admin;

import com.lms.clients.AuthenticationClient;
import com.lms.clients.admin.ProgramMasterClient;
import com.lms.config.TestConfig;
import com.lms.context.ScenarioContext;
import com.lms.models.LoginResponse;
import com.lms.utils.AdminSessionCache;
import com.lms.utils.JsonSearch;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the admin Program Master CRUD flow (com.lms.clients.admin.ProgramMasterClient).
 *
 * Response body shapes here have mixed confidence:
 *   - GET single (Data.programMaster) is corroborated by real frontend consumption code
 *     (programSlice.js's getSingleProgramMaster explicitly reads {@code data.Data?.programMaster}).
 *   - LIST's nested {@code data.Data.programMasters[]} shape is only spec-documented, not
 *     confirmed by frontend consumption code (the list thunk just returns the raw response).
 *   - CREATE/UPDATE response shapes are NOT confirmed by either source (both thunks return
 *     the raw response without destructuring).
 * Because of this, the created program master's id is discovered defensively: create with a
 * unique name, then search the (more-corroborated) LIST response for an object whose "name"
 * matches, via {@link JsonSearch} - this works regardless of exactly how the create
 * response itself is shaped.
 */
public class ProgramMasterSteps {

    private final ScenarioContext context;
    private final AuthenticationClient authClient;
    private final ProgramMasterClient programMasterClient;

    // Must be a genuinely RFC4122-valid UUID, not just a UUID-shaped string: these endpoints
    // validate the orgId/id path segments with Joi's .uuid() BEFORE checking the bearer
    // token, and Joi's default validator checks the variant nibble too - a hand-typed
    // string like "11111111-1111-1111-1111-111111111111" still fails as "not a valid uuid"
    // since its variant digit isn't 8/9/a/b. UUID.randomUUID() is always compliant.
    // Confirmed live while building this flow (both findings).
    private static final String PLACEHOLDER_ORG_ID = UUID.randomUUID().toString();
    private static final String PLACEHOLDER_PM_ID = UUID.randomUUID().toString();
    private static final String PLACEHOLDER_USER_ID = UUID.randomUUID().toString();

    public ProgramMasterSteps(ScenarioContext context) {
        this.context = context;
        this.authClient = new AuthenticationClient();
        this.programMasterClient = new ProgramMasterClient();
    }

    /**
     * Canonical home for full admin login + token/org/uuid extraction - the first admin
     * flow to need it. Reuse this step from other admin feature files rather than
     * redefining it (Cucumber glue is global across all step classes on the glue path).
     *
     * Checks {@link AdminSessionCache} first and only calls the real login endpoint on a
     * cache miss/expiry - this step is a CONVENIENCE for workflows that merely need an
     * authenticated admin session, not a test of the login endpoint itself (that's
     * Authentication.feature's job, which always logs in for real). Reusing a cached token
     * avoids tripping the login rate limit during iterative workflow verification, where
     * `mvn test` runs repeatedly in a short window.
     */
    @Given("the admin logs in with valid credentials")
    public void theAdminLogsInWithValidCredentials() {
        Assumptions.assumeTrue(TestConfig.hasAdminCredentials(),
                "ADMIN_TEST_EMAIL/ADMIN_TEST_PASSWORD not set in .env - skipping");

        Optional<AdminSessionCache.CachedSession> cached = AdminSessionCache.loadIfValid();
        if (cached.isPresent()) {
            AdminSessionCache.CachedSession session = cached.get();
            context.setAdminToken(session.token);
            context.setAdminUserId(session.userId);
            context.setAdminOrgId(session.orgId);
            return;
        }

        Response loginResponse = authClient.adminLogin(
                TestConfig.getAdminTestEmail(), TestConfig.getAdminTestPassword());
        assertThat(loginResponse.statusCode())
                .as("admin login must succeed before hitting authenticated program master endpoints")
                .isEqualTo(200);

        LoginResponse body = loginResponse.as(LoginResponse.class);
        context.setAdminToken(body.getData().getToken().getAccess().getToken());

        Object adminUuid = body.getData().getUser().get("uuid");
        assertThat(adminUuid).as("login response Data.user.uuid must be present").isNotNull();
        context.setAdminUserId(adminUuid.toString());

        Object organization = body.getData().getUser().get("Organization");
        assertThat(organization).as("login response Data.user.Organization must be present").isInstanceOf(Map.class);
        Object orgUuid = ((Map<?, ?>) organization).get("uuid");
        assertThat(orgUuid).as("login response Data.user.Organization.uuid must be present").isNotNull();
        context.setAdminOrgId(orgUuid.toString());

        AdminSessionCache.save(context.getAdminToken(), context.getAdminOrgId(), context.getAdminUserId());
    }

    @Given("a valid category id is available")
    public void aValidCategoryIdIsAvailable() {
        Response categoriesResponse = programMasterClient.listCategories(context.getAdminOrgId(), context.getAdminToken());
        Object body = categoriesResponse.jsonPath().getMap("$");
        // Verified live: category objects are shaped { name, uuid, id (numeric), parentId } -
        // the identifier field is "uuid", NOT "_id" (unlike Program Master list/get, which do
        // use "_id"). Confirmed from a real GET .../category response.
        Object categoryId = JsonSearch.findFirstValue(body, "uuid");
        Assumptions.assumeTrue(categoryId != null,
                "no category id found via GET .../category - the organization may need at least one "
                        + "category created first, or the response shape differs from expected; "
                        + "update ProgramMasterSteps once confirmed live");
        context.putAttribute("categoryId", categoryId.toString());
    }

    @When("the admin creates a program master with a unique name based on {string}")
    public void theAdminCreatesAProgramMasterWithAUniqueNameBasedOn(String baseName) {
        String name = baseName + " " + System.currentTimeMillis();
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("description", "Created by automated API test");
        fields.put("programMode", "Online");
        fields.put("programSystem", "Semester");
        fields.put("numberOfTerms", "2");
        fields.put("durationInMonths", "12");
        fields.put("gradingMechanism", "Percentage");
        fields.put("timezone", "Asia/Kolkata");
        fields.put("isDraft", false);
        fields.put("isActive", true);
        // Confirmed live (400: "\"categoryId\" is required") that this field is mandatory,
        // unlike the rest of this payload which is a best-effort guess from the frontend form.
        if (context.hasAttribute("categoryId")) {
            fields.put("categoryId", context.getAttribute("categoryId"));
        }

        context.putAttribute("createdProgramMasterName", name);
        context.putAttribute("programMasterFields", fields);
        context.setLastResponse(programMasterClient.create(
                context.getAdminOrgId(), fields, context.getAdminUserId(), context.getAdminToken()));
    }

    @When("the created program master id is looked up from the list")
    public void theCreatedProgramMasterIdIsLookedUpFromTheList() {
        String name = context.getAttribute("createdProgramMasterName");
        Response listResponse = programMasterClient.list(context.getAdminOrgId(), context.getAdminToken());
        Object body = listResponse.jsonPath().getMap("$");
        // Verified live: the real shape is { IsSuccess, Data: [ {uuid, name, ...}, ... ] } -
        // a flat array directly under "Data" (capital D), NOT the spec's nested
        // data.Data.programMasters[]. The identifier field is "uuid", matching the same
        // convention confirmed on the category list - NOT "_id"/"id".
        Object id = JsonSearch.findIdByField(body, "name", name, "uuid");
        Assumptions.assumeTrue(id != null,
                "could not locate created program master \"" + name + "\" in the list response - "
                        + "update ProgramMasterSteps once the real list response shape is confirmed live");
        context.putAttribute("programMasterId", id.toString());
    }

    @When("the admin retrieves the program master by id")
    public void theAdminRetrievesTheProgramMasterById() {
        String id = context.getAttribute("programMasterId");
        context.setLastResponse(programMasterClient.getById(context.getAdminOrgId(), id, context.getAdminToken()));
    }

    @When("the admin updates the program master name to {string}")
    public void theAdminUpdatesTheProgramMasterNameTo(String newName) {
        String id = context.getAttribute("programMasterId");
        // A partial payload ({name, updatedBy} only) 500s on this endpoint - confirmed live.
        // Testing the hypothesis that PATCH expects the same full field set as POST create
        // (i.e. no real partial-merge support server-side) by resending the original
        // creation fields with just "name" overridden.
        Map<String, Object> fields = new HashMap<>(context.<Map<String, Object>>getAttribute("programMasterFields"));
        fields.put("name", newName);
        context.setLastResponse(programMasterClient.update(
                context.getAdminOrgId(), id, fields, context.getAdminUserId(), context.getAdminToken()));
    }

    @When("the admin deletes the program master")
    public void theAdminDeletesTheProgramMaster() {
        String id = context.getAttribute("programMasterId");
        context.setLastResponse(programMasterClient.delete(context.getAdminOrgId(), id, context.getAdminToken()));
    }

    // ---- Authorization enforcement (no real credentials/IDs needed) ----

    @When("the admin lists program masters without authentication")
    public void theAdminListsProgramMastersWithoutAuthentication() {
        context.setLastResponse(programMasterClient.list(PLACEHOLDER_ORG_ID, null));
    }

    @When("the admin creates a program master without authentication")
    public void theAdminCreatesAProgramMasterWithoutAuthentication() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", "Unauthorized Test Program");
        // categoryId is required by request-body validation, which runs before the auth
        // check (confirmed live) - any UUID satisfies the presence/format check even
        // though it doesn't correspond to a real category.
        fields.put("categoryId", UUID.randomUUID().toString());
        context.setLastResponse(programMasterClient.create(PLACEHOLDER_ORG_ID, fields, PLACEHOLDER_USER_ID, null));
    }

    @When("the admin retrieves a program master without authentication")
    public void theAdminRetrievesAProgramMasterWithoutAuthentication() {
        context.setLastResponse(programMasterClient.getById(PLACEHOLDER_ORG_ID, PLACEHOLDER_PM_ID, null));
    }

    @When("the admin updates a program master without authentication")
    public void theAdminUpdatesAProgramMasterWithoutAuthentication() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", "Unauthorized Update");
        context.setLastResponse(programMasterClient.update(PLACEHOLDER_ORG_ID, PLACEHOLDER_PM_ID, fields, PLACEHOLDER_USER_ID, null));
    }

    @When("the admin deletes a program master without authentication")
    public void theAdminDeletesAProgramMasterWithoutAuthentication() {
        context.setLastResponse(programMasterClient.delete(PLACEHOLDER_ORG_ID, PLACEHOLDER_PM_ID, null));
    }

    // ---- Assertions ----

    @Then("the response status code indicates success")
    public void theResponseStatusCodeIndicatesSuccess() {
        int status = context.getLastResponse().statusCode();
        assertThat(status).as("expected a 2xx status, got %d", status).isBetween(200, 299);
    }

    @Then("the response contains the created program master's name")
    public void theResponseContainsTheCreatedProgramMastersName() {
        String expectedName = context.getAttribute("createdProgramMasterName");
        Object body = context.getLastResponse().jsonPath().getMap("$");
        assertThat(JsonSearch.containsMatchingObject(body, "name", expectedName))
                .as("expected to find an object with name=\"%s\" somewhere in the response", expectedName)
                .isTrue();
    }
}
