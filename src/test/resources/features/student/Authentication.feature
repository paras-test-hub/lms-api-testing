Feature: Student Authentication (Endpoint Validation)
  As a student
  I want the login endpoint to validate my request and reject bad credentials
  So that only correctly-formed, correct requests can proceed

  # Endpoint verified against the live backend: POST /api/v1/auth/studentLogin
  # { officialEmail, password, captcha }. The captcha field is presence-checked only on the
  # dev environment - this suite sends a dummy placeholder value rather than minting a real
  # reCAPTCHA token (see AuthenticationClient). The backend does not leak whether an email is
  # registered: wrong password / unregistered email / malformed email all return the same
  # 401 "Incorrect email or password".
  #
  # This feature covers ONLY failure/validation paths of the login endpoint. There is no
  # positive/successful-login scenario here by design: the framework has exactly one concept
  # of "a valid student" - the one Environment Setup creates and authenticates as its final
  # step (see features/cross-functional/EnvironmentSetup.feature). A second, static-credential
  # "valid student" (formerly backed by .env's STUDENT_TEST_EMAIL/PASSWORD) has been retired;
  # see StudentCredentialIsolationTest, which now guards against it being reintroduced.

  @student @negative @smoke
  Scenario: Login fails when all required fields are missing
    Given a student login request with no fields
    When the student submits the login request
    Then the response status code is 400
    And the response error message matches "(?i)officialEmail.*required"
    And the response error message matches "(?i)password.*required"
    And the response error message matches "(?i)captcha.*required"

  @student @negative
  Scenario: Login fails when only captcha is missing
    Given a student login request with fields:
      | officialEmail | student@example.com |
      | password      | Whatever@123         |
    When the student submits the login request
    Then the response status code is 400
    And the response error message matches "(?i)captcha.*required"

  @student @negative
  Scenario: Login fails when only password is missing
    Given a student login request with fields:
      | officialEmail | student@example.com   |
      | captcha       | api-test-placeholder  |
    When the student submits the login request
    Then the response status code is 400
    And the response error message matches "(?i)password.*required"

  @student @negative
  Scenario: Login fails when only email is missing
    Given a student login request with fields:
      | password | Whatever@123          |
      | captcha  | api-test-placeholder  |
    When the student submits the login request
    Then the response status code is 400
    And the response error message matches "(?i)officialEmail.*required"

  @student @negative @smoke
  Scenario: Login fails for an incorrect password
    Given a student with email "known.student@example.com" and password "DefinitelyWrong@000"
    When the student submits the login request
    Then the response status code is 401
    And the response error message matches "(?i)incorrect email or password"

  @student @negative
  Scenario: Login fails for an unregistered email
    Given a student with email "no_such_student_9999@example.com" and password "Whatever@123"
    When the student submits the login request
    Then the response status code is 401
    And the response error message matches "(?i)incorrect email or password"

  @student @negative
  Scenario: Login fails for a malformed email (server treats it as an auth failure, not a format error)
    Given a student with email "notanemail" and password "Whatever@123"
    When the student submits the login request
    Then the response status code is 401
    And the response error message matches "(?i)incorrect email or password"
