package com.lms.config;

/**
 * Global test constants and feature flags. Values can be overridden via .env
 * (see {@link EnvironmentConfig#getEnvOrDefault}).
 */
public final class TestConfig {

    private TestConfig() {
    }

    // API version prefixes - see environments_config.json notes: v1 is primary,
    // v2 hosts newer endpoints (session-feedback, lecture-lense, doubtDiscussion, admin/overview).
    public static final String API_V1 = "/api/v1";
    public static final String API_V2 = "/api/v2";

    public static int getRequestTimeoutMs() {
        return Integer.parseInt(EnvironmentConfig.getEnvOrDefault("REQUEST_TIMEOUT", "30000"));
    }

    public static int getConnectionTimeoutMs() {
        return Integer.parseInt(EnvironmentConfig.getEnvOrDefault("CONNECTION_TIMEOUT", "10000"));
    }

    /**
     * When false (default), tests pass an empty/dummy captcha value instead of minting a
     * real reCAPTCHA v3 token. Dev has been observed to presence-check only, not verify.
     */
    public static boolean isRecaptchaVerificationEnabled() {
        return Boolean.parseBoolean(EnvironmentConfig.getEnvOrDefault("ENABLE_RECAPTCHA_VERIFICATION", "false"));
    }

    /**
     * The captcha value sent on the student/admin login requests. The dev environment
     * (api-dev.mastersunion.in) only presence-checks this field on studentLogin - it does not
     * validate a real reCAPTCHA token there (see AuthenticationClient's Javadoc) - so tests use
     * a configurable placeholder instead of minting a real token. Configurable via
     * DEV_RECAPTCHA_TOKEN in .env so it can be swapped (e.g. for a real token on an environment
     * that does validate it) without any source change; defaults to the same placeholder value
     * this suite has always used.
     */
    public static String getDevRecaptchaToken() {
        return EnvironmentConfig.getEnvOrDefault("DEV_RECAPTCHA_TOKEN", "api-test-placeholder");
    }

    public static boolean isParallelExecutionEnabled() {
        return Boolean.parseBoolean(EnvironmentConfig.getEnvOrDefault("ENABLE_PARALLEL_EXECUTION", "false"));
    }

    public static String getAdminTestEmail() {
        return EnvironmentConfig.getRequired("ADMIN_TEST_EMAIL");
    }

    public static String getAdminTestPassword() {
        return EnvironmentConfig.getRequired("ADMIN_TEST_PASSWORD");
    }

    /**
     * Non-throwing presence check - lets scenarios skip gracefully (via JUnit's
     * Assumptions) instead of failing when credentials aren't configured yet, mirroring
     * lms-api-testing/core/config/env.js's hasAdminCredentials getter.
     */
    public static boolean hasAdminCredentials() {
        String email = EnvironmentConfig.getEnvOrDefault("ADMIN_TEST_EMAIL", null);
        String password = EnvironmentConfig.getEnvOrDefault("ADMIN_TEST_PASSWORD", null);
        return email != null && !email.isEmpty() && password != null && !password.isEmpty();
    }
}
