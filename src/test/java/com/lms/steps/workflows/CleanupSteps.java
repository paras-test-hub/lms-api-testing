package com.lms.steps.workflows;

import com.lms.cleanup.CleanupSummary;
import com.lms.context.ExecutionContext;
import com.lms.context.ScenarioContext;
import com.lms.workflows.CleanupWorkflow;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the suite teardown phase ({@code 99_cleanup/Cleanup.feature}). Thin by design: all
 * logic lives in {@link CleanupWorkflow} (which delegates to the centralized CleanupManager). This
 * is the single visible, reported teardown of the suite's one continuous story; the {@code @AfterAll}
 * safety net only fires if this feature did not run.
 */
public class CleanupSteps {

    private final ScenarioContext context;
    private final CleanupWorkflow cleanupWorkflow;

    private CleanupSummary summary;

    public CleanupSteps(ScenarioContext context) {
        this.context = context;
        this.cleanupWorkflow = new CleanupWorkflow();
    }

    @When("the runtime environment is torn down")
    public void theRuntimeEnvironmentIsTornDown() {
        summary = cleanupWorkflow.tearDownEnvironment(context);
    }

    @Then("all suite-created resources are cleaned up")
    public void allSuiteCreatedResourcesAreCleanedUp() {
        assertThat(ExecutionContext.get().isCleanupCompleted())
                .as("cleanup must have run to completion")
                .isTrue();
        assertThat(summary.getFailures())
                .as("cleanup must have no hard failures (resources with no delete handler are SKIPPED, not failed): %s",
                        summary.getFailures())
                .isEmpty();
    }
}
