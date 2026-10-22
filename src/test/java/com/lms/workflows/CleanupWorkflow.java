package com.lms.workflows;

import com.lms.cleanup.CleanupManager;
import com.lms.cleanup.CleanupSummary;
import com.lms.context.ExecutionContext;
import com.lms.context.ScenarioContext;
import com.lms.reporting.CleanupReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for the suite's teardown phase - the counterpart to Environment Setup. Invoked
 * once from {@code 99_cleanup/Cleanup.feature}, it tears down EVERYTHING the suite created (both
 * the Environment Setup entities and any children later business journeys registered), then marks
 * the suite {@link ExecutionContext#markCleanupCompleted() cleaned up} so the {@code @AfterAll}
 * safety net knows it need not run.
 *
 * <p>All deletion logic stays centralized in {@link CleanupManager} - this class only sequences
 * "run cleanup -> mark completed -> publish report", keeping exactly one cleanup algorithm
 * regardless of whether it is reached via this feature or the emergency @AfterAll fallback.
 */
public class CleanupWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CleanupWorkflow.class);

    private final CleanupManager cleanupManager;

    public CleanupWorkflow() {
        this.cleanupManager = new CleanupManager();
    }

    /**
     * Tears down the suite-scoped runtime environment and attaches the cleanup summary to the
     * Cleanup.feature scenario's HTML report. Best-effort and non-throwing: a cleanup failure is
     * recorded and reported but never rethrown, so the teardown scenario reflects reality without
     * a single failed deletion aborting the rest.
     */
    public CleanupSummary tearDownEnvironment(ScenarioContext scenarioContext) {
        ExecutionContext execution = ExecutionContext.get();

        CleanupSummary summary = cleanupManager.cleanup(execution);
        execution.markCleanupCompleted();

        CleanupReport.publish(scenarioContext, summary);

        if (summary.hasFailures()) {
            log.warn("Suite cleanup reported {} failure(s) - see the cleanup log and the Environment "
                    + "Cleanup report attachment.", summary.getFailures().size());
        }
        return summary;
    }
}
