package com.lms.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Produces sequential, never-reused student emails of the form
 * {@code paras.sen+<N>@venturepact.com}, starting at 10001 (paras.sen+10001, +10002, ...).
 * No timestamps, no random numbers - a monotonic counter.
 *
 * This is a deliberately small, self-contained SEAM. Today the "next number" comes from a
 * file-backed counter that survives {@code mvn clean} (it lives outside target/), so the
 * sequence keeps climbing across executions. The intended future replacement - querying the
 * backend for the highest existing {@code paras.sen+N} suffix and continuing from there - can
 * drop in behind {@link #nextEmail()} without touching any caller.
 *
 * Within a single JVM run the in-memory {@link AtomicInteger} is the source of truth, so
 * emails are guaranteed unique for the run even if the best-effort file persistence fails
 * (a failed persist only risks repeating a number on a LATER run, never within this one).
 */
public final class SequentialEmailGenerator {

    private static final String LOCAL_PART = "paras.sen";
    private static final String DOMAIN = "venturepact.com";
    private static final int START_SUFFIX = 10001;
    private static final Path SEQUENCE_FILE =
            Paths.get(".automation-state", "student-email-sequence.txt");

    private static final Object LOCK = new Object();
    /** -1 = not yet seeded from the persisted value. */
    private static final AtomicInteger COUNTER = new AtomicInteger(-1);

    private SequentialEmailGenerator() {
    }

    public static String nextEmail() {
        int suffix;
        synchronized (LOCK) {
            if (COUNTER.get() < 0) {
                COUNTER.set(readPersistedSuffixOrStart());
            }
            suffix = COUNTER.getAndIncrement();
            persistNextSuffix(suffix + 1);
        }
        return LOCAL_PART + "+" + suffix + "@" + DOMAIN;
    }

    private static int readPersistedSuffixOrStart() {
        try {
            if (Files.exists(SEQUENCE_FILE)) {
                String content = new String(Files.readAllBytes(SEQUENCE_FILE), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return Math.max(START_SUFFIX, Integer.parseInt(content));
                }
            }
        } catch (IOException | NumberFormatException e) {
            // Missing/corrupt counter must never fail a test - fall back to the base.
        }
        return START_SUFFIX;
    }

    private static void persistNextSuffix(int next) {
        try {
            Files.createDirectories(SEQUENCE_FILE.getParent());
            Files.write(SEQUENCE_FILE, String.valueOf(next).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Best-effort: persistence failure doesn't break within-run uniqueness.
        }
    }
}
