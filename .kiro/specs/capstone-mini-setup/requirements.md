# Requirements Document

## Introduction

Capstone Mini-Setup adds one new journey stage to the existing Java / RestAssured / Cucumber LMS API test
framework. It runs after Environment Setup, inside the SAME suite-scoped runtime Cohort and Program Term
that Environment Setup provisioned, and executes exactly ONE scenario made of six business steps:

1. Create a Capstone Outclass Section in the runtime Program Term.
2. Enroll the existing runtime Student into that Capstone Outclass Section.
3. Attach the existing runtime CourseMaster to that Capstone Outclass Section.
4. Verify the attachment and resolve the Capstone TermCourse id.
5. Enable gamification on the Capstone TermCourse.
6. Verify gamification twice, through two independent reads.

The stage creates exactly one new Section and one new SectionCourse mapping. It recreates no Program,
Cohort, Program Term, normal Section, CourseMaster or Student. Gamification is enabled on the Capstone
TermCourse only; the normal TermCourse the Assignment and Attendance journeys depend on stays untouched.

The single largest correctness risk in this feature is conflating four distinct entities that all read
like "the course": CourseMaster, TermCourse, SectionCourse mapping, and the Section itself. The Glossary
below is normative for every requirement in this document.

## Glossary

- **Program Term**: The academic term inside the runtime Cohort, provisioned once by Environment Setup and
  held as `programTermId`. Both the normal Section and the Capstone Outclass Section belong to this same
  Program Term. This stage never creates or edits a Program Term.
- **Normal Section**: The non-capstone Section provisioned by Environment Setup, held as `sectionId`. It
  carries the normal TermCourse the Assignment, Session and Attendance journeys consume. This stage never
  modifies it.
- **Capstone Outclass Section**: The single new Section this stage creates in the runtime Program Term with
  the capstone flag set. Identified by `capstoneSectionId` and named by `capstoneSectionName`. Distinct
  from the Normal Section in every respect except its parent Program Term.
- **CourseMaster**: The organization-level course definition provisioned once by Environment Setup and held
  as `courseId`. One CourseMaster can be attached to many Sections. This stage attaches the existing
  CourseMaster to the Capstone Outclass Section and creates no new CourseMaster.
- **TermCourse**: The runtime instance of a CourseMaster inside one Section of one Program Term. Each
  Section that a CourseMaster is attached to yields a SEPARATE TermCourse with its own uuid. The normal
  TermCourse is `termCourseId`; the capstone one is `capstoneTermCourseId`. Gamification is a TermCourse
  level attribute, so the two are independently configurable.
- **SectionCourse mapping**: The join record that binds one CourseMaster to one Section within a Program
  Term. It is what the section-courses read returns, and the entry matched by CourseMaster identity yields
  the TermCourse id. Registered for cleanup symmetry as `ResourceType.SECTION_COURSE`.
- **Runtime Student**: The single student provisioned and authenticated by Environment Setup, held as
  `studentUuid`. This stage enrolls that same student and creates no new student.
- **Capstone_Workflow**: The new workflow class that owns orchestration, business assertions and read-back
  verification for this stage.
- **Cohort_Client**: The existing `CohortClient`, extended with the three new operations this stage needs.
  It owns HTTP request shaping and nothing else.
- **Reference_Data_Client**: The existing `ReferenceDataClient`, used unmodified to resolve the grading
  template id at journey runtime.
- **Rest_Client**: The existing `RestClient`, the framework's only RestAssured-aware layer.
- **Execution_Context**: The existing suite-scoped `ExecutionContext` singleton holding runtime entity ids.
- **Scenario_Context**: The existing per-scenario `ScenarioContext`, whose entity accessors delegate to
  Execution_Context.
- **Cleanup_Manager**: The existing `CleanupManager`. It handles COHORT and PROGRAM only; every other
  registered type is a logged skip. This stage changes nothing inside it.
- **Capstone_Journey_Report**: The new reporting helper following the existing `*JourneyReport.checkpoint`
  and `publish` pattern.
- **Read-back verification**: A separate read request whose payload is asserted to contain the intended
  state. An HTTP 2xx status is not read-back verification.

## Requirements

### Requirement 1: Journey Stage Placement and Preconditions

**User Story:** As a QA engineer, I want the Capstone Mini-Setup stage to run after Environment Setup on the
same runtime environment, so that capstone behaviour is verified without provisioning a second environment.

#### Acceptance Criteria

1. THE Capstone_Workflow SHALL execute the Capstone Mini-Setup stage as exactly one Cucumber scenario.
2. WHEN the Capstone Mini-Setup scenario starts, THE Capstone_Workflow SHALL assert that Execution_Context
   reports the runtime environment ready before issuing any request.
3. IF Execution_Context reports the runtime environment is not ready, THEN THE Capstone_Workflow SHALL fail
   the scenario with a message naming Environment Setup as the missing prerequisite.
4. THE Capstone_Workflow SHALL read `cohortId`, `programTermId`, `courseId`, `studentUuid`, `adminUserId`,
   `adminOrgId` and the admin token from Execution_Context.
5. THE Capstone_Workflow SHALL create the Capstone Outclass Section inside the Program Term identified by
   the `programTermId` value already held in Execution_Context.
6. THE Capstone_Workflow SHALL complete the stage without requiring the runtime Program Term to report
   `isActiveTerm` as true.
7. THE Capstone Mini-Setup scenario SHALL execute the six steps in the order: create Capstone Outclass
   Section, enroll Runtime Student, attach CourseMaster, verify attachment and resolve Capstone TermCourse,
   enable gamification, verify gamification.

### Requirement 2: Create the Capstone Outclass Section

**User Story:** As a QA engineer, I want a Capstone Outclass Section created in the runtime Program Term, so
that capstone-specific behaviour has a dedicated section to act on.

#### Acceptance Criteria

1. WHEN the Capstone Mini-Setup stage runs, THE Capstone_Workflow SHALL request creation of exactly one
   Capstone Outclass Section in the runtime Program Term.
2. THE Cohort_Client SHALL send the Capstone Outclass Section creation request to
   `POST /api/v1/program/{cohortId}/termSection`.
3. THE Cohort_Client SHALL send a Capstone Outclass Section creation body containing exactly the three
   fields `programTermId`, `createdBy` and `isCapstoneSection`, with `isCapstoneSection` set to true.
4. WHEN the Capstone Outclass Section creation response is received, THE Capstone_Workflow SHALL assert the
   HTTP status equals 201.
5. WHEN the Capstone Outclass Section creation response is received, THE Capstone_Workflow SHALL assert
   `Data.isCapstoneSection` equals true.
6. WHEN the Capstone Outclass Section creation response is received, THE Capstone_Workflow SHALL capture
   `Data.uuid` as `capstoneSectionId` and `Data.name` as `capstoneSectionName`.
7. IF the captured `capstoneSectionId` is blank, THEN THE Capstone_Workflow SHALL fail the scenario with
   the response body included in the failure message.
8. THE Capstone_Workflow SHALL assert that `capstoneSectionId` differs from the `sectionId` held in
   Execution_Context.
9. THE Capstone_Workflow SHALL derive the Capstone Outclass Section name from the creation response rather
   than from any value composed by the framework.
10. THE Capstone_Workflow SHALL treat the `programTermId` and `programId` values in the Capstone Outclass
    Section creation response as numeric internal identifiers, and SHALL omit any Program Term ownership
    assertion at this step.

### Requirement 3: Enroll the Runtime Student into the Capstone Outclass Section

**User Story:** As a QA engineer, I want the existing runtime student enrolled into the Capstone Outclass
Section, so that the capstone section has a real, verifiable member.

#### Acceptance Criteria

1. THE Capstone_Workflow SHALL enroll the Runtime Student identified by the `studentUuid` held in
   Execution_Context into the Capstone Outclass Section.
2. THE Capstone_Workflow SHALL perform the enrollment through the existing Cohort_Client operations
   `getSectionStudents`, `getUnassignedUsers` and `enrollStudents` without modifying those operations.
3. THE Cohort_Client SHALL send an enrollment body of the form
   `{"studentIds":[{"id":"<studentUuid>"}],"createdBy":"<adminUuid>"}`.
4. WHEN the enrollment request completes, THE Capstone_Workflow SHALL read the Capstone Outclass Section
   roster and assert the roster contains an entry whose student uuid equals `studentUuid`.
5. IF the Capstone Outclass Section roster does not contain the Runtime Student after enrollment, THEN THE
   Capstone_Workflow SHALL fail the scenario and report the roster entry count found.
6. THE Capstone_Workflow SHALL create no additional student while enrolling into the Capstone Outclass
   Section.
7. THE Capstone_Workflow SHALL leave the Normal Section roster unchanged.

### Requirement 4: Attach the Runtime CourseMaster to the Capstone Outclass Section

**User Story:** As a QA engineer, I want the existing runtime CourseMaster attached to the Capstone Outclass
Section, so that the capstone section carries a course that can be configured.

#### Acceptance Criteria

1. THE Capstone_Workflow SHALL attach the CourseMaster identified by the `courseId` held in
   Execution_Context to the Capstone Outclass Section.
2. THE Capstone_Workflow SHALL perform the attachment through the existing Cohort_Client operation
   `attachCourseToSection` without modifying that operation.
3. WHEN the attachment body is built, THE Capstone_Workflow SHALL resolve `templateMasterId` at journey
   runtime by calling Reference_Data_Client `listGradingTemplates` with the `adminOrgId` and admin token
   from Execution_Context.
4. THE Capstone_Workflow SHALL resolve `templateMasterId` at journey runtime because the Scenario_Context
   attribute bag is scoped to a single scenario and therefore holds no value set during Environment Setup.
5. THE Capstone Mini-Setup stage SHALL leave Execution_Context free of any grading-template field.
6. THE Capstone Mini-Setup stage SHALL leave `BootstrapSteps` unchanged.
7. IF Reference_Data_Client returns no grading template uuid, THEN THE Capstone_Workflow SHALL fail the
   scenario with a message stating that no grading template is available.
8. THE Capstone_Workflow SHALL create no additional CourseMaster while attaching to the Capstone Outclass
   Section.

### Requirement 5: Verify the Attachment and Resolve the Capstone TermCourse

**User Story:** As a QA engineer, I want the capstone attachment verified by read-back and the Capstone
TermCourse id resolved from that read, so that later configuration targets the correct course instance.

#### Acceptance Criteria

1. WHEN the attachment request completes, THE Capstone_Workflow SHALL read
   `GET /api/v1/program/section/{capstoneSectionId}/sectionCourses`.
2. THE Capstone_Workflow SHALL select the SectionCourse mapping entry from `Data[]` whose
   `CourseMaster.uuid` equals the `courseId` held in Execution_Context.
3. THE Capstone_Workflow SHALL match the SectionCourse mapping entry by CourseMaster uuid and SHALL ignore
   course name when matching.
4. WHEN the matching SectionCourse mapping entry is found, THE Capstone_Workflow SHALL capture that entry's
   `uuid` as `capstoneTermCourseId`.
5. WHEN the matching SectionCourse mapping entry is found, THE Capstone_Workflow SHALL assert that entry's
   `Section.uuid` equals `capstoneSectionId`.
6. THE Capstone_Workflow SHALL assert that `capstoneTermCourseId` differs from the `termCourseId` held in
   Execution_Context.
7. IF no `Data[]` entry has a `CourseMaster.uuid` equal to `courseId`, THEN THE Capstone_Workflow SHALL fail
   the scenario and report the CourseMaster uuids that were present.
8. THE Capstone_Workflow SHALL leave the `sectionCourseId` value in Execution_Context unchanged.
9. THE Capstone_Workflow SHALL resolve the Capstone TermCourse without calling
   `BootstrapWorkflow.verifyCourseAttached`, because that method overwrites `sectionCourseId`.

### Requirement 6: Enable Gamification on the Capstone TermCourse

**User Story:** As a QA engineer, I want gamification enabled on the Capstone TermCourse only, so that the
capstone configuration path is exercised without disturbing the normal course.

#### Acceptance Criteria

1. WHEN gamification is enabled, THE Capstone_Workflow SHALL read
   `GET /api/v1/program/termCourse/{capstoneTermCourseId}` before sending any update.
2. THE Capstone_Workflow SHALL build the update body from the values in that read response, changing
   `isGamify` from false to true as the only value mutation.
3. THE Cohort_Client SHALL send the gamification update as
   `PATCH /api/v1/program/termCourse/{capstoneTermCourseId}`.
4. THE Cohort_Client SHALL send the gamification update as a complete course-edit object rather than as a
   body containing only the changed field.
5. THE Capstone_Workflow SHALL map the update body fields from the read response according to the Field
   Mapping table in this requirement.
6. THE Capstone_Workflow SHALL send `courseDomain` as an empty array.
7. THE Capstone_Workflow SHALL omit the sixteen read-back-only fields listed in the Field Mapping notes
   from the update body.
8. WHEN the gamification update response is received, THE Capstone_Workflow SHALL assert the HTTP status is
   between 200 and 299.
9. THE Capstone_Workflow SHALL send the gamification update to `capstoneTermCourseId` only, and SHALL send
   no update to the `termCourseId` held in Execution_Context.
10. THE Capstone_Workflow SHALL treat the gamification update response status as insufficient proof of
    success and SHALL rely on the verification in Requirement 7.

#### Field Mapping (update body built from the TermCourse read response)

| Update body field                                  | Source in the read response                          |
| -------------------------------------------------- | ---------------------------------------------------- |
| `departmentId`                                     | `NpsDepartment.uuid` (not the numeric `departmentId`) |
| `courseModeId`                                     | `CourseMode.uuid`                                    |
| `courseTypeId`                                     | `CourseType.uuid`                                    |
| `courseTagId`                                      | `CourseTag.uuid`                                     |
| `courseClassId`                                    | `CourseClass.uuid`                                   |
| `credits`                                          | numeric `credits`, converted to a string             |
| `subjectWeight`                                    | `subjectWeight`; a null value is sent as `""`        |
| `taIds`                                            | the TOP-LEVEL `taIds` field                          |
| `facultyIds`                                       | the TOP-LEVEL `facultyIds` field                     |
| `courseDomain`                                     | always an empty array                                |
| `isGamify`                                         | read as false, sent as true (the sole mutation)      |

Notes: the numeric `departmentId` in the read response is a different identifier space from the uuid the
update expects, so using it would bind the update to the wrong department. Sixteen fields present in the
read response are deliberately absent from the update body; the design phase enumerates them from the
captured evidence.

### Requirement 7: Verify Gamification Through Two Independent Reads

**User Story:** As a QA engineer, I want gamification confirmed by two separate reads, so that the enabled
state is proven on the Capstone TermCourse and visible through the section-courses projection.

#### Acceptance Criteria

1. WHEN the gamification update completes, THE Capstone_Workflow SHALL read
   `GET /api/v1/program/termCourse/{capstoneTermCourseId}` using the `capstoneTermCourseId` already held in
   Execution_Context.
2. THE Capstone_Workflow SHALL reuse the stored `capstoneTermCourseId` for the verification read and SHALL
   resolve that identifier no more than once per stage.
3. WHEN the TermCourse verification response is received, THE Capstone_Workflow SHALL assert `isGamify`
   equals true.
4. WHEN the TermCourse verification response is received, THE Capstone_Workflow SHALL assert the Section
   uuid equals `capstoneSectionId`.
5. WHEN the TermCourse verification response is received, THE Capstone_Workflow SHALL assert the
   CourseMaster uuid equals the `courseId` held in Execution_Context.
6. WHEN the TermCourse verification response is received, THE Capstone_Workflow SHALL assert the
   ProgramTerm uuid equals the `programTermId` held in Execution_Context.
7. WHEN the TermCourse verification assertions pass, THE Capstone_Workflow SHALL read
   `GET /api/v1/program/section/{capstoneSectionId}/sectionCourses` as a second, independent confirmation.
8. WHEN the section-courses confirmation response is received, THE Capstone_Workflow SHALL assert
   `isGamify` equals true on the entry whose `CourseMaster.uuid` equals `courseId`.
9. IF either verification read reports `isGamify` as a value other than true, THEN THE Capstone_Workflow
   SHALL fail the scenario and report which of the two reads disagreed.

### Requirement 8: Suite-Scoped Context Extensions

**User Story:** As a framework maintainer, I want the capstone ids stored as suite-scoped state, so that a
later capstone journey can reuse them without re-resolving anything.

#### Acceptance Criteria

1. THE Execution_Context SHALL expose exactly three new fields for this stage: `capstoneSectionId`,
   `capstoneSectionName` and `capstoneTermCourseId`.
2. THE Scenario_Context SHALL expose accessors for those three fields that delegate to Execution_Context.
3. THE Capstone_Workflow SHALL leave the `sectionId` value in Execution_Context unchanged.
4. THE Capstone_Workflow SHALL leave the `termCourseId` value in Execution_Context unchanged.
5. THE Capstone Mini-Setup stage SHALL add no grading-template field to Execution_Context.
6. THE Capstone Mini-Setup stage SHALL store capstone ids that were captured from response payloads during
   the current run.

### Requirement 9: Client Extensions

**User Story:** As a framework maintainer, I want the three new HTTP operations added to the existing cohort
client, so that the capstone stage adds no new HTTP layer.

#### Acceptance Criteria

1. THE Cohort_Client SHALL expose a `createTermSection` operation targeting
   `POST /api/v1/program/{cohortId}/termSection`.
2. THE Cohort_Client SHALL expose a `getSectionCourses` operation targeting
   `GET /api/v1/program/section/{sectionId}/sectionCourses`.
3. THE Cohort_Client SHALL expose an `updateTermCourse` operation targeting
   `PATCH /api/v1/program/termCourse/{termCourseId}`.
4. THE Cohort_Client SHALL retain its existing `createSection` operation unchanged, because that operation
   targets a different endpoint from `createTermSection`.
5. THE Cohort_Client SHALL issue every new request through Rest_Client.
6. THE Rest_Client SHALL remain the only component in the framework that references RestAssured types.
7. THE Cohort_Client SHALL contain no business assertion and no orchestration for the capstone stage.

### Requirement 10: Cleanup Registration

**User Story:** As a QA engineer, I want the capstone resources registered for cleanup, so that the registry
stays symmetric with Environment Setup without inventing deletion behaviour.

#### Acceptance Criteria

1. WHEN the Capstone Outclass Section is created and verified, THE Capstone_Workflow SHALL register
   `capstoneSectionId` under `ResourceType.SECTION`.
2. WHEN the SectionCourse mapping is verified, THE Capstone_Workflow SHALL register the mapping under
   `ResourceType.SECTION_COURSE`.
3. THE Capstone Mini-Setup stage SHALL leave Cleanup_Manager unchanged.
4. WHEN Cleanup_Manager encounters a registered SECTION or SECTION_COURSE resource, THE Cleanup_Manager
   SHALL record a skip and log it, which is its existing behaviour for every type other than COHORT and
   PROGRAM.
5. THE Capstone Mini-Setup stage SHALL introduce no delete endpoint for sections, section-course mappings
   or term courses.
6. THE Capstone Mini-Setup stage SHALL register capstone resources in creation order, after the resources
   Environment Setup registered.

### Requirement 11: Reporting

**User Story:** As a QA engineer reading the HTML report, I want capstone checkpoints in the existing report
style, so that the capstone stage reads as part of one continuous suite story.

#### Acceptance Criteria

1. THE Capstone_Journey_Report SHALL follow the existing `*JourneyReport.checkpoint` and `publish` pattern.
2. WHEN a capstone step's assertions have passed, THE Capstone_Journey_Report SHALL record the checkpoint
   for that step.
3. THE Capstone_Journey_Report SHALL record the checkpoints "Capstone Outclass Section Created", "Runtime
   Student Added to Capstone Section", "Runtime Course Added to Capstone Section", "Capstone Course
   Gamification Enabled" and "Capstone Mini-Setup Complete".
4. THE Capstone_Journey_Report SHALL record a checkpoint only after the corresponding assertion has passed.
5. THE Capstone_Journey_Report SHALL exclude bearer tokens, refresh tokens, passwords and Authorization
   header values from every line it emits.
6. THE Capstone_Workflow SHALL exclude bearer tokens, refresh tokens, passwords and Authorization header
   values from every log statement it emits.
7. THE Capstone_Journey_Report SHALL perform no HTTP request and no assertion.

### Requirement 12: Business-Oriented Gherkin and Layering

**User Story:** As a stakeholder reading the feature file, I want the capstone scenario written in business
language, so that the journey is understandable without reading transport details.

#### Acceptance Criteria

1. THE Capstone Mini-Setup feature file SHALL describe each step in business language.
2. THE Capstone Mini-Setup feature file SHALL exclude HTTP methods, URLs, status codes, JSON field names,
   UUID values and RestAssured terminology from its step text.
3. THE Capstone Mini-Setup step definitions SHALL delegate every request and assertion to
   Capstone_Workflow.
4. THE Capstone Mini-Setup step definitions SHALL exclude HTTP methods, URLs, JSON field names and
   RestAssured types from their own bodies.
5. THE Capstone_Workflow SHALL own the orchestration and the business assertions for the stage.
6. THE Cohort_Client SHALL own the HTTP request shaping for the stage.

### Requirement 13: Runtime Data and Isolation Guarantees

**User Story:** As a QA engineer, I want the capstone stage to be re-runnable and side-effect free on the
existing environment, so that the rest of the suite keeps passing.

#### Acceptance Criteria

1. THE Capstone Mini-Setup stage SHALL derive every identifier it sends from a response captured during the
   current run or from Execution_Context.
2. THE Capstone Mini-Setup stage SHALL contain no literal UUID value in its source.
3. THE Capstone Mini-Setup stage SHALL contain no literal date value in its source.
4. THE Capstone Mini-Setup stage SHALL create exactly one Section.
5. THE Capstone Mini-Setup stage SHALL create no Program, Cohort, Program Term, Normal Section,
   CourseMaster or Student.
6. WHEN the Capstone Mini-Setup stage changes state, THE Capstone_Workflow SHALL confirm that change with a
   read-back verification.
7. THE Capstone_Workflow SHALL treat an HTTP 2xx status as insufficient evidence of a successful state
   change.

## Out of Scope

The following are explicitly excluded from this feature and belong to separate specs:

- Capstone metrics
- Capstone submissions
- Leaderboard and leaderboard configuration
- Capstone resources
- Skip dates
- Scoring
- Student submission flows
- Student dashboard verification
- Gamification domain configuration
- Analytics
- Reports beyond the checkpoint lines in Requirement 11

## Known Uncertainties

Recorded deliberately and left unresolved at requirements stage. Each is a design-phase or
implementation-phase confirmation item, not a blocker.

1. **`courseDomain` versus `TermCourseDomains` naming.** The captured evidence shows a domain-related field
   under two different names across the read and update contracts, and both are empty in every captured
   payload. The decision for this stage is to send `courseDomain` as an empty array only. Whether the
   backend also accepts or expects `TermCourseDomains` on update, and what a non-empty value looks like, is
   unverified.
2. **Cohort deletion cascade.** Whether deleting the runtime Cohort cascades to the Capstone Outclass
   Section, the capstone enrollment, the capstone SectionCourse mapping and the Capstone TermCourse has not
   been verified. The capstone resources are registered for cleanup symmetry, and Cleanup_Manager skips
   them, so no cascade behaviour is asserted either way.
3. **Tag strings.** The suite's setup and cleanup tags appear in the feature files as `@environment-setup`
   (`00_setup/EnvironmentSetup.feature`) and `@cleanup` (`99_cleanup/Cleanup.feature`). These exact strings
   must be re-confirmed against the feature files and the runner tag expressions when the capstone feature
   file is placed and tagged, since ordering of the new stage depends on them.
