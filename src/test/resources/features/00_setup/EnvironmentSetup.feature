Feature: Environment Setup for Isolated LMS Testing
  As a test framework
  I want to provision a complete, isolated LMS academic environment via the API
  So that every business workflow can run against fresh data with no dependency on pre-existing
    students, programs, cohorts, courses, or enrollments

  # ARCHITECTURE: this is no longer an endpoint-driven or CRUD-driven suite. The framework is
  # organized around a complete isolated academic environment:
  #
  #   Suite Start -> Environment Setup -> Environment Ready -> Business Workflow 1
  #     -> Business Workflow 2 -> ... -> Business Workflow N -> Environment Cleanup -> Suite End
  #
  # Environment Setup (this feature) builds a fresh academic institution ONCE per scenario:
  # Program -> Cohort -> Program Term -> Section -> Course -> Attach Course to Section
  # -> Student -> Assign Student to Section. The only permanent account is the Automation Admin
  # used to log in; every academic entity above is created fresh here and registered in
  # ScenarioContext's cleanup registry for CleanupManager to tear down (Environment Cleanup).
  #
  # Business Workflows (Assignment, Attendance, Quiz, Announcement, Grading, Event,
  # Certificate, Placement, ...) run AFTER this environment exists and must NEVER create their
  # own Program/Cohort/Program Term/Section/Course/Student - they consume the ids this feature
  # already produced (via ScenarioContext) and create only business-specific entities.
  #
  # Grown incrementally: each step below was verified against the live dev backend before being
  # added (see BootstrapSteps.java's Javadoc - TODO: rename to EnvironmentSetupSteps/Workflow in
  # a dedicated refactoring pass; the class/file names still say "Bootstrap" internally, but the
  # responsibility described here is Environment Setup, not a business workflow or a test).
  # Environment Setup chain (COMPLETE - includes student authentication as its final step):
  #   Admin login -> Create Program -> Create Cohort -> Create Student -> Program Term
  #   -> Section -> Create Course (POST + 2 mandatory PATCHes, verified via GET)
  #   -> Attach Course to Section (POST sectionCourse, verified via section-detail GET)
  #   -> Assign Student to Section (POST enrollStudents, verified via getSectionStudents GET)
  #   -> Authenticate Student (POST studentLogin -> store access/refresh token + uuid in context)
  # (Student creation only needs programId + cohortId, so it runs right after cohort; the
  # term/section/course chain - and the enrollment step - depend on it existing already.)
  #
  # Student authentication is the LAST Environment Setup step, not a business workflow: the
  # environment is "Ready" only once a brand-new isolated student has been created, assigned, AND
  # logged in with its session stored in ScenarioContext. The framework has exactly ONE concept of
  # "a valid student" - this one. The login here uses the dynamically-created student's own
  # credentials (StudentLoginWorkflow reads context.getStudentEmail()/getStudentPassword()). A
  # second, static/.env-sourced student credential pair (STUDENT_TEST_EMAIL/PASSWORD) used to exist
  # for Authentication.feature's own endpoint tests and has been fully retired - see
  # StudentCredentialIsolationTest, which now guards against it being reintroduced.
  #
  # Business Workflows (Assignment, Attendance, Quiz, Announcement, Grading, Event, Certificate,
  # Placement, ...) run AFTER "Environment Ready" and consume the stored session
  # (context.getStudentToken()) - none implemented yet. When added, each gets its own dedicated
  # orchestrator + step classes and is appended below in this SAME scenario (Cucumber has no
  # cross-scenario state, so "consumes an already-completed Environment Setup" is expressed as
  # later steps in one chain). Then, finally, Environment Cleanup.

  @environment-setup @cross-functional @requires-credentials
  Scenario: Provision an isolated LMS environment with an authenticated student session
    Given the admin logs in with valid credentials
    And a valid category id is available for bootstrap
    And a program is created for the isolated environment
    Then the isolated environment has a program

    Given a valid grading template id is available for bootstrap
    And a valid course type id is available for bootstrap
    And a cohort is created under the program for the isolated environment
    Then the isolated environment has a cohort

    Given a student is created in the program and cohort for the isolated environment
    Then the isolated environment has a student

    Given a program term is created under the cohort for the isolated environment
    Then the isolated environment has a program term

    Given a section is created under the program term for the isolated environment
    Then the isolated environment has a section

    Given a course is created and configured for the isolated environment
    Then the isolated environment has a course

    Given the course is attached to the section for the isolated environment
    Then the isolated environment has the course attached to the section

    Given the student is assigned to the section for the isolated environment
    Then the isolated environment has the student assigned to the section

    # Final Environment Setup step: authenticate the created student and store the session.
    Given the student logs in for the isolated environment
    Then the isolated environment has an authenticated student session

    # ==== ENVIRONMENT READY (student created, assigned, and authenticated - session in context) ====
    # Business Workflows would begin here, consuming context.getStudentToken(). None implemented yet.
