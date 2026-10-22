# LMS API Automation Framework
# Kiro Context & Engineering Rules

> This document is the primary context and operating contract for AI-assisted
> development in this repository.
>
> Kiro must read and understand this document before modifying the framework.

---

# 1. PROJECT PURPOSE

This project is an enterprise-style API automation framework for an LMS platform.

Technology stack:

- Java
- Maven
- RestAssured
- Cucumber JVM
- Gherkin
- PicoContainer / dependency injection where already used
- Runtime execution context
- Business-workflow-driven automation
- API-first testing
- HAR-driven API discovery
- Cucumber reporting
- Runtime cleanup

The objective is NOT to automate individual APIs independently.

The objective is to automate complete BUSINESS JOURNEYS through APIs.

Example:

Admin creates assignment
    ↓
Student sees assignment
    ↓
Student submits assignment
    ↓
Admin verifies submission
    ↓
Student verifies resulting state

The framework must prove real business behavior across actors and API boundaries.

---

# 2. CORE AUTOMATION VISION

The framework follows this high-level architecture:

Gherkin
    ↓
Step Definitions
    ↓
Business Workflows
    ↓
API Clients
    ↓
RestAssured
    ↓
LMS API

Runtime state is shared through:

ExecutionContext / ScenarioContext

The execution context contains runtime-generated data such as:

- Program ID
- Cohort ID
- Program Term ID
- Section ID
- Course ID
- Term Course ID
- Student UUID
- Student numeric ID
- Admin token
- Student token
- Session UUID
- Assignment UUID
- other runtime business identifiers

The framework must NEVER depend on hardcoded runtime IDs.

---

# 3. ARCHITECTURAL LAYERS

## 3.1 Gherkin / Feature Layer

Feature files describe BUSINESS BEHAVIOR.

Example:

Given the runtime environment is ready

When the admin marks the runtime student present

Then the runtime student sees the attendance as present

Feature files should:

- describe business intent
- remain readable
- represent complete journeys
- avoid implementation details
- avoid endpoint names
- avoid raw JSON
- avoid unnecessary API-level terminology

Do not turn feature files into API documentation.

---

# 4. STEP DEFINITIONS

Step definitions translate Gherkin into Java workflow calls.

Example:

@When("the admin marks the runtime student present")
public void theAdminMarksTheRuntimeStudentPresent() {
    attendanceWorkflow.markRuntimeStudentPresent();
}

Step definitions must remain THIN.

They should NOT contain:

- HTTP request construction
- business logic
- JSON payload construction
- complex assertions
- runtime resource orchestration
- duplicated API calls

Business logic belongs in Workflows.

---

# 5. WORKFLOW LAYER

Workflows represent BUSINESS CAPABILITIES.

Examples:

- BootstrapWorkflow
- SessionWorkflow
- AttendanceWorkflow
- AssignmentWorkflow
- Student Profile workflows
- future CapstoneWorkflow
- future ConcernWorkflow

A workflow should orchestrate the required client operations to complete a business action.

Example:

AttendanceWorkflow

    prepare runtime attendance
        ↓
    call AttendanceClient
        ↓
    verify response
        ↓
    capture runtime attendance ID
        ↓
    verify admin state
        ↓
    verify student state

Workflows should remain focused.

Do not create giant workflows containing unrelated business capabilities.

---

# 6. CLIENT LAYER

Clients contain low-level API interaction.

A client should know:

- HTTP method
- endpoint
- headers
- authentication
- request payload
- API response

A client should NOT decide business workflow behavior.

Example:

AttendanceClient

    POST /program/createTempAttendance

The workflow decides:

"Mark this runtime student present."

The client decides:

"Send this HTTP request."

Do not duplicate HTTP operations across workflows when an existing client already supports the operation.

Prefer extending an existing client when the operation belongs to the same API domain.

---

# 7. RESTASSURED

RestAssured is the HTTP automation layer.

Typical structure:

given()
    request configuration
.when()
    HTTP operation
.then()
    response validation

Do not introduce arbitrary HTTP libraries when RestAssured already handles the requirement.

---

# 8. RUNTIME-FIRST DESIGN

The framework MUST create its own runtime environment.

The environment should not depend on:

- pre-existing test students
- pre-existing programs
- pre-existing cohorts
- pre-existing sections
- pre-existing courses
- hardcoded UUIDs
- manually created records

The environment is created dynamically during execution.

The framework should capture the identifiers returned by the API and pass them through ExecutionContext.

---

# 9. ENVIRONMENT SETUP

Environment Setup is the foundation of all business journeys.

Current conceptual flow:

Admin Login
    ↓
Program
    ↓
Cohort
    ↓
Current Program Term
    ↓
Section
    ↓
Course
    ↓
Attach Course to Section
    ↓
Student
    ↓
Assign Student to Section
    ↓
Student Login
    ↓
Environment Ready

Environment Setup must provide valid runtime IDs for downstream journeys.

---

# 10. PROGRAM TERM RULE

The runtime environment should use:

ONE runtime Cohort
    ↓
ONE Program Term
    ↓
CURRENT academic timeline

The Cohort should use:

numberOfTerms = 1

The purpose is to avoid unnecessary Term 2 / Term 3 creation.

The automatically-created Term 1 should be resolved dynamically from the runtime Cohort.

Do NOT:

- hardcode the Program Term UUID
- create unnecessary additional terms
- select a pre-existing term
- select the latest term blindly
- create Term 3 as a workaround
- make downstream workflows search for another term

The Program Term ID must be stored in the existing ExecutionContext.

---

# 11. CURRENT TERM REQUIREMENT

Downstream workflows such as:

- Session
- Attendance
- Assignment
- Quiz
- Announcement
- Capstone

may be gated by the LMS backend based on the academic term.

Therefore the runtime term must be valid for the current execution timeline.

The Cohort timeline should be dynamically based on the current date.

Do NOT hardcode dates such as:

2026-08-11

or any other specific execution date.

Use dynamic current-time logic where required.

---

# 12. IMPORTANT TERM DEBUGGING LESSON

A previous failure was:

HTTP 500

{
  "code": 500,
  "message": "Term has Not Started Yet"
}

The runtime ID trace showed:

Cohort
    ↓
Term 1
    ↓
Section
    ↓
TermCourse
    ↓
Session
    ↓
Attendance

The same runtime identifiers were correctly propagated.

The important lesson:

Do not immediately assume a downstream workflow is wrong.

Trace the runtime IDs end-to-end.

If the correct Term ID is propagated but the backend still rejects the term, investigate the actual term state/configuration.

Never solve an environment problem by adding special-case logic to AttendanceWorkflow.

---

# 13. SESSION WORKFLOW

SessionWorkflow owns session business behavior.

Current important requirement:

Create an Offline Session against the runtime Course.

Session names should be dynamically generated and start with:

test_

Example:

test_20260825_115301_042

Do not hardcode session UUIDs.

Session should use runtime:

- Section ID
- Course ID
- Term Course ID
- Program Term ID
- Admin ID

The session should be verified after creation.

---

# 14. SESSION MODALITY

Current Session Journey Phase 1 focuses on:

OFFLINE

The modality value for this scenario should remain:

offline

Online session scenarios will be implemented separately.

Do not mix Online and Offline behavior into the same scenario unless explicitly requested.

---

# 15. SESSION TIME

Session dates/times must be generated dynamically.

Avoid hardcoded dates/times.

When the business scenario requires a completed/past session, generate a valid past time relative to the current runtime date.

If the API requires a session conflict check, use the behavior proven by the HAR.

Do NOT invent endpoints or conflict behavior.

---

# 16. ATTENDANCE JOURNEY

Attendance is a BUSINESS JOURNEY, not just an API call.

Current intended flow:

Environment Ready
    ↓
Create completed Offline Session
    ↓
Admin verifies Session
    ↓
Student verifies Session
    ↓
Admin marks Student Present
    ↓
Admin verifies Present
    ↓
Student verifies Present
    ↓
Admin changes same record to Absent
    ↓
Verify record was updated, not duplicated
    ↓
Admin verifies Absent
    ↓
Student verifies Absent
    ↓
Admin and Student remain synchronized
    ↓
Cleanup

The same runtime Session must be reused.

Do NOT create duplicate sessions to perform attendance scenarios unless a new business scenario explicitly requires it.

---

# 17. ATTENDANCE FEEDBACK

A later Attendance scenario uses Session Feedback.

Session configuration:

askSessionFeedBack = true
feedBackAttendanceRequired = true

Attendance status:

"3" = Feedback Pending

Conceptual flow:

Create Session with Feedback enabled
    ↓
Admin marks attendance as Feedback Pending
    ↓
Student retrieves feedback questions
    ↓
Student submits feedback
    ↓
Attendance becomes reflected as Present in the relevant rollup
    ↓
Verify feedback record
    ↓
Verify attendance state

Important:

This scenario reuses the ONE session created for the journey.

Do NOT create a second session solely for feedback.

---

# 18. ASSIGNMENT JOURNEY

Assignment creation and submission logic already exists.

Do NOT recreate assignment creation logic in future Assignment-related journeys.

Reuse the existing AssignmentWorkflow and AssignmentClient behavior.

Current business coverage includes concepts such as:

- Admin creates assignment
- Student retrieves assignment
- Student submits assignment
- Draft visibility
- Publishing
- Unpublishing
- Deadline behavior
- Late submission behavior
- Assignment editing propagation
- Independent assignment submissions

When adding Assignment Grades / Feedback scenarios:

Reuse:

existing assignment creation
existing runtime student
existing submission behavior

Do not duplicate the setup.

---

# 19. ASSIGNMENT FEEDBACK BUSINESS RULE

Assignment feedback is not simply a "feedback API test."

The intended business behavior includes:

Student receives feedback
    ↓
Student is not satisfied
    ↓
Student requests additional feedback
    ↓
Admin provides/updates feedback again
    ↓
New feedback appears along with previous feedback

Previous feedback must NOT disappear.

This is a business scenario under the existing Assignment Journey.

Do not create a separate unrelated business journey merely because another feature file is convenient.

---

# 20. STUDENT PROFILE JOURNEY

Student Profile must be organized as ONE BUSINESS JOURNEY.

Preferred structure:

Student Profile Journey
    ├── Personal Details
    ├── Address Details
    ├── Education Qualification
    ├── Work Experience
    ├── Career Preferences
    ├── Family & Background
    ├── Guardian Details
    └── Accomplishments

These should be multiple SCENARIOS inside the Student Profile business journey.

Do NOT create a separate business journey for every profile section unless explicitly required.

---

# 21. PERSONAL DETAILS

Important business rules discovered during previous work:

First Name:
- cannot be changed

Last Name:
- cannot be changed

The framework should verify the relevant validation behavior.

DOB:
- GET may return ISO format
- PATCH may use UI-formatted date

Be careful with timezone/date conversion.

A previous failure occurred because:

Expected:
2006-07-30

Actual:
2006-07-29T18:30:00.000Z

This may represent the same calendar date after timezone conversion.

Do NOT blindly compare raw timestamp prefixes when the business requirement is calendar-date persistence.

Normalize dates to the intended calendar date before asserting.

Do not weaken validation. Fix the comparison to represent the actual business rule.

---

# 22. PROFILE LOOKUP DATA

HAR analysis showed that some profile dropdown values may be client-side literals rather than API lookups.

Do NOT invent lookup APIs.

Before implementing a lookup:

1. Search the HAR.
2. Search existing project clients.
3. Search frontend/API evidence if available.
4. Only implement an endpoint when evidence exists.

If no lookup API exists, document that fact instead of inventing an endpoint.

---

# 23. CONCERN JOURNEY

Concern is a separate business journey.

Current first scenario:

Technical Support
    ↓
General IT Support

Environment isolation requirement:

The admin configures the escalation flow using the ADMIN'S OWN runtime/user ID.

Do NOT assign a random existing admin.

The goal is:

Runtime Admin
    ↓
configure escalation
    ↓
Student raises concern
    ↓
Admin receives concern
    ↓
Messages exchanged
    ↓
Status transitions

Example lifecycle observed:

Open
    ↓
In Progress
    ↓
Resolved
    ↓
Escalated
    ↓
Resolved

The exact transitions must follow the HAR/business behavior.

Both admin and student sides must be verified.

---

# 24. CAPSTONE JOURNEY

Capstone is another business journey.

Environment setup for Capstone should establish reusable runtime data for future Capstone scenarios.

Conceptual flow:

Existing Runtime Cohort
    ↓
Current Runtime Term
    ↓
Create Outclass Section
    ↓
Attach Existing Runtime Course
    ↓
Add Runtime Student(s)
    ↓
Verify Section
    ↓
Enable Course Gamification
    ↓
Configure Capstone Details
    ├── Metrics
    ├── Submissions
    ├── Skip Dates
    ├── Leaderboard
    └── Resources

Do NOT create a duplicate Course if the existing runtime Course can be reused.

The current course should be updated with:

gamifying = true

The framework should verify that the course is actually configured as gamified.

---

# 25. CAPSTONE DATE RULE

When Capstone configuration requires dates:

Prefer a date in the current month and within the current week where valid.

The date should be in the PAST relative to the current runtime date when the objective is to allow students to have metrics available for completion.

Do not hardcode dates.

Calculate dynamically.

The exact date must satisfy the backend's valid date rules.

---

# 26. HAR-FIRST API DISCOVERY

HAR files are authoritative evidence for frontend network behavior.

When implementing a new journey:

1. Analyze the HAR.
2. Identify business-relevant requests.
3. Ignore UI noise.
4. Identify request method.
5. Identify endpoint.
6. Identify headers that matter.
7. Identify request payload.
8. Identify response structure.
9. Identify runtime IDs.
10. Map the operation into the existing architecture.

Do NOT blindly automate every network request captured in the HAR.

UI interaction often generates:

- initialization calls
- lookup calls
- analytics
- duplicate GET requests
- polling
- irrelevant UI state requests

Only automate requests required to prove the business journey.

---

# 27. NEVER INVENT API CONTRACTS

This is one of the strictest rules.

Never invent:

- endpoints
- payload fields
- response fields
- status codes
- UUIDs
- lookup APIs
- request parameters

If an API contract is unclear:

Search:

1. Existing clients
2. Existing workflows
3. Existing tests
4. HAR
5. Project documentation
6. API responses

If still unknown, report the uncertainty.

Do not guess.

---

# 28. RUNTIME ID RULE

Never hardcode:

- Program UUID
- Cohort UUID
- Term UUID
- Section UUID
- Course UUID
- Student UUID
- Session UUID
- Assignment UUID
- Attendance UUID

IDs must come from runtime API responses.

Store them in the existing ExecutionContext.

---

# 29. EXECUTION CONTEXT

ExecutionContext is shared runtime memory.

Conceptually:

Program
    ↓
programId

Cohort
    ↓
cohortId

Term
    ↓
programTermId

Section
    ↓
sectionId

Course
    ↓
courseId

Course/Section mapping
    ↓
termCourseId

Student
    ↓
studentUuid
studentNumericId

Authentication
    ↓
adminToken
studentToken

Session
    ↓
sessionUuid

Business entities
    ↓
assignmentUuid
attendanceUuid
etc.

Do not create parallel context objects for the same runtime entity.

Extend the existing context only when a genuinely new business entity requires state.

---

# 30. RESOURCE CLEANUP

Cleanup is a first-class part of the framework.

The intended lifecycle:

Environment Setup
    ↓
Business Journeys
    ↓
Cleanup

Cleanup must remove runtime-created resources.

Do not leave test data in the dev environment.

Do not bypass CleanupManager.

Do not add unrelated cleanup mechanisms.

If a new resource is created, determine how it should be registered with the existing cleanup system.

---

# 31. CLEANUP SAFETY

Cleanup should be:

- deterministic
- idempotent where possible
- runtime-ID driven
- executed at the end of the suite

The framework uses Cleanup.feature as the explicit teardown stage.

Do not replace the architecture with random @After methods.

Hooks may act as safety nets, but business cleanup remains explicit.

---

# 32. REPORTING

Reports should communicate BUSINESS RESULTS.

Prefer:

Environment Setup ✓
Session Created ✓
Admin Verification ✓
Student Verification ✓
Attendance Updated ✓
Cross-Actor Synchronization ✓
Cleanup ✓

Avoid reporting every low-level HTTP call as though it were a business achievement.

The framework is designed to demonstrate business behavior.

---

# 33. TEST ISOLATION

Each complete suite execution should create its own runtime data.

Example:

Run 1:
    Program A
    Cohort A
    Student A

Run 2:
    Program B
    Cohort B
    Student B

Run 3:
    Program C
    Cohort C
    Student C

No run should rely on another run's data.

---

# 34. BUSINESS JOURNEY ORGANIZATION

Organize tests by BUSINESS CAPABILITY, not by API or actor.

Preferred:

Assignment Journey
Attendance Journey
Session Journey
Student Profile Journey
Concern Journey
Capstone Journey

Not:

Admin API Tests
Student API Tests
Course API Tests
Session API Tests

Actors are part of scenarios inside journeys.

---

# 35. REUSE RULE

Before creating new setup logic:

SEARCH THE EXISTING FRAMEWORK.

If an existing workflow can create the required entity, reuse it.

Examples:

Assignment creation:
    reuse AssignmentWorkflow

Runtime Session:
    reuse SessionWorkflow

Runtime Student:
    reuse Environment Setup

Runtime Course:
    reuse Environment Setup

Do not duplicate creation logic inside another workflow.

---

# 36. ONE BUSINESS CAPABILITY = ONE WORKFLOW

A workflow should have a clear responsibility.

Examples:

SessionWorkflow
    → Session behavior

AttendanceWorkflow
    → Attendance behavior

AssignmentWorkflow
    → Assignment behavior

Avoid putting:

session creation
attendance marking
assignment creation
profile updates

all into one giant workflow.

---

# 37. WHEN FIXING FAILURES

Do NOT immediately edit the line that failed.

Follow this debugging process:

1. Read the complete stack trace.
2. Identify the first framework method involved.
3. Identify the API request.
4. Inspect the request payload.
5. Inspect runtime IDs.
6. Trace the ID back through ExecutionContext.
7. Inspect the API response.
8. Compare against HAR behavior.
9. Determine whether the failure is:
   - test data
   - environment setup
   - context propagation
   - API contract
   - assertion
   - backend behavior
   - timing/date
10. Only then modify code.

Example:

If Attendance fails with:

"Term has Not Started Yet"

Do NOT automatically modify Attendance.

Trace:

Cohort
    ↓
Program Term
    ↓
Section
    ↓
Term Course
    ↓
Session
    ↓
Attendance

Find the actual broken relationship/state first.

---

# 38. DATE/TIME RULES

Dates are a common source of false failures.

Never hardcode today's date.

Use Java date/time APIs.

Prefer:

java.time.LocalDate
java.time.LocalTime
java.time.LocalDateTime
java.time.OffsetDateTime
java.time.Instant

Choose the correct type based on the API contract.

Be careful about:

- UTC
- IST
- timezone offsets
- calendar date vs timestamp
- start/end boundaries

When a business requirement is "30 July", compare the calendar date rather than blindly comparing UTC timestamp strings.

---

# 39. API VALIDATION

Assertions should prove business requirements.

Bad:

assertThat(response.statusCode()).isEqualTo(200);

alone.

Better:

assert response succeeded
AND
entity exists
AND
runtime ID matches
AND
business fields are correct

Example:

Session creation should verify:

- response success
- session UUID exists
- session name starts with test_
- correct course
- correct section
- correct term
- correct modality
- correct runtime ownership

---

# 40. STUDENT/ADMIN CROSS-ACTOR VALIDATION

Many business journeys intentionally use both actors.

Example:

Admin changes attendance
    ↓
Student retrieves attendance
    ↓
Student sees same state

This is stronger than simply checking the POST response.

Always verify state through the appropriate actor's API where the business requirement demands it.

---

# 41. API RESPONSE VS BUSINESS STATE

A successful HTTP response does not necessarily prove the business operation succeeded.

For example:

POST returns 200

but the entity may still have:

- incorrect status
- incorrect owner
- incorrect relationship
- incorrect visibility
- incorrect date

Therefore:

HTTP success
    +
business-state verification

is the preferred pattern.

---

# 42. MAVEN

Typical commands:

Run full suite:

mvn clean test

Run selected tagged journeys:

mvn clean test "-Dcucumber.filter.tags=@environment-setup or @attendance or @cleanup"

On Windows PowerShell, quote the Cucumber property when the tag expression contains spaces/operators.

Remember:

Cucumber tag filtering determines WHICH scenarios are selected.

The framework architecture must still guarantee the required lifecycle and runtime context.

---

# 43. GIT SAFETY

Before making significant changes:

Inspect:

git status
git log
git diff

Do not commit automatically.

Do not reset, revert, or delete user work unless explicitly instructed.

Never destroy existing work to make a test pass.

---

# 44. AI DEVELOPMENT RULES

Kiro is an assistant working inside an existing framework.

It must behave like a maintainer, not like a greenfield developer.

Before modifying code:

1. Understand the repository.
2. Read relevant files.
3. Search for existing implementations.
4. Identify the smallest required change.
5. Explain the proposed change when the task is ambiguous.
6. Preserve existing architecture.

Do NOT:

- redesign the framework unnecessarily
- rename large numbers of classes
- move packages without reason
- introduce new frameworks
- replace RestAssured
- replace Cucumber
- replace Maven
- create duplicate clients
- create duplicate workflows
- create duplicate context objects
- remove cleanup
- remove runtime isolation
- hardcode IDs
- invent APIs

---

# 45. MINIMAL CHANGE PRINCIPLE

When fixing a defect:

Prefer:

ONE root cause
    ↓
ONE smallest correct fix

over:

ONE failure
    ↓
large refactor
    ↓
new architecture
    ↓
five new failures

Do not improve unrelated code during a targeted bug fix.

---

# 46. BEFORE CREATING A NEW FILE

Ask:

Does this capability already belong in an existing workflow?

Does this endpoint already belong in an existing client?

Does this state already belong in ExecutionContext?

Does an existing feature already represent this business journey?

Can the existing scenario be extended?

Only create a new file when the architectural responsibility genuinely requires it.

---

# 47. BEFORE MODIFYING AN EXISTING FILE

Identify:

- who calls it
- what it calls
- what data it receives
- what data it returns
- what other workflows depend on it
- what tests depend on it

Do not change a public method signature casually.

---

# 48. HAR ANALYSIS RULE

When given a HAR:

Separate:

REAL BUSINESS OPERATION

from:

UI SUPPORTING TRAFFIC

Example:

Useful:

POST create session

Potentially useful:

GET session list

Potentially irrelevant:

GET dropdown initialization

GET analytics

Repeated polling

UI configuration calls

The objective is to reproduce the business operation, not the browser's every network request.

---

# 49. EXISTING WORK VS NEW WORK

Existing completed/partially completed areas include:

- Environment Setup
- Authentication
- Session Journey
- Attendance Journey
- Assignment Journey
- Student Profile areas
- Concern Journey
- Capstone environment preparation

When adding new scenarios:

EXTEND existing business journeys when the capability belongs to them.

Do not create unnecessary new journeys.

---

# 50. CURRENT DEVELOPMENT DIRECTION

The project is being developed manually with AI assistance.

The developer understands QA/business behavior strongly and is intentionally deepening Java/API automation/framework engineering knowledge.

Therefore explanations should:

- explain WHY
- explain WHAT
- explain WHERE
- explain HOW
- connect implementation back to the framework architecture

Do not assume advanced Java knowledge.

When making a significant change, explain the relevant Java/framework concept briefly.

---

# 51. TEACHING MODE

When the developer asks "why" or "how":

Do not simply provide a patch.

Explain:

1. What happened
2. Why it happened
3. Which layer owns the problem
4. Why the proposed solution belongs there
5. What the code is doing
6. What could go wrong with an alternative solution

The objective is not merely to make tests pass.

The objective is to build maintainable automation engineering knowledge.

---

# 52. GOLDEN RULES

These rules override convenience:

1. Business journeys over individual APIs.
2. Runtime data over hardcoded data.
3. Existing architecture over new architecture.
4. HAR evidence over API guessing.
5. Business-state verification over HTTP status alone.
6. Shared ExecutionContext over duplicated state.
7. Reuse workflows over duplicated setup.
8. Reuse clients over duplicated HTTP calls.
9. Explicit cleanup over leftover test data.
10. Small targeted fixes over broad refactors.
11. Current runtime data over hardcoded dates.
12. Cross-actor verification where the business flow requires it.
13. Never invent API contracts.
14. Never hardcode runtime UUIDs.
15. Never modify unrelated working journeys to solve a local problem.
16. Never create a second business journey when the scenario belongs inside an existing one.
17. Never commit changes automatically.
18. Investigate before editing.

---

# 53. TARGET ARCHITECTURE

The intended framework should remain conceptually:

                         GHERKIN
                            │
                            ▼
                    STEP DEFINITIONS
                            │
                            ▼
                       WORKFLOWS
                            │
                            ▼
                         CLIENTS
                            │
                            ▼
                       RESTASSURED
                            │
                            ▼
                        LMS API


                    EXECUTION CONTEXT
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
       Session        Attendance       Assignment
       Workflow        Workflow         Workflow
           │               │               │
           └───────────────┼───────────────┘
                           ▼
                        CLEANUP


Environment Setup provides the runtime foundation.

Business Journeys consume that foundation.

Cleanup removes everything created by the suite.

This architecture must be preserved.

---

# 54. DEFINITION OF SUCCESS

A successful automation framework is NOT:

"All API calls returned 200."

A successful framework is:

"Every business journey can run against isolated runtime data,
verify the expected business state from the correct actor,
reuse runtime entities safely,
and leave the environment clean afterward."

That is the standard this project should maintain.