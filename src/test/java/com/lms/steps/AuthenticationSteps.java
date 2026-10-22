package com.lms.steps;

import com.lms.clients.AuthenticationClient;
import com.lms.config.TestConfig;
import com.lms.context.ScenarioContext;
import com.lms.models.ErrorResponse;
import com.lms.models.LoginResponse;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for student/admin login. Payload construction supports two modes per
 * role, matching how the Playwright suite exercises the same endpoints:
 *   - raw payload (Given ... "with no fields" / "with fields:") for field-validation cases,
 *     posted as-is via AuthenticationClient#rawStudentLogin / #rawAdminLogin
 *   - well-formed email+password (Given "a student/admin with email ... and password ...")
 *     for credential-rejection cases
 *
 * "... with valid credentials" (a static, permanent account read from .env) exists ONLY for
 * admin - the framework keeps exactly one permanent account (the automation admin used to log
 * in) by design. There is no student equivalent: the only "valid student" is the one Environment
 * Setup creates and authenticates at runtime (see StudentLoginWorkflow); a static-credential
 * student login path used to exist here and has been retired (see StudentCredentialIsolationTest).
 */
public class AuthenticationSteps {

    private final ScenarioContext context;
    private final AuthenticationClient authClient;

    private Map<String, Object> pendingStudentPayload;
    private Map<String, Object> pendingAdminPayload;

    public AuthenticationSteps(ScenarioContext context) {
        this.context = context;
        this.authClient = new AuthenticationClient();
    }

    // ---- Given: request setup ----

    @Given("a student login request with no fields")
    public void aStudentLoginRequestWithNoFields() {
        pendingStudentPayload = new HashMap<>();
    }

    @Given("a student login request with fields:")
    public void aStudentLoginRequestWithFields(DataTable table) {
        pendingStudentPayload = new HashMap<>(table.asMap(String.class, String.class));
    }

    @Given("a student with email {string} and password {string}")
    public void aStudentWithEmailAndPassword(String email, String password) {
        pendingStudentPayload = null;
        context.setStudentEmail(email);
        context.setStudentPassword(password);
    }

    @Given("an admin login request with no fields")
    public void anAdminLoginRequestWithNoFields() {
        pendingAdminPayload = new HashMap<>();
    }

    @Given("an admin login request with fields:")
    public void anAdminLoginRequestWithFields(DataTable table) {
        pendingAdminPayload = new HashMap<>(table.asMap(String.class, String.class));
    }

    @Given("an admin with email {string} and password {string}")
    public void anAdminWithEmailAndPassword(String email, String password) {
        pendingAdminPayload = null;
        context.setAdminEmail(email);
        context.setAdminPassword(password);
    }

    @Given("an admin with valid credentials")
    public void anAdminWithValidCredentials() {
        Assumptions.assumeTrue(TestConfig.hasAdminCredentials(),
                "ADMIN_TEST_EMAIL/ADMIN_TEST_PASSWORD not set in .env - skipping");
        pendingAdminPayload = null;
        context.setAdminEmail(TestConfig.getAdminTestEmail());
        context.setAdminPassword(TestConfig.getAdminTestPassword());
    }

    @Given("the admin password is overridden to {string}")
    public void theAdminPasswordIsOverriddenTo(String password) {
        context.setAdminPassword(password);
    }

    // ---- When: submit ----

    @When("the student submits the login request")
    public void theStudentSubmitsTheLoginRequest() {
        Response response = (pendingStudentPayload != null)
                ? authClient.rawStudentLogin(pendingStudentPayload)
                : authClient.studentLogin(context.getStudentEmail(), context.getStudentPassword());
        context.setLastResponse(response);
    }

    @When("the admin submits the login request")
    public void theAdminSubmitsTheLoginRequest() {
        Response response = (pendingAdminPayload != null)
                ? authClient.rawAdminLogin(pendingAdminPayload)
                : authClient.adminLogin(context.getAdminEmail(), context.getAdminPassword());

        // The admin login endpoint is rate-limited (~8 rapid requests trips a 406 "Too many
        // login attempts" - see AdminSessionCache's Javadoc). These scenarios deliberately
        // bypass the session cache to exercise the real endpoint, so they're the ones exposed
        // to it. A 406 here isn't a genuine test outcome about the credentials/payload under
        // test - skip rather than let it fail the scenario with a misleading assertion error.
        Assumptions.assumeTrue(response.statusCode() != 406,
                "admin login endpoint rate-limited this request (406) - not a genuine test result, skipping");

        context.setLastResponse(response);
    }

    // ---- Then: shared assertions (role-agnostic - operate on context.getLastResponse()) ----

    @Then("^the response status code is (\\d+)$")
    public void theResponseStatusCodeIs(int expectedStatus) {
        assertThat(context.getLastResponse().statusCode()).isEqualTo(expectedStatus);
    }

    @Then("^the response error message matches \"([^\"]*)\"$")
    public void theResponseErrorMessageMatches(String regex) {
        ErrorResponse error = context.getLastResponse().as(ErrorResponse.class);
        assertThat(error.getMessage()).isNotBlank();
        assertThat(Pattern.compile(regex).matcher(error.getMessage()).find())
                .as("expected error message [%s] to match /%s/", error.getMessage(), regex)
                .isTrue();
    }

    @Then("the response contains a valid access token")
    public void theResponseContainsAValidAccessToken() {
        LoginResponse body = context.getLastResponse().as(LoginResponse.class);
        assertThat(body.getIsSuccess()).isTrue();
        assertThat(body.getData().getToken().getAccess().getToken()).isNotBlank();
    }

    @Then("the response contains a valid refresh token")
    public void theResponseContainsAValidRefreshToken() {
        LoginResponse body = context.getLastResponse().as(LoginResponse.class);
        assertThat(body.getData().getToken().getRefresh().getToken()).isNotBlank();
    }
}
