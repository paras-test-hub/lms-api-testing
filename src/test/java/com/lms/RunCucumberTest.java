package com.lms;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Entry point for `mvn test`. Discovers all .feature files under
 * src/test/resources/features and wires them to glue code in com.lms.steps / com.lms.hooks.
 *
 * No scenarios exist yet (Phase 2) - this class exists so the project compiles and
 * `mvn test` runs cleanly with "0 scenarios", ready for Phase 3's Authentication.feature.
 */
@Suite(failIfNoTests = false)
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.lms.steps,com.lms.hooks")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, html:target/cucumber-reports/index.html, json:target/cucumber.json")
public class RunCucumberTest {
}
