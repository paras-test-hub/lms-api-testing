package com.lms.workflows;

import com.lms.clients.AuthenticationClient;
import com.lms.config.TestConfig;
import com.lms.context.ScenarioContext;
import com.lms.models.LoginResponse;
import com.lms.reporting.EnvironmentSetupReport;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Student authentication - the FINAL step of Environment Setup, not a business workflow. It
 * takes the isolated Student that Environment Setup already created and assigned to the
 * Program/Cohort/Program Term/Section, logs it in with that same dynamically-generated
 * credential pair (context.getStudentEmail() / getStudentPassword() - produced by
 * BootstrapWorkflow#createAndVerifyStudent, NEVER a static/.env account), and stores the
 * resulting access token, refresh token, and user id in ScenarioContext. Only once this session
 * exists is the environment truly "Ready".
 *
 * This authenticated session is the single source of student credentials for every downstream
 * Student business workflow (Dashboard, Assignments, Attendance, Quizzes, Announcements, Grades,
 * ...). Those workflows MUST consume {@code context.getStudentToken()} - they must never log the
 * student in themselves and must never read the static debug-only student credentials from .env
 * (that pair is reserved for Authentication.feature's own endpoint tests, and is enforced
 * non-load-bearing by StudentCredentialIsolationTest).
 *
 * Creates no academic entities and registers nothing for cleanup (a login produces a session,
 * not a persistent resource). Publishes the accumulated Environment Setup summary at the end,
 * since this is the last provisioning action.
 *
 * Request shape verified from a real HAR capture (hawkeye-apis_2026-07-16_14-57-40.har) of a
 * real student login: POST /api/v1/auth/studentLogin { officialEmail, password, captcha }.
 * The dev environment only presence-checks the captcha field (any non-empty string passes; the
 * real reCAPTCHA token is not validated server-side there - see AuthenticationClient's
 * Javadoc), so a configurable placeholder (TestConfig#getDevRecaptchaToken, sourced from
 * DEV_RECAPTCHA_TOKEN in .env) is sent instead of minting a real token.
 */
public class StudentLoginWorkflow {

    private static final Logger log = LoggerFactory.getLogger(StudentLoginWorkflow.class);

    private final AuthenticationClient authClient;

    public StudentLoginWorkflow() {
        this.authClient = new AuthenticationClient();
    }

    /**
     * Logs the Environment-Setup-created Student in and stores the resulting session
     * (access token, refresh token, user id) in ScenarioContext for downstream Student business
     * workflows to consume. Fails immediately if Environment Setup hasn't produced student
     * credentials yet, or if the logged-in identity doesn't match the student that was created.
     * Adds the "Student Authenticated" / "Student Session Stored" checkpoints and then publishes
     * the full Environment Setup summary, since authentication is the last provisioning step.
     */
    public void loginStudent(ScenarioContext context) {
        String email = context.execution().getStudentEmail();
        String password = context.execution().getStudentPassword();

        assertThat(email).as("studentEmail must be set by Environment Setup before Student Login can run").isNotBlank();
        assertThat(password).as("studentPassword must be set by Environment Setup before Student Login can run").isNotBlank();

        log.info("==============================\nStudent Authentication\nStudent: {}\n==============================", email);

        Response loginResponse = authClient.studentLogin(email, password, TestConfig.getDevRecaptchaToken());
        assertThat(loginResponse.statusCode())
                .as("student login must succeed. Response: " + loginResponse.getBody().asString())
                .isEqualTo(200);

        LoginResponse body = loginResponse.as(LoginResponse.class);
        assertThat(body.getData()).as("login response must expose Data").isNotNull();
        assertThat(body.getData().getToken()).as("login response must expose Data.token").isNotNull();

        String accessToken = body.getData().getToken().getAccess().getToken();
        assertThat(accessToken).as("login response must expose Data.token.access.token").isNotBlank();
        context.setStudentToken(accessToken);

        String refreshToken = body.getData().getToken().getRefresh() != null
                ? body.getData().getToken().getRefresh().getToken() : null;
        if (refreshToken != null) {
            context.setStudentRefreshToken(refreshToken);
        }

        Object uuid = body.getData().getUser() != null ? body.getData().getUser().get("uuid") : null;
        assertThat(uuid).as("login response Data.user.uuid must be present").isNotNull();
        assertThat(uuid.toString())
                .as("logged-in user's uuid must match the student created during Environment Setup")
                .isEqualTo(context.getStudentUuid());
        context.setStudentUserId(uuid.toString());

        // Report - never logs the token VALUES, only confirms the session was established/stored.
        EnvironmentSetupReport.checkpoint(context, "Student Authenticated",
                "Email: " + email, "Login: POST /api/v1/auth/studentLogin -> 200");
        EnvironmentSetupReport.checkpoint(context, "Student Session Stored",
                "Access token: stored in ScenarioContext",
                "Refresh token: " + (refreshToken != null ? "stored in ScenarioContext" : "not returned"),
                "Student UUID: " + uuid);
        EnvironmentSetupReport.checkpoint(context, "Environment Ready",
                "Student created, assigned, and authenticated session stored.");

        log.info("==============================\nStudent Authentication\nStudent: {}\nResult: SUCCESS (session stored)\n==============================", email);

        // Authentication is the final Environment Setup action - the environment is now "Ready"
        // (student created, assigned, AND an authenticated session stored). Flip the suite-scoped
        // readiness flag so business journeys' requireEnvironmentReady() guard passes, then publish
        // the summary BEFORE any business journey begins. Reporting/flag only: no effect on ordering.
        context.execution().markEnvironmentReady();
        EnvironmentSetupReport.publish(context);
    }
}
