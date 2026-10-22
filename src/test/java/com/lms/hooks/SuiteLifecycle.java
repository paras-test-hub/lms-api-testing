package com.lms.hooks;

import com.lms.cleanup.CleanupManager;
import com.lms.cleanup.CleanupSummary;
import com.lms.context.ExecutionContext;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suite-level lifecycle hooks (run ONCE per suite, not per scenario).
 *
 * <p><b>{@code @BeforeAll}</b> performs only lightweight framework initialization: it resets the
 * suite-scoped {@link ExecutionContext} to a clean slate so a reused JVM (IDE re-run, forked
 * surefire reuse) never starts carrying a previous run's data. It deliberately creates NO LMS
 * entities - provisioning is the job of {@code 00_setup/EnvironmentSetup.feature}, which remains
 * the visible, reported setup workflow.
 *
 * <p><b>{@code @AfterAll}</b> is an idempotent teardown SAFETY NET, not the primary teardown.
 * {@code 99_cleanup/Cleanup.feature} owns cleanup and its reporting; it marks the context
 * {@link ExecutionContext#markCleanupCompleted() cleaned up} when done. This hook therefore does
 * nothing in the normal path (it sees the flag and exits). It only fires the SAME centralized
 * {@link CleanupManager} as an emergency fallback when Cleanup.feature did not run to completion
 * (e.g. a tag filter excluded it, or the suite was interrupted) - preventing orphaned runtime
 * entities without ever duplicating cleanup work or report entries.
 */
public final class SuiteLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SuiteLifecycle.class);

    private SuiteLifecycle() {
    }

    @BeforeAll
    public static void initSuite() {
        ExecutionContext.reset();
        log.info("=== Suite starting: ExecutionContext initialized (empty runtime environment) ===");
    }

    @AfterAll
    public static void teardownSafetyNet() {
        ExecutionContext context = ExecutionContext.get();

        if (context.isCleanupCompleted()) {
            // Normal path: Cleanup.feature already tore the environment down (and reported it).
            return;
        }

        if (context.getCreatedResources().isEmpty()) {
            // Nothing was ever provisioned this run (e.g. only endpoint tests ran) - nothing to do.
            return;
        }

        log.warn("=== @AfterAll safety net: Cleanup.feature did not run to completion; invoking "
                + "CleanupManager once as an idempotent emergency fallback to avoid orphaned entities ===");
        try {
            CleanupSummary summary = new CleanupManager().cleanup(context);
            context.markCleanupCompleted();
            if (summary.hasFailures()) {
                log.warn("Safety-net cleanup reported {} failure(s) - see the cleanup log above.",
                        summary.getFailures().size());
            }
        } catch (Throwable t) {
            // Absolute last resort - never let teardown break the JVM exit.
            log.error("Safety-net cleanup threw unexpectedly; ignoring: {}", t.toString());
        }
    }
}
