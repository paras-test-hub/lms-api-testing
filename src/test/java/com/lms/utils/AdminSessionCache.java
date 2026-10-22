package com.lms.utils;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

/**
 * File-backed cache for a single admin session (token + orgId + userId), so the CONVENIENCE
 * "the admin logs in with valid credentials" step - used by workflows that merely need an
 * authenticated admin session as a means to an end - doesn't call POST /api/v1/auth/login on
 * every single test-process invocation. That endpoint is rate-limited (~8 rapid requests
 * trips a 406 "Too many login attempts"), and iterative workflow verification runs `mvn test`
 * many times in a short window, so avoiding a redundant login per run matters here.
 *
 * This must NOT be used by Authentication.feature's own login-behavior tests - those exist
 * specifically to exercise the login endpoint itself, and caching would defeat their purpose.
 *
 * Stored under target/ (gitignored, cleared by `mvn clean`) rather than committed anywhere,
 * since it holds a live bearer token. Validity is determined by decoding the JWT's own "exp"
 * claim (via JwtUtils) rather than trusting the login response's separately-provided
 * "expires" string field, whose format is unverified.
 */
public final class AdminSessionCache {

    private static final Path CACHE_FILE = Paths.get("target", "admin-session-cache.json");
    private static final long EXPIRY_SAFETY_BUFFER_SECONDS = 120;

    private static final Gson GSON = new Gson();

    private AdminSessionCache() {
    }

    public static Optional<CachedSession> loadIfValid() {
        if (!Files.exists(CACHE_FILE)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(CACHE_FILE, StandardCharsets.UTF_8)) {
            CachedSession session = GSON.fromJson(reader, CachedSession.class);
            if (session == null || session.token == null || session.orgId == null || session.userId == null) {
                return Optional.empty();
            }
            Long expiry = JwtUtils.extractExpiryEpochSeconds(session.token);
            if (expiry == null) {
                return Optional.empty(); // can't confirm validity - force a fresh login
            }
            long now = Instant.now().getEpochSecond();
            if (expiry - EXPIRY_SAFETY_BUFFER_SECONDS <= now) {
                return Optional.empty(); // expired or expiring imminently
            }
            return Optional.of(session);
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // corrupt/unreadable cache - force a fresh login
        }
    }

    public static void save(String token, String orgId, String userId) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            CachedSession session = new CachedSession();
            session.token = token;
            session.orgId = orgId;
            session.userId = userId;
            try (Writer writer = Files.newBufferedWriter(CACHE_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(session, writer);
            }
        } catch (IOException e) {
            // Caching is a best-effort optimization - failing to persist it should never
            // fail a test that otherwise obtained a valid session.
        }
    }

    public static final class CachedSession {
        public String token;
        public String orgId;
        public String userId;
    }
}
