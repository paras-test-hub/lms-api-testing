# Student API Feature Files

BDD-style test scenarios for Student API operations.

## Feature File Naming & Organization

One `.feature` file per API section (matching OpenAPI tags):

```
student/
├── Authentication.feature       # Login, logout, token refresh (Phase 3)
├── AcademicSummary.feature      # Academic data retrieval (Phase 4.1)
├── Courses.feature              # Course enrollment and details (Phase 4.2)
├── Assignments.feature          # Assignment retrieval and submission (Phase 4.3)
├── Doubts.feature               # Q&A and doubt posting (Phase 4.4)
├── Club.feature                 # Club management (Phase 4.5)
├── Event.feature                # Event management
├── Announcements.feature        # View announcements
├── Feed.feature                 # Feed and social features
├── Notes.feature                # Note management
├── AiSummary.feature            # AI-generated summaries
└── ... (other sections)
```

## Feature File Template

```gherkin
Feature: Student Academic Summary
  As a student
  I want to retrieve my academic performance data
  So that I can track my progress

  Background:
    Given the student is authenticated and logged in

  @student @smoke
  Scenario: Student retrieves academic summary
    When the student requests the academic summary
    Then the student receives a 200 response
    And the response contains GPA information
    And the response contains term-wise grades

  @student @regression
  Scenario: Student views course progress
    When the student requests enrolled courses
    Then the student receives a 200 response
    And the response contains a list of courses
    And each course contains name, progress, and grade

  @student @negative
  Scenario: Unauthenticated student cannot access academic data
    Given the student is not authenticated
    When the student requests the academic summary
    Then the student receives a 401 response
    And the response contains error message "Unauthorized"
```

## Tagging Convention

Standard tags for test classification:

- `@student` - Student role scenario
- `@admin` - Admin role scenario
- `@smoke` - Critical path / smoke tests
- `@regression` - Full regression suite
- `@negative` - Negative test case
- `@positive` - Happy path test case
- `@high-priority` - High business priority
- `@skip` - Temporarily skip scenario

**Run scenarios by tag:**
```bash
mvn test -Dgroups="@student and @smoke"
mvn test -Dgroups="@regression"
mvn test -Dgroups="@negative"
```

## Scenario Examples

### Scenario 1: Authentication
```gherkin
@student @smoke
Scenario: Student logs in successfully
  Given a student with email "student@example.com"
  And password "password123"
  When the student submits the login request
  Then the student receives a 200 response
  And the response contains a valid JWT token
  And the response contains student profile data
  And the token is stored in context for subsequent requests
```

### Scenario 2: Data Retrieval
```gherkin
@student @regression
Scenario: Student views pending assignments
  Given the student is authenticated
  When the student requests pending assignments
  Then the student receives a 200 response
  And the response contains assignment list
  And each assignment contains name, due date, and submission status
```

### Scenario 3: Negative Case
```gherkin
@student @negative
Scenario: Student cannot access without valid token
  Given the student has an invalid or expired token
  When the student requests academic summary with the invalid token
  Then the student receives a 401 response
  And the response contains error "Unauthorized"
```

## Implementation Timeline

- **Phase 3**: Authentication.feature
- **Phase 4**: Academic, Courses, Assignments, Doubts, Clubs, Events, Announcements
- **Phase 5**: Feed, Notes, AiSummary, and other student sections

Each phase adds new feature files with corresponding step implementations.
