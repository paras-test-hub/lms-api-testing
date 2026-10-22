Feature: Admin Authentication
  As an admin
  I want to log in with my credentials
  So that I can manage the LMS platform

  # Endpoint verified against the live backend: POST /api/v1/auth/login
  # { officialEmail, password } - no captcha field at all (confirmed distinct from student
  # login). Same non-leaking 401 behavior as student login: unregistered email and wrong
  # password both return "Incorrect email or password".
  #
  # Verified quirk - field validation is asymmetric between the two fields:
  #   - officialEmail is checked for presence/format via Joi-style validation -> 400 with a
  #     specific message ("Email is required" / "Identifier must be a valid email or phone
  #     number"), and only the FIRST validation error is returned, not a combined message
  #     (unlike the student login endpoint, which lists every missing field at once).
  #   - password has NO equivalent presence check - a missing or empty-string password
  #     falls through to the auth-attempt path and comes back as a normal 401 "Incorrect
  #     email or password", exactly like a wrong password would.
  #
  # Rate-limiting: this endpoint enforces a login-attempt rate limit (observed as a 406
  # "Too many login attempts. Please try again after N seconds." once ~8+ requests hit it
  # in quick succession). Running this entire feature file repeatedly in a short window can
  # trip it - if a scenario unexpectedly gets 406 instead of its documented status, wait
  # ~30s and re-run rather than assuming a test defect.
  #
  # Trimmed to 7 scenarios (from 10) to stay under that ~8-request threshold in one run.
  # Removed as redundant (each asserted the IDENTICAL status+message as a scenario kept
  # below, so coverage is unchanged): "both email and password missing" (dominated by
  # "email is missing" - the backend only ever reports the first validation error), "empty-
  # string password" (dominated by "missing password" - same asymmetric-validation quirk,
  # same 401), and "malformed email" (subsumed by the SQL-injection scenario below, which
  # hits the identical format-validation branch with a more informative example value).

  @admin @negative @smoke
  Scenario: Login fails when email is missing
    Given an admin login request with fields:
      | password | Whatever@123 |
    When the admin submits the login request
    Then the response status code is 400
    And the response error message matches "(?i)email.*required"

  @admin @negative
  Scenario: Login with a missing password is treated as invalid credentials, not a validation error
    Given an admin login request with fields:
      | officialEmail | admin@example.com |
    When the admin submits the login request
    Then the response status code is 401
    And the response error message matches "(?i)incorrect email or password"

  @admin @negative
  Scenario: Login fails when email is an empty string
    Given an admin login request with fields:
      | officialEmail |               |
      | password      | Whatever@123  |
    When the admin submits the login request
    Then the response status code is 400

  @admin @negative @security
  Scenario: Login fails safely for an email containing SQL-injection-like characters
    # Rejected by the same format validation as a plain malformed email, above - the
    # injection-like value never reaches a query layer at all.
    Given an admin login request with fields:
      | officialEmail | ' OR '1'='1  |
      | password      | Whatever@123 |
    When the admin submits the login request
    Then the response status code is 400
    And the response error message matches "(?i)identifier must be a valid email or phone number"

  @admin @negative @smoke
  Scenario: Login fails for an unregistered email
    Given an admin with email "no_such_admin_9999@example.com" and password "Whatever@123"
    When the admin submits the login request
    Then the response status code is 401
    And the response error message matches "(?i)incorrect email or password"

  @admin @negative @requires-credentials
  Scenario: Login fails for an incorrect password
    Given an admin with valid credentials
    And the admin password is overridden to "DefinitelyWrong@000"
    When the admin submits the login request
    Then the response status code is 401
    And the response error message matches "(?i)incorrect email or password"

  @admin @positive @smoke @requires-credentials
  Scenario: Login succeeds with valid credentials
    Given an admin with valid credentials
    When the admin submits the login request
    Then the response status code is 200
    And the response contains a valid access token
    And the response contains a valid refresh token
