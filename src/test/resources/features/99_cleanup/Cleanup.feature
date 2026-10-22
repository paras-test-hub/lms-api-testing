Feature: Suite Cleanup (Environment Teardown)
  As the test framework
  I want to destroy every runtime entity the suite created, exactly once at the end
  So that the dev environment is left clean with no orphaned automation data

  # LIFECYCLE PHASE: this is the closing bookend of the suite's one continuous story:
  #   00_setup/EnvironmentSetup.feature  -> provisions the isolated environment (once)
  #   10_journeys/*.feature              -> business journeys consume ExecutionContext
  #   99_cleanup/Cleanup.feature (HERE)  -> tears the whole thing down (once)
  #
  # It owns no deletion logic of its own: it delegates to CleanupWorkflow -> CleanupManager, the
  # single centralized cleanup algorithm that walks ExecutionContext's suite-wide registry in
  # REVERSE creation order (children before parents) and soft-delete-verifies each removal. The
  # numeric folder prefix (99_) makes this sort LAST under Cucumber's lexical-by-URI execution
  # order. SuiteLifecycle's @AfterAll is only an idempotent safety net for the case where this
  # feature is filtered out or the suite is interrupted - it never double-cleans (guarded by
  # ExecutionContext.isCleanupCompleted()).
  #
  # Resource types without a delete handler yet (STUDENT, ENROLLMENT, SECTION_COURSE, COURSE,
  # SECTION, PROGRAM_TERM) are reported as Skipped, not Failed - so this scenario stays green
  # while COHORT and PROGRAM (the verified handlers) are actually deleted.

  @cleanup @lifecycle
  Scenario: Tear down the runtime environment provisioned for the suite
    When the runtime environment is torn down
    Then all suite-created resources are cleaned up
