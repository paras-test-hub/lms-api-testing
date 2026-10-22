# Implementation Plan: Capstone Mini-Setup

## Overview

The work is sequenced as **vertical slices**. Each slice adds one business step end to end - client method
(if needed) → `CapstoneWorkflow` method → step definition → feature line - so that after every slice the
suite compiles, runs, and demonstrates one more piece of the capstone journey. Nothing is left orphaned:
there is never a workflow method without a step calling it, and never a step without a feature line.

Language: **Java 17** (existing framework - RestAssured 5.4, Cucumber 7.14, JUnit 5, AssertJ 3.24).

Files touched, once, in this order:

| Slice | Files |
| --- | --- |
| 1 | `context/ExecutionContext.java`, `context/ScenarioContext.java` |
| 2 | `clients/admin/CohortClient.java`, `reporting/CapstoneJourneyReport.java`, `workflows/CapstoneWorkflow.java`, `steps/workflows/CapstoneSteps.java`, `features/10_journeys/Capstone.feature` |
| 3-7 | `workflows/CapstoneWorkflow.java`, `clients/admin/CohortClient.java`, `steps/workflows/CapstoneSteps.java`, `features/10_journeys/Capstone.feature` |
| 8 | `workflows/CapstoneWorkflow.java`, `features/10_journeys/Capstone.feature` |

**Global rule for every task below: no pre-existing scenario, feature file, step class, workflow, client
method or context field may be modified to make Capstone pass.** The only edits outside new files are the
three additive `CohortClient` methods and the three additive `ExecutionContext` / `ScenarioContext` field
groups. `BootstrapWorkflow`, `BootstrapSteps`, `CleanupManager`, `ResourceType`, `RestClient`,
`ReferenceDataClient`, `CourseClient`, `RunCucumberTest`, `pom.xml`, `EnvironmentSetup.feature` and
`Cleanup.feature` stay byte-identical.

**How each slice is validated.** The stage cannot run standalone - the readiness guard fails fast without
Environment Setup, and that is intended. So every Demo means:

1. `mvn -q test-compile`
2. `mvn clean test "-Dcucumber.filter.tags=@environment-setup or @capstone or @cleanup"`
3. Before declaring a slice done, the full suite: `mvn clean test` (and `mvn clean verify` for the report)

**Regression gate on every run:** the Assignment, Session and Attendance journeys must still pass in the
same run. That is the evidence that `sectionId`, `termCourseId` and `sectionCourseId` were left intact. The
existing architecture tests, including `StudentCredentialIsolationTest`, must stay green.

## Tasks

- [x] 1. Context foundation - suite-scoped capstone state
  - [x] 1.1 Add the three capstone fields to `ExecutionContext`
    - Add `capstoneSectionId`, `capstoneSectionName`, `capstoneTermCourseId` in a new "Capstone journey
      outputs" block with standard getters/setters, placed after the existing Environment Setup outputs
    - Add the distinguishing Javadoc: `capstoneSectionId`/`Name` are the NEW capstone Section and never the
      Environment Setup `sectionId`; `capstoneTermCourseId` is the runtime CourseMaster's instance inside
      THAT section, a different entity from `termCourseId`, which the Assignment / Session / Attendance
      journeys depend on; resolved once per stage from current-run response payloads
    - Add exactly these three fields - no grading-template field, no fourth field
    - Do not touch `sectionId`, `termCourseId`, `sectionCourseId` or `requireEnvironmentReady()`
    - _Requirements: 8.1, 8.5, 8.6, 13.1_

  - [x] 1.2 Add the three delegating accessor pairs to `ScenarioContext`
    - Add a `// ===== Capstone journey (delegated -> ExecutionContext) =====` block with
      `getCapstoneSectionId`/`setCapstoneSectionId`, `getCapstoneSectionName`/`setCapstoneSectionName`,
      `getCapstoneTermCourseId`/`setCapstoneTermCourseId`
    - Each body is pure one-line delegation to `execution()`, matching every other entity accessor in the
      class - no caching, no attribute-bag storage, no validation
    - _Requirements: 8.2_

  - Demo: `mvn -q test-compile` succeeds and `mvn clean test` is unchanged from the pre-change baseline -
    the new state is additive and nothing reads it yet.

- [ ] 2. Capstone section creation slice - the section exists and shows up in the report
  - [x] 2.1 Add `createTermSection` to `CohortClient`
    - `POST /api/v1/program/{cohortId}/termSection` via `restClient.post`, copying `fields` into a new map
      and stamping `createdBy`, so the wire body is exactly `programTermId`, `isCapstoneSection`,
      `createdBy`
    - Javadoc must state it is distinct from the existing `createSection`
      (`POST .../programTerm/{ptId}/section`) - different path, kept separate, never a rename or a widening
    - Request shaping only: no assertion, no orchestration, no business logic; `Response` is the only
      RestAssured type referenced
    - Leave `createSection` and all other existing `CohortClient` methods untouched
    - _Requirements: 9.1, 9.4, 9.5, 9.6, 9.7, 2.2, 2.3_

  - [x] 2.2 Create `CapstoneJourneyReport`
    - Model on `AttendanceJourneyReport`: private constructor, `BANNER`/`CHECK` constants, static
      `checkpoint(ScenarioContext, String label, String... details)` appending via
      `appendEnvironmentSetupReportLine`, static `publish(ScenarioContext)` formatting under a
      `CAPSTONE MINI-SETUP` heading and attaching to `getCucumberScenario()` (no-op when null)
    - No HTTP call, no assertion, and no bearer token / refresh token / password / Authorization value in
      any emitted line
    - _Requirements: 11.1, 11.5, 11.7_

  - [x] 2.3 Create `CapstoneWorkflow` with the readiness guard and section creation
    - New class holding a `CohortClient` and a `ReferenceDataClient`; no mutable instance state beyond
      method locals
    - `requireRuntimeEnvironmentReady(ScenarioContext)`: call
      `context.execution().requireEnvironmentReady()` (its existing message already names
      `EnvironmentSetup.feature` as the prerequisite), then assert `cohortId`, `programTermId`, `courseId`,
      `studentUuid`, `sectionId`, `termCourseId`, `adminUserId`, `adminOrgId` and the admin token are
      non-blank. Issue no request. Make no `isActiveTerm` assertion and no term-state read
    - `createCapstoneOutclassSection(ScenarioContext)`: build `{programTermId, isCapstoneSection: true}`,
      call `createTermSection`, assert status == 201 with the body in the message, assert
      `Data.isCapstoneSection` is true, capture `Data.uuid` → `capstoneSectionId` and `Data.name` →
      `capstoneSectionName`, fail with the full body if the id is blank, assert the id differs from
      `ExecutionContext.sectionId`
    - Take the section name from the response only - compose no name locally, send no name
    - Assert nothing about `Data.programTermId`, `Data.programId` or `Data.createdBy` (numeric internal ids
      in a different identifier space)
    - Record the "Capstone Outclass Section Created" checkpoint after the assertions pass, with the
      capstone section name, capstone section id and "distinct from the runtime section"
    - Every assertion uses AssertJ with `.as(...)`; no token or credential appears in any log or message
    - Cleanup registration is deliberately deferred to task 8.1
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 2.1, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 11.2, 11.3, 11.4, 11.6, 12.5, 13.4, 13.5_

  - [x] 2.4 Create `CapstoneSteps` and `Capstone.feature` with the first two steps
    - `steps/workflows/CapstoneSteps.java`: constructor-injected `ScenarioContext` (PicoContainer), one
      `CapstoneWorkflow` instance, one method per step, each body a single delegation. No HTTP method, URL,
      status code, JSON field name or RestAssured type in the class
    - Bind `Given the runtime environment is ready for the capstone journey` and
      `When the admin creates a Capstone Outclass section`
    - `src/test/resources/features/10_journeys/Capstone.feature`: header comment block (Feature narrative
      plus a BUSINESS JOURNEY note naming the four distinct entities - CourseMaster, TermCourse,
      SectionCourse mapping, Section), tags `@journey @capstone @requires-credentials`, one scenario
      "Admin sets up a capstone section on the runtime environment" with only those two step lines for now
    - Step text carries no HTTP verb, URL, status code, JSON field name, uuid or RestAssured term
    - Add no runner change - `RunCucumberTest` discovers the feature and the glue automatically
    - _Requirements: 1.1, 1.7, 12.1, 12.2, 12.3, 12.4_

  - Demo: the capstone section is created and the "Capstone Outclass Section Created" checkpoint appears in
    the report; the Assignment, Session and Attendance journeys pass on the same run.

- [x] 3. Checkpoint - foundation and creation slice
  - Ensure all tests pass, ask the user if questions arise.
  - Confirm `mvn clean test` is green end to end, the architecture tests including
    `StudentCredentialIsolationTest` still pass, and no pre-existing scenario was modified.

- [x] 4. Student enrollment slice - the capstone section has a verified member
  - [x] 4.1 Add the enrollment and roster verification methods to `CapstoneWorkflow`
    - `assignRuntimeStudentToCapstoneSection`: replay the admin UI's three-call order through the existing,
      unmodified `getSectionStudents` → `getUnassignedUsers` → `enrollStudents`
    - Pre-state read `getSectionStudents(programTermId, capstoneSectionId)`: assert 2xx only, assert nothing
      about the pre-enrollment roster contents
    - `getUnassignedUsers(cohortId, programTermId, capstoneSectionId)`: locate the runtime student in
      `Data.Program.UserPrograms[].User` by uuid and assert presence, failing with the student uuid and
      "must be a member of the runtime Program before enrollment"
    - Implement the **weakened** unassigned guard: the student appears in the Program member list AND no
      `UserSections` entry has a `Section.uuid` equal to `capstoneSectionId`. Do NOT reuse Bootstrap's
      empty-`UserSections` check - by this point the student is already in the Normal Section, so the
      Bootstrap guard would fail on a correct environment
    - `enrollStudents(programTermId, capstoneSectionId, {studentIds:[{id: studentUuid}]}, adminUserId)` -
      the client stamps `createdBy`, producing exactly the specified body; assert 2xx
    - `verifyRuntimeStudentInCapstoneSection`: read `getSectionStudents` on the capstone section and assert
      an entry whose student uuid equals `studentUuid` exists; on failure report the roster entry count
    - Record "Runtime Student Added to Capstone Section" (student uuid, "verified via section roster
      read-back") only after that assertion passes
    - Create no student; issue no enrollment call against `sectionId` - the only enrollment target in the
      stage is `capstoneSectionId`, leaving the Normal Section roster unchanged
    - Modify none of the three reused `CohortClient` operations
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 11.2, 11.3, 11.4, 13.5_

  - [x] 4.2 Wire the enrollment steps into the step class and feature file
    - Add `When the runtime student is assigned to the Capstone section` and
      `Then the Capstone section contains the runtime student` as single-line delegations
    - Append both lines to the scenario in the order Requirement 1.7 fixes
    - _Requirements: 1.7, 12.1, 12.2, 12.3, 12.4_

  - Demo: the runtime student appears in the capstone roster read-back, with the roster checkpoint in the
    report; the Normal Section roster and the other journeys are unaffected.

- [x] 5. Course attachment slice - the runtime CourseMaster is attached to the capstone section
  - [x] 5.1 Add `addRuntimeCourseToCapstoneSection` to `CapstoneWorkflow`
    - Resolve `templateMasterId` at journey runtime: call the unmodified
      `ReferenceDataClient.listGradingTemplates(adminOrgId, adminToken)` and extract a uuid with
      `JsonSearch.findFirstValue(body, "uuid")`, the same helper `BootstrapSteps` uses
    - A null result fails the scenario with "no grading template is available for the runtime organization -
      seed at least one"
    - Runtime resolution is required because `ScenarioContext`'s attribute bag (where `BootstrapSteps` parks
      `gradingTemplateId`) is per-scenario and therefore empty here
    - Build the HAR-verified attach body with the capstone section substituted:
      `sectionArray:[{sectionId: capstoneSectionId}]`,
      `courseArray:[{courseMasterId: courseId, courseTypeId, courseTagId, courseModeId, courseClassId,
      subjectWeight:"", isSubjectWeightEnable:false}]`, `templateMasterId`; the four lookup ids come from
      `ExecutionContext` (`courseClassId` from `classTypeId`)
    - Send it through the existing, unmodified `attachCourseToSection`; assert 2xx
    - Do NOT call `BootstrapWorkflow.verifyCourseAttached`
    - Write no grading-template field to `ExecutionContext`; leave `BootstrapSteps` unchanged; create no
      CourseMaster
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 5.9, 8.5, 13.5_

  - [x] 5.2 Wire the attachment step into the step class and feature file
    - Add `When the existing runtime course is added to the Capstone section` as a single-line delegation
      and append the line to the scenario in order
    - _Requirements: 1.7, 12.1, 12.2, 12.3, 12.4_

  - Demo: the attach call succeeds against the capstone section; the other journeys still pass, proving the
    Normal Section's course wiring was untouched.

- [ ] 6. TermCourse resolution slice - the capstone TermCourse is a distinct entity
  - [x] 6.1 Add `getSectionCourses` to `CohortClient`
    - `GET /api/v1/program/section/{sectionId}/sectionCourses` via `restClient.get` with the required
      pagination query params `searchParam=""`, `pageSize="10"`, `pageNo="1"`, `toggle=""`, using the same
      string-valued query-params-map overload `getSectionStudents` and `listProgramTerms` use
    - Request shaping only; no assertion, no orchestration; `Response` is the only RestAssured type
    - _Requirements: 9.2, 9.5, 9.6, 9.7_

  - [x] 6.2 Add the mapping lookup helper and `verifyRuntimeCourseInCapstoneSection` to `CapstoneWorkflow`
    - `findSectionCourseByCourseMaster(Response, String courseId)`: private helper returning the `Data[]`
      entry whose `CourseMaster.uuid` equals `courseId`, matching on uuid only and never on course name;
      on no match, fail with the expected uuid, every `CourseMaster.uuid` present, and `Count`
    - `verifyRuntimeCourseInCapstoneSection`: call `getSectionCourses(capstoneSectionId)`, assert 2xx, match
      through the helper, capture that entry's `uuid` as `capstoneTermCourseId`, assert its `Section.uuid`
      equals `capstoneSectionId` (message naming the section-courses read)
    - Assert `capstoneTermCourseId` differs from `ExecutionContext.termCourseId`, with both ids in the
      message stating the capstone TermCourse must be a distinct instance from the normal one
    - Resolve `capstoneTermCourseId` exactly once here; later steps reuse the stored value and re-derive
      nothing
    - Leave `sectionCourseId` in `ExecutionContext` unchanged - call no setter for it, and do not call
      `BootstrapWorkflow.verifyCourseAttached` (which would overwrite it) or `getSectionDetail`
    - Record "Runtime Course Added to Capstone Section" (course name, capstone termCourse id, "matched by
      CourseMaster uuid") after the assertions pass; the SECTION_COURSE cleanup registration is deferred to
      task 8.1
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 7.2, 8.3, 8.4, 11.2, 11.3, 11.4_

  - [x] 6.3 Wire the course verification step into the step class and feature file
    - Add `Then the Capstone section contains the runtime course` as a single-line delegation and append the
      line to the scenario in order
    - _Requirements: 1.7, 12.1, 12.2, 12.3, 12.4_

  - Demo: the report shows the capstone termCourse id, and the run proves it differs from the normal
    `termCourseId` while Assignment / Session / Attendance still pass.

- [x] 7. Checkpoint - enrollment, attachment and resolution slices
  - Ensure all tests pass, ask the user if questions arise.
  - Confirm the full suite is green, `sectionCourseId` and `termCourseId` are provably intact (the other
    journeys pass), and no pre-existing scenario was modified.

- [ ] 8. Gamification slice - the 31-key course-edit PATCH
  - [x] 8.1 Add `updateTermCourse` to `CohortClient`
    - `PATCH /api/v1/program/termCourse/{termCourseId}` via `restClient.patch`, copying `fields` and
      stamping `updatedBy`, exactly as `updateProgramTerm` does; the caller supplies every other field
      verbatim
    - Request shaping only; no assertion, no orchestration; `Response` is the only RestAssured type
    - _Requirements: 9.3, 9.5, 9.6, 9.7, 6.3, 6.4_

  - [-] 8.2 Add `buildTermCourseUpdatePayload` and `enableCapstoneCourseGamification` to `CapstoneWorkflow`
    - `buildTermCourseUpdatePayload(Map termCourse, Object taIds, Object facultyIds)`: pure private
      function returning a `LinkedHashMap` with the 31 keys in the design's verified order. Transforms:
      `credits` number → String; `courseModeId`/`courseTypeId`/`courseTagId`/`courseClassId` from the
      nested `CourseMode`/`CourseType`/`CourseTag`/`CourseClass` `.uuid`; `departmentId` from
      `NpsDepartment.uuid` and never the numeric `departmentId`; `subjectWeight` null → `""`;
      `courseDomain` always an empty array; `isGamify` always `true`; `taIds` and `facultyIds` from the
      TOP-LEVEL response fields; everything else verbatim (`workexperienced` spelling preserved)
    - Omit all sixteen read-back-only fields: `isMandatory`, `isCourseReview`, `isDraft`, `displayOrder`,
      `startDate`, `endDate`, `sectionId`, `sessionFormat`, `status`, `remarks`, `moderatedDiscussion`,
      `transactionId`, `isSubjectWeightEnable`, `AttendanceStats`, `feedbackSummary`,
      `isAttendaceStatsProcessed`; also omit `uuid`, `createdBy`, `createdAt`, `updatedAt` and every nested
      association object. Send no `TermCourseDomains` key
    - Make no request, read no context and hold no state in the builder
    - `enableCapstoneCourseGamification`: read the unmodified
      `getTermCourseDetail(capstoneTermCourseId)` first, assert 2xx, build the body from
      `Data.termCourse` plus top-level `Data.taIds` / `Data.facultyIds`, send via `updateTermCourse`, assert
      status is 200-299 with the actual status and full body in the message
    - Target `capstoneTermCourseId` only; address no request in the stage to `ExecutionContext.termCourseId`,
      and make no assertion about the normal TermCourse's `isGamify`
    - Record NO checkpoint here - a 2xx is not evidence; the gamification checkpoint belongs to task 9.1
    - No literal uuid and no literal date in the source; every value sent is either from `ExecutionContext`
      or from the read this run just performed
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10, 13.1, 13.2, 13.3, 13.7_

  - [ ]* 8.3 Write the example-based unit test for `buildTermCourseUpdatePayload`
    - New JUnit 5 + AssertJ test in the same package (`com.lms.workflows`); widen the helper to
      package-private if needed for direct invocation, and note why in its Javadoc
    - Feed a fixture `Data.termCourse` map plus `taIds` / `facultyIds`, then assert: the exact key set (31
      keys minus `updatedBy`, which the client stamps), the key ORDER of the returned `LinkedHashMap`,
      `credits` is a `String`, `subjectWeight` null → `""`, `departmentId` equals the nested
      `NpsDepartment.uuid` and not the numeric `departmentId`, `courseDomain` is an empty array,
      `isGamify` is `true`, and that none of the sixteen omitted keys is present
    - No HTTP, no context, no live environment; the test must run under `mvn test` without credentials
    - _Requirements: 6.5, 6.6, 6.7_

  - [~] 8.4 Wire the gamification step into the step class and feature file
    - Add `When the admin enables gamification for the Capstone course` as a single-line delegation and
      append the line to the scenario in order
    - _Requirements: 1.7, 12.1, 12.2, 12.3, 12.4_

  - Demo: the PATCH is accepted; the unit test pins the payload contract; the normal course is provably
    untouched because the other journeys still pass.

- [ ] 9. Gamification verification slice - two independent reads
  - [~] 9.1 Add `verifyCapstoneCourseGamified` to `CapstoneWorkflow`
    - Read 1, `getTermCourseDetail(capstoneTermCourseId)` using the stored id (resolved once, in task 6.2):
      assert `Data.termCourse.isGamify` is true, `Data.termCourse.Section.uuid` equals `capstoneSectionId`,
      `Data.termCourse.Section.isCapstoneSection` is true, `Data.termCourse.CourseMaster.uuid` equals
      `courseId`, and `Data.termCourse.ProgramTerm.uuid` equals `programTermId`
    - Read 2, `getSectionCourses(capstoneSectionId)`: re-match by `CourseMaster.uuid` through the SAME
      private helper from task 6.2 and assert `isGamify` is true on that entry
    - Per-read failure wording: read 1 reports "termCourse detail read reports isGamify=<actual>", read 2
      reports "section-courses projection reports isGamify=<actual>", so the message itself names which
      read disagreed; relation-chain failures name the relation (Section / CourseMaster / ProgramTerm) with
      expected vs actual uuid
    - After both reads pass, record "Capstone Course Gamification Enabled" ("confirmed by termCourse read",
      "confirmed by section-courses read"), then "Capstone Mini-Setup Complete" (1 section + 1
      section-course created; no program / cohort / term / course / student created), then `publish`
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 11.2, 11.3, 11.4, 13.6, 13.7_

  - [~] 9.2 Wire the final verification step into the step class and feature file
    - Add `Then the Capstone course is verified as gamified` as a single-line delegation and append the line
      to the scenario
    - _Requirements: 1.7, 12.1, 12.2, 12.3, 12.4_

  - Demo: both reads agree that gamification is on, and the full five-checkpoint capstone block renders in
    the HTML report.

- [ ] 10. Final wiring and review
  - [~] 10.1 Add the two cleanup registrations in creation order
    - In `createCapstoneOutclassSection`, after the create assertions pass: register `capstoneSectionId`
      under `ResourceType.SECTION`
    - In `verifyRuntimeCourseInCapstoneSection`, after the mapping is verified: register the matched
      mapping uuid under `ResourceType.SECTION_COURSE` through the existing
      `registerCreated(ResourceType, String, Map<String,String>)` overload with deletion context
      `{"sectionId": capstoneSectionId, "courseMasterId": courseId}` - metadata only
    - Both entries must land after everything Environment Setup registered, in that creation order
    - Register no enrollment entry - exactly two registrations
    - Leave `CleanupManager`, `CleanupSummary`, `CreatedResource` and `ResourceType` unchanged; both entries
      become logged skips through existing behaviour, and no delete endpoint for sections, section-course
      mappings or term courses is introduced
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

  - [~] 10.2 Finalize the complete eight-step feature file
    - Confirm the scenario reads: readiness Given, create section, assign student, add course, contains
      student, contains course, enable gamification, verified as gamified - matching the fixed order
    - Confirm the header comment block and `@journey @capstone @requires-credentials` tags are in the house
      style used by `SessionJourney.feature`, and that no step line contains an HTTP verb, URL, status code,
      JSON field name, uuid or RestAssured term
    - Leave `EnvironmentSetup.feature`, `Cleanup.feature`, every other feature file and `RunCucumberTest`
      unchanged
    - _Requirements: 1.1, 1.7, 12.1, 12.2_

  - [~] 10.3 Verify the architectural and isolation review checklist against the finished code
    - Confirm the deliberately-untouched list is byte-identical: `BootstrapWorkflow`, `BootstrapSteps`,
      `CourseClient`, `ReferenceDataClient`, `RestClient`, `CleanupManager`, `CleanupSummary`,
      `CreatedResource`, `ResourceType`, `RunCucumberTest`, `pom.xml`, `DateUtils`, all pre-existing
      features / steps / workflows / reports (`git diff --stat` should show only the seven intended files)
    - Confirm `RestClient` is still the only class importing RestAssured beyond `Response`; run the
      architecture tests including `StudentCredentialIsolationTest`
    - Confirm `createSection` is unchanged and separate from `createTermSection`; confirm no setter for
      `sectionId`, `termCourseId` or `sectionCourseId` is called anywhere in the capstone code
    - Confirm cleanup safety: no new delete endpoint, no `CleanupManager` change, capstone types resolve to
      logged skips, registrations in creation order
    - Confirm no literal uuid, no literal date, no token / password / Authorization value in any capstone
      source file, log statement or report line; grep the capstone sources to prove it
    - Confirm every capstone assertion uses AssertJ `.as(...)`, and that lookup failures print the candidate
      uuid set rather than a bare "not found"
    - Confirm no pre-existing scenario was modified to make Capstone pass
    - _Requirements: 4.6, 5.8, 5.9, 8.3, 8.4, 9.4, 9.6, 9.7, 10.3, 11.5, 11.6, 13.2, 13.3, 13.4, 13.5_

- [~] 11. Final checkpoint - full suite and regression gate
  - Ensure all tests pass, ask the user if questions arise.
  - `mvn -q test-compile`, then
    `mvn clean test "-Dcucumber.filter.tags=@environment-setup or @capstone or @cleanup"`, then
    `mvn clean test`, then `mvn clean verify` for the HTML report.
  - Regression gate: Assignment, Session and Attendance journeys pass on the same run as Capstone; the
    architecture tests are green; the report shows all five capstone checkpoints.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster first green run. Only task 8.3 is
  optional; every other task is core implementation.
- The design has no Correctness Properties section - property-based testing was assessed and deliberately
  omitted (single fixed integration scenario against a live service, one Section allowed by Requirement
  13.4). So there are no property-test tasks. Verification is read-back assertions inside the journey plus
  the one example-based unit test in task 8.3.
- The stage has no standalone run mode: the readiness guard fails fast without Environment Setup, by design.
- The two open Known Uncertainties (`courseDomain` vs `TermCourseDomains`, and cohort deletion cascade) are
  documented in the requirements and design and are intentionally NOT implementation tasks. Nothing here
  asserts cascade behaviour, and `courseDomain: []` is sent with no `TermCourseDomains` key.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2"] },
    { "id": 1, "tasks": ["1.2", "6.1"] },
    { "id": 2, "tasks": ["2.3", "8.1"] },
    { "id": 3, "tasks": ["2.4"] },
    { "id": 4, "tasks": ["4.1"] },
    { "id": 5, "tasks": ["4.2"] },
    { "id": 6, "tasks": ["5.1"] },
    { "id": 7, "tasks": ["5.2"] },
    { "id": 8, "tasks": ["6.2"] },
    { "id": 9, "tasks": ["6.3"] },
    { "id": 10, "tasks": ["8.2"] },
    { "id": 11, "tasks": ["8.3", "8.4"] },
    { "id": 12, "tasks": ["9.1"] },
    { "id": 13, "tasks": ["9.2"] },
    { "id": 14, "tasks": ["10.1"] },
    { "id": 15, "tasks": ["10.2"] },
    { "id": 16, "tasks": ["10.3"] }
  ]
}
```
