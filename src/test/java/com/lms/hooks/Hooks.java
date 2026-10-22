package com.lms.hooks;

import com.lms.config.EnvironmentConfig;
import com.lms.context.ScenarioContext;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-scenario lifecycle logging + capture of the Cucumber {@link Scenario} handle for reporting.
 *
 * <p>Teardown is NO LONGER per-scenario. Since the runtime environment is suite-scoped (see
 * {@link com.lms.context.ExecutionContext}), cleanup runs ONCE at the end of the suite:
 * {@code 99_cleanup/Cleanup.feature} is the visible, reported teardown, and {@link SuiteLifecycle}'s
 * {@code @AfterAll} is an idempotent safety net. Cleaning up per-scenario here would wrongly tear
 * down the shared environment right after Environment Setup, before any business journey could run.
 */
public class Hooks {

    private static final Logger log = LoggerFactory.getLogger(Hooks.class);

    private final ScenarioContext context;

    public Hooks(ScenarioContext context) {
        this.context = context;
    }

    @Before
    public void beforeScenario(Scenario scenario) {
        // Reporting only - lets non-hook classes (workflow orchestrators) call scenario.attach(...)
        // to build the per-scenario HTML-report summary. Does not affect execution or assertions.
        context.setCucumberScenario(scenario);
        log.info("=== Starting scenario: {} [{}] against env={} ===",
                scenario.getName(), scenario.getId(), EnvironmentConfig.resolveEnvName());
    }

    @After(order = 10)
    public void afterScenario(Scenario scenario) {
        if (scenario.isFailed() && context.getLastResponse() != null) {
            log.error("Scenario FAILED: {}. Last response: status={}, body={}",
                    scenario.getName(),
                    context.getLastResponse().statusCode(),
                    context.getLastResponse().asPrettyString());
        }
        log.info("=== Finished scenario: {} - status={} ===", scenario.getName(), scenario.getStatus());
    }
}
