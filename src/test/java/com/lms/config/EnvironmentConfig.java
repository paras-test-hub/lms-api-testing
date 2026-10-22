package com.lms.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.HashMap;
import java.util.Map;

/**
 * Environment matrix - mirrors lms-api-testing/core/config/environments.js and
 * environments_config.json. Switch target with the TEST_ENV env var.
 *
 * All environments share the same api_prefix (/api/v1); a growing set of newer
 * endpoints (session-feedback, lecture-lense, doubtDiscussion, admin/overview) use
 * /api/v2 on the same base_url - callers append the version themselves via
 * {@link TestConfig#API_V1} / {@link TestConfig#API_V2}.
 */
public final class EnvironmentConfig {

    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .ignoreIfMalformed()
            .load();

    private static final Map<String, String> BASE_URLS = new HashMap<>();

    static {
        BASE_URLS.put("dev", getEnvOrDefault("API_BASE_URL_DEV", "https://api-dev.mastersunion.in"));
        BASE_URLS.put("staging", getEnvOrDefault("API_BASE_URL_STAGING", "https://api-dev.mastersunion.in"));
        BASE_URLS.put("prod", getEnvOrDefault("API_BASE_URL_PROD", "https://api.mastersunion.in"));
        BASE_URLS.put("production", BASE_URLS.get("prod"));
        BASE_URLS.put("tetr", getEnvOrDefault("API_BASE_URL_TETR", "https://tetr.mastersunion.in"));
        BASE_URLS.put("aimer", getEnvOrDefault("API_BASE_URL_AIMER", "https://aimer-api.mastersunion.in"));
        BASE_URLS.put("coachlms", getEnvOrDefault("API_BASE_URL_COACHLMS", "https://api.coachlms.org"));
        BASE_URLS.put("isbf", getEnvOrDefault("API_BASE_URL_ISBF", "https://api-isbf.mastersunion.in"));
    }

    private EnvironmentConfig() {
    }

    /**
     * Resolves TEST_ENV (env var takes precedence, falls back to .env file, then "dev").
     * Accepts "prod"/"production" interchangeably, matching the JS suite's convention.
     */
    public static String resolveEnvName() {
        String raw = getEnvOrDefault("TEST_ENV", "dev").toLowerCase().trim();
        if (raw.equals("production")) {
            return "prod";
        }
        if (raw.equals("development") || raw.equals("master")) {
            return "dev";
        }
        if (!BASE_URLS.containsKey(raw)) {
            throw new IllegalArgumentException(
                    "Unknown TEST_ENV=\"" + raw + "\". Valid values: " + BASE_URLS.keySet());
        }
        return raw;
    }

    /** Returns the API base URL (no path) for the currently resolved environment. */
    public static String getApiBaseUrl() {
        return BASE_URLS.get(resolveEnvName());
    }

    public static String getApiBaseUrl(String envKey) {
        String key = envKey.toLowerCase().trim();
        if (!BASE_URLS.containsKey(key)) {
            throw new IllegalArgumentException(
                    "Unknown environment=\"" + envKey + "\". Valid values: " + BASE_URLS.keySet());
        }
        return BASE_URLS.get(key);
    }

    public static boolean isProduction() {
        String env = resolveEnvName();
        return env.equals("prod") || env.equals("tetr") || env.equals("aimer")
                || env.equals("coachlms") || env.equals("isbf");
    }

    /** Reads a value from the process environment first, then .env, then the given default. */
    public static String getEnvOrDefault(String key, String defaultValue) {
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.isEmpty()) {
            return sysEnv;
        }
        String dotenvValue = dotenv.get(key);
        if (dotenvValue != null && !dotenvValue.isEmpty()) {
            return dotenvValue;
        }
        return defaultValue;
    }

    public static String getRequired(String key) {
        String value = getEnvOrDefault(key, null);
        if (value == null) {
            throw new IllegalStateException(
                    "Required environment variable \"" + key + "\" is not set. Check your .env file.");
        }
        return value;
    }
}
