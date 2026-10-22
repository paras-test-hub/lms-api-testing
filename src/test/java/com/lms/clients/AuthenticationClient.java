package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.models.LoginRequest;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.Map;

/**
 * Auth API client. Endpoints and payload shapes verified against the real backend
 * (api-dev.mastersunion.in) by the existing Playwright suite
 * (lms-api-testing/projects/api/clients/auth.js) - ported here rather than re-derived:
 *
 *   Student: POST /api/v1/auth/studentLogin  { officialEmail, password, captcha }
 *   Admin:   POST /api/v1/auth/login         { officialEmail, password }  (no captcha)
 *
 * Dev-only finding: the student endpoint's captcha field is checked for PRESENCE only on
 * api-dev.mastersunion.in - any non-empty string passes, the real reCAPTCHA v3 token is not
 * validated server-side there. Per project decision, this suite sends a configurable
 * placeholder captcha value (TestConfig#getDevRecaptchaToken, sourced from DEV_RECAPTCHA_TOKEN
 * in .env) rather than minting a real token (see also TestConfig#isRecaptchaVerificationEnabled).
 * Do not assume the dev-only presence-check behavior holds on production/white-label environments.
 *
 * Returns raw {@link Response} rather than deserialized POJOs - callers (step definitions)
 * decide whether to parse the body as {@link com.lms.models.LoginResponse} (2xx) or
 * {@link com.lms.models.ErrorResponse} (4xx), since a single scenario often needs to assert
 * on status code first before it's known which shape applies.
 */
public class AuthenticationClient {

    private static final String STUDENT_LOGIN_PATH = TestConfig.API_V1 + "/auth/studentLogin";
    private static final String ADMIN_LOGIN_PATH = TestConfig.API_V1 + "/auth/login";

    private final RestClient restClient;

    public AuthenticationClient() {
        this.restClient = new RestClient();
    }

    /** Posts whatever payload map is given - lets callers control exactly which fields are present. */
    public Response rawStudentLogin(Map<String, Object> payload) {
        return restClient.post(STUDENT_LOGIN_PATH, payload);
    }

    /** Well-formed student login using the configurable captcha value (TestConfig#getDevRecaptchaToken). */
    public Response studentLogin(String officialEmail, String password) {
        return studentLogin(officialEmail, password, TestConfig.getDevRecaptchaToken());
    }

    public Response studentLogin(String officialEmail, String password, String captcha) {
        return restClient.post(STUDENT_LOGIN_PATH, new LoginRequest(officialEmail, password, captcha));
    }

    /** Posts whatever payload map is given - lets callers control exactly which fields are present. */
    public Response rawAdminLogin(Map<String, Object> payload) {
        return restClient.post(ADMIN_LOGIN_PATH, payload);
    }

    /** Well-formed admin login (no captcha field, per verified contract). */
    public Response adminLogin(String officialEmail, String password) {
        return restClient.post(ADMIN_LOGIN_PATH, LoginRequest.forAdmin(officialEmail, password));
    }
}
