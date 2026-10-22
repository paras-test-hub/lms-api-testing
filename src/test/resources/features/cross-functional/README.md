# Cross-Functional Feature Files

BDD-style test scenarios that involve both Student and Admin roles working together.

## Feature Organization

```
cross-functional/
├── StudentAdminWorkflows.feature     # Multi-actor workflow scenarios
├── DataLifecycle.feature             # Full data lifecycle (create → use → update → delete)
└── ... (other cross-functional scenarios)
```

## Purpose

Cross-functional scenarios validate:
1. **End-to-end workflows** - Multiple steps involving different roles
2. **State consistency** - When admin changes state, student sees reflected changes
3. **Integration points** - Admin operations trigger student-visible changes
4. **Business rules** - Complex business logic involving multiple actors

## Feature File Template

```gherkin
Feature: Student Enrollment and Grade Workflow
  As a test team
  I want to verify end-to-end workflows
  So that student and admin operations work together correctly

  @cross-functional @smoke @integration
  Scenario: Admin creates program → Student enrolls → Admin grades → Student views
    # Step 1: Admin creates a program
    Given an admin is logged in
    And the admin creates a new program "Spring 2024"
    And the program has course "Java Fundamentals"
    
    # Step 2: Student enrolls in the program
    When a student logs in
    And the student enrolls in the program "Spring 2024"
    Then the enrollment is successful
    
    # Step 3: Student submits assignment
    When the student submits an assignment with content "Hello World"
    Then the submission is recorded
    
    # Step 4: Admin views and grades the submission
    When the admin views pending submissions
    Then the admin sees the student's submission
    When the admin assigns a grade "A" to the submission
    Then the grade is recorded in database
    
    # Step 5: Student views their grade
    When the student refreshes their transcript
    Then the student sees grade "A" for the assignment
    And the grade appears in their GPA calculation
```

## Tagging Convention

Cross-functional specific tags:

- `@cross-functional` - Multi-role scenario
- `@integration` - Integration test
- `@smoke` - Critical end-to-end path
- `@data-lifecycle` - Full CRUD cycle
- `@multi-step` - Scenario with many steps
- `@state-dependent` - Later steps depend on earlier state

**Run cross-functional scenarios:**
```bash
mvn test -Dgroups="@cross-functional"
mvn test -Dgroups="@cross-functional and @smoke"
mvn test -Dgroups="@integration"
```

## Scenario Examples

### Workflow 1: Program Creation → Student Enrollment → Grading → Transcript
```gherkin
@cross-functional @smoke
Scenario: Full academic workflow
  # Admin setup
  Given admin creates program with 2 courses and 50 student capacity
  And admin sets grading scale (A=90-100, B=80-89, etc)
  
  # Student enrollment
  When student logs in
  And student enrolls in the program
  Then student appears in admin's enrollment list
  
  # Course completion
  When student completes assignments totaling 95 points
  And admin reviews and grades all submissions
  Then student's GPA is calculated
  And student's transcript shows all grades
```

### Workflow 2: Announcement → Student Notification
```gherkin
@cross-functional @integration
Scenario: Admin posts announcement visible to students
  # Admin posts
  Given admin is logged in
  When admin creates announcement "Welcome to Spring 2024"
  And admin tags audience "All Students"
  Then announcement is published
  
  # Student receives
  When students refresh their feed
  Then all students see the announcement
  And announcement appears in notification count
  And students can read and react to announcement
```

### Workflow 3: Multi-Admin Operations
```gherkin
@cross-functional @data-lifecycle
Scenario: Multiple admins manage same program
  # Admin A creates program
  Given admin_a creates program "Data Science"
  And admin_a adds courses and enrollment capacity
  
  # Admin B updates program
  When admin_b logs in
  And admin_b views the program "Data Science"
  And admin_b updates program capacity to 150
  Then the program update is reflected
  
  # Admin A sees changes
  When admin_a refreshes program list
  Then admin_a sees updated capacity of 150
  
  # Students see result
  When students check available programs
  Then students see updated capacity
  And students can enroll if under capacity
```

## Implementation Challenges

Cross-functional scenarios require:

1. **Multiple Sessions**: Maintain separate auth tokens for student and admin
2. **State Management**: Track entities created in each step (program ID, student ID, etc.)
3. **Timing**: Wait for backend to propagate state changes
4. **Cleanup**: Delete test data created during scenarios

**ScenarioContext** must handle:
```java
context.setAdminToken(adminToken);
context.setStudentToken(studentToken);
context.setCreatedProgramId(programId);  // For cleanup
context.setCreatedStudentId(studentId);
// ... later ...
context.getAdminToken();  // Reuse different token
context.getStudentToken();
```

## Implementation Timeline

- **Phase 6**: Design 3-5 key cross-functional workflows
- **Phase 6**: Implement step definitions reusing Phase 3-5 steps
- **Phase 7**: Add parameterization for data-driven cross-functional tests

These require both student and admin features to be implemented first.
