# Admin API Feature Files

BDD-style test scenarios for Admin API operations.

## Feature File Naming & Organization

One `.feature` file per API section (matching OpenAPI tags):

```
admin/
├── Authentication.feature           # Login, logout (Phase 3)
├── Program.feature                  # Program management (Phase 5.1)
├── Grading.feature                  # Grade management and report cards (Phase 5.2)
├── Attendance.feature               # Attendance tracking (Phase 5.3)
├── Announcements.feature            # Create and manage announcements (Phase 5.4)
├── ReportCard.feature               # Generate and manage report cards
├── ClubManagement.feature           # Create and manage clubs (Phase 5.5)
├── EventManagement.feature          # Create and manage events
├── UserManagement.feature           # Manage student/staff accounts
├── Placement.feature                # Placement operations
├── Analytics.feature                # View analytics and dashboards
├── AdminOverview.feature            # Admin dashboard overview
└── ... (other sections)
```

## Feature File Template

```gherkin
Feature: Admin Program Management
  As an admin
  I want to create and manage programs
  So that I can organize student cohorts

  Background:
    Given the admin is authenticated and logged in

  @admin @smoke
  Scenario: Admin creates a new program
    Given a valid program creation request
    When the admin submits the program creation request
    Then the admin receives a 201 response
    And the response contains the new program ID
    And the program is stored in the database

  @admin @regression
  Scenario: Admin updates program details
    Given an existing program
    When the admin updates the program name
    Then the admin receives a 200 response
    And the program details are updated

  @admin @negative
  Scenario: Admin cannot create program with invalid data
    Given program data is missing required fields
    When the admin submits the program creation request
    Then the admin receives a 400 response
    And the response contains validation error messages
```

## Key Differences from Student Features

1. **Write Operations**: Admin features include POST, PUT, DELETE scenarios
2. **Authorization**: Admin-only operations (403 Forbidden if student attempts)
3. **Bulk Operations**: Mass updates, bulk imports
4. **State Changes**: Program status transitions, term management
5. **Cross-Entity Operations**: Creating programs affects enrollments, courses, etc.

## Tagging Convention

Standard tags for admin test classification:

- `@admin` - Admin role scenario
- `@smoke` - Critical path / smoke tests
- `@regression` - Full regression suite
- `@create` - Creation/POST scenarios
- `@update` - Update/PUT scenarios
- `@delete` - Deletion/DELETE scenarios
- `@negative` - Negative test case
- `@state-change` - Scenario that changes system state
- `@high-priority` - High business priority

**Run scenarios by tag:**
```bash
mvn test -Dgroups="@admin and @smoke"
mvn test -Dgroups="@admin and @create"
mvn test -Dgroups="@admin and @negative"
```

## Scenario Examples

### Scenario 1: Create Program
```gherkin
@admin @smoke @create
Scenario: Admin creates a new academic program
  Given the admin has valid program details:
    | name           | Engineering |
    | term           | Fall 2024   |
    | capacity       | 100         |
    | status         | active      |
  When the admin creates the program
  Then the admin receives a 201 response
  And the response contains program ID
  And the program is queryable via GET
```

### Scenario 2: Manage Grades
```gherkin
@admin @regression @update
Scenario: Admin assigns grade to student assignment
  Given a student has submitted an assignment
  And the admin has grade criteria
  When the admin assigns a grade of "A" to the submission
  Then the admin receives a 200 response
  And the grade is recorded in the database
  And the student can see the grade in their transcript
```

### Scenario 3: Negative Authorization
```gherkin
@admin @negative
Scenario: Non-admin cannot access admin endpoints
  Given a student is logged in
  When the student attempts to access program management API
  Then the student receives a 403 response
  And the response contains error "Insufficient permissions"
```

## Implementation Timeline

- **Phase 3**: Authentication.feature
- **Phase 5**: Program, Grading, Attendance, Announcements, ReportCard
- **Later**: ClubManagement, EventManagement, UserManagement, Placement, Analytics

Each phase adds new feature files with corresponding step implementations.
