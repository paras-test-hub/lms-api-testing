# Requirements Document

## Introduction

This feature adds ONE new Cucumber business-journey scenario - referred to throughout as **S3** - to the existing LMS API test-automation framework (Java + RestAssured + Cucumber). The deliverable is automated test coverage, not product code: S3 exercises the live LMS API to prove that an admin can create a Capstone team containing the runtime student, and that the created team is visible with correct membership from BOTH the admin side and the student side while carrying the Capstone gamification configuration established by S2.

S3 is appended to the existing `src/test/resources/features/10_journeys/Capstone.feature`, after the S2 configuration scenario, and carries the tags `@journey @capstone @team-management @requires-credentials` to match S1 and S2.

S3 CONSUMES suite-scoped runtime state produced by earlier stages and creates exactly ONE new entity: the Capstone team (`sectionGroup`). It reuses the Capstone Section and Capstone TermCourse from S1, the Capstone GamificationConfig from S2, and the runtime student and admin sessions from Environment Setup.

The API contract for S3 is derived exclusively from `references/Admin-team-creation.har`. Three calls are in scope:

| # | Actor token | Call |
|---|-------------|------|
| 1 | Admin | `POST /api/v1/program/addStudentsInGroup` -> 201 |
| 2 | Admin | `GET /api/v1/program/termCourse/{capstoneTermCourseId}/section/{capstoneSectionId}/getAllSectionGroups?searchParam=&pageSize=10000&pageNo=1` -> 200 |
| 3 | Student | `GET /api/v1/program/capstoneGamifications/term/{programTermId}?courseId={capstoneTermCourseId}&sectionId={capstoneSectionId}` -> 200 |

Metric/coin submission, team deletion, pending group requests, the capstone eligibility roster, student self-formation invitations and all browser telemetry captured in the same HAR are explicitly out of scope.

A pre-existing compilation blocker in `TestDataFactory` (three missing `capstoneResource*` helpers referenced by `CapstoneWorkflow` and `CapstoneWorkflowPayloadTest`) is in scope as a prerequisite, because S3 cannot be compiled or executed while the blocker stands.

## Glossary

The LMS API uses several different names for the same concepts, and conflating them is the single largest correctness risk in this feature. The following terms are used with exactly these meanings throughout this document.

- **CourseMaster**: the organization-level course definition, provisioned once by Environment Setup and reused as-is. S3 creates no CourseMaster.
- **TermCourse**: a CourseMaster's runtime instance inside ONE section of one Program Term. The Capstone Section has its OWN TermCourse (`capstoneTermCourseId`), a different entity from the runtime TermCourse (`termCourseId`) consumed by the Assignment, Session and Attendance journeys.
- **SectionCourse mapping**: the join record binding one CourseMaster to one Section within a term. Created by S1; read-only for S3.
- **Section**: a section within a Program Term. S3 addresses only the Capstone Outclass Section (`capstoneSectionId`) created by S1, never the runtime Section (`sectionId`) created by Environment Setup.
- **GamificationConfig**: the aggregate Capstone gamification configuration created by S2 against the Capstone TermCourse. Identified by BOTH a uuid (`capstoneGamificationConfigUuid`) and a distinct NUMERIC id (`capstoneGamificationConfigNumericId`); the two identifiers are not interchangeable.
- **Capstone_Team**: the team entity created by S3. The API names the SAME entity three ways: `sectionGroup` (create request array), `userGroup` (student-side read projection, and the flat member-uuid array in the create request), and an element of `Data[]` in the admin-side `getAllSectionGroups` read. In this document "Capstone_Team" always means this entity, and its identity is its `uuid`.
- **Team_Member_Entry**: one membership record inside a Capstone_Team, exposed as `UserGroups[j]` on both the admin-side and student-side reads, carrying the member identity at `UserGroups[j].User.uuid`.
- **Group_Representative**: the member designated as the team's representative, sent at creation as `groupRepresentative: {value, label}` and read back as `isGroupRepresentative` on a Team_Member_Entry.
- **Runtime_Student**: the single student identity created and authenticated by Environment Setup, held in `ExecutionContext` as `studentUuid` with a live session token `studentToken`.
- **Admin_Session**: the admin identity and token held as `adminUserId` and `adminToken`.
- **Execution_Context**: the suite-scoped singleton (`com.lms.context.ExecutionContext`) holding cross-scenario state. `ScenarioContext` delegates entity getters and setters to it.
- **Scenario_Context_Attributes**: the per-scenario `HashMap` on `ScenarioContext` that resets between scenarios and is NOT delegated to Execution_Context.
- **Capstone_Team_Workflow**: the workflow-layer component owning every S3 HTTP call, assertion and report checkpoint.
- **Capstone_Team_Steps**: the step-definition bindings for the S3 Gherkin steps.
- **Capstone_Client**: the existing admin client `com.lms.clients.admin.CapstoneClient`, which performs request shaping only.
- **Student_Capstone_Client**: the new student-token client `com.lms.clients.student.StudentCapstoneClient`.
- **Test_Data_Factory**: the existing `com.lms.utils.TestDataFactory` test-data helper.
- **Capstone_Journey_Report**: the existing `CapstoneJourneyReport` reporting component exposing `checkpoint` and `publish`.
- **Configuration_Exclusion_List**: the named set of exactly 6 GamificationConfig fields that the HAR proves DIFFER between the team object and the configuration object, and which therefore must be excluded from equality comparison: `leaderboardType`, `triggerInterval`, `triggerDay`, `allowSkipUntil`, `analyticsDisplay`, `dashboardDisplay`.
- **Metric_Definition**: one entry of a `metrics` array, identified by its `metric` name field and carrying the fields `target`, `type`, `showDashboard` and `currency`.
- **Authoritative_Read**: a fresh HTTP GET issued during S3 whose response is the only accepted evidence of persisted state.

## Requirements

### Requirement 1: Team Formation Readiness Guard

**User Story:** As a QA engineer, I want S3 to fail immediately and descriptively when the Capstone state it consumes is missing, so that a misconfigured run reports a missing precondition instead of a misleading API failure.

#### Acceptance Criteria

1. WHEN the step `Given the Capstone course is configured and ready for team formation` executes, THE Capstone_Team_Workflow SHALL verify that Execution_Context holds a non-empty value for each of `capstoneSectionId`, `capstoneTermCourseId`, `capstoneGamificationConfigUuid`, `programTermId`, `studentUuid`, `studentToken`, `adminUserId` and `adminToken`, where non-empty means non-null and containing at least one non-whitespace character.
2. WHEN the readiness guard evaluates `capstoneGamificationConfigNumericId`, THE Capstone_Team_Workflow SHALL verify that the value is non-null and greater than zero.
3. IF one or more values listed in criteria 1 and 2 are absent, empty or out of bounds, THEN THE Capstone_Team_Workflow SHALL fail the step with one message listing every such value, each mapped to the earlier stage that produces the value (Environment Setup, S1 or S2).
4. IF `capstoneTermCourseId` equals `termCourseId`, OR `capstoneSectionId` equals `sectionId`, THEN THE Capstone_Team_Workflow SHALL fail the step with a message identifying the collision as the Capstone identity having converged on the runtime identity that the Assignment, Session and Attendance journeys depend on.
5. WHEN the readiness guard executes, THE Capstone_Team_Workflow SHALL complete the guard using Execution_Context state only, issuing zero HTTP requests and performing zero writes to Execution_Context and zero writes to Scenario_Context_Attributes.
6. IF the readiness guard fails for any reason, THEN THE Capstone_Team_Workflow SHALL leave the environment as found, performing zero provisioning of Section, TermCourse or GamificationConfig.
7. WHEN the readiness guard passes, THE Capstone_Team_Workflow SHALL record exactly one Capstone_Journey_Report checkpoint naming the consumed `capstoneSectionId`, `capstoneTermCourseId`, `capstoneGamificationConfigNumericId`, `programTermId` and `studentUuid` values.

### Requirement 2: Capstone Team Creation

**User Story:** As an admin, I want a Capstone team created that contains the runtime student, so that team-scoped Capstone behaviour can be verified against the existing runtime environment.

#### Acceptance Criteria

1. WHEN the step `When the admin creates a Capstone team with the runtime student` executes, THE Capstone_Team_Workflow SHALL send exactly one `POST /api/v1/program/addStudentsInGroup` request using the Admin_Session token, SHALL issue zero retries of that request, and SHALL rely on the framework's existing configured request timeout without introducing a scenario-specific override.
2. WHEN the create request body is built, THE Capstone_Team_Workflow SHALL emit exactly the five top-level keys `sectionId`, `termCourseId`, `createdBy`, `sectionGroup`, `allowTeamFormation` in that order, and SHALL emit zero additional top-level keys.
3. WHEN the create request body is built, THE Capstone_Team_Workflow SHALL emit `sectionGroup` as a JSON array containing exactly one element, and SHALL emit exactly the six keys of `sectionGroup[0]` in the order `name`, `description`, `image`, `size`, `userGroup`, `groupRepresentative`, with zero additional keys.
4. WHEN the create request body is built, THE Capstone_Team_Workflow SHALL set `sectionId` to `capstoneSectionId`, `termCourseId` to `capstoneTermCourseId` and `createdBy` to `adminUserId`, each read from Execution_Context as a non-empty String, and SHALL set `name` from the Test_Data_Factory `capstoneTeamName` helper and `description` from the Test_Data_Factory `capstoneTeamDescription` helper, each a non-empty String that is stable within a single suite run.
5. WHEN the create request body is built, THE Capstone_Team_Workflow SHALL serialise `size` as the JSON String `"1"`, `image` as the empty JSON String `""`, `allowTeamFormation` as the JSON boolean `false`, `userGroup` as a flat JSON array of Strings containing exactly one element equal to the Runtime_Student uuid, and `groupRepresentative` as a JSON object carrying exactly the two keys `value` and `label` in that order, with `value` set to the Runtime_Student uuid and `label` set to a non-empty display String supplied by Test_Data_Factory.
6. WHEN the create response is received, THE Capstone_Team_Workflow SHALL assert an HTTP status of 201, assert that `Data` is an array containing exactly one element, and assert that `Data[0]` carries a non-empty `uuid`, a `name` equal to the `name` sent in the create request, and a `gamificationConfigId` that is a JSON number greater than zero.
7. WHEN the create response `Data[0].gamificationConfigId` is read, THE Capstone_Team_Workflow SHALL assert equality with the numeric `capstoneGamificationConfigNumericId` from Execution_Context, comparing the two values as numbers.
8. WHEN a 201 create response is received, THE Capstone_Team_Workflow SHALL treat the accepted request as insufficient evidence of persistence and SHALL defer the persistence verdict to the Authoritative_Read defined in Requirement 4.
9. IF the create response status is any value other than 201, THEN THE Capstone_Team_Workflow SHALL fail the step with a message naming the endpoint, the observed status and the response body, SHALL leave `capstoneTeamUuid` and `capstoneTeamName` unset in Execution_Context, SHALL issue zero retry or compensating requests, and SHALL register zero resources for cleanup.
10. IF the create response carries a 201 status but `Data` is absent, is an empty array, or `Data[0].uuid` is absent or empty, THEN THE Capstone_Team_Workflow SHALL fail the step with a message naming the absent or empty field and reporting the observed `Data` shape, and SHALL leave `capstoneTeamUuid` and `capstoneTeamName` unset in Execution_Context.
11. IF `Data[0].gamificationConfigId` differs from `capstoneGamificationConfigNumericId`, THEN THE Capstone_Team_Workflow SHALL fail the step with a message reporting both numeric values and identifying the created team as bound to a GamificationConfig other than the one created by S2.

### Requirement 3: Single-Resolution Team Identity in Suite State

**User Story:** As a QA engineer, I want the created team's identity resolved exactly once and shared, so that every later verification step asserts against the same team rather than re-deriving a possibly different one.

#### Acceptance Criteria

1. WHEN the create response is asserted successfully, THE Capstone_Team_Workflow SHALL store `Data[0].uuid` in Execution_Context as `capstoneTeamUuid` and `Data[0].name` as `capstoneTeamName`.
2. WHERE a later S3 step requires the team identity, THE Capstone_Team_Workflow SHALL read `capstoneTeamUuid` and `capstoneTeamName` from Execution_Context.
3. WHEN a later S3 step requires the team identity, THE Capstone_Team_Workflow SHALL resolve that identity from Execution_Context only, performing zero re-derivation from a list read, a name search or an array position.
4. WHEN `capstoneTeamUuid` and `capstoneTeamName` are declared on Execution_Context, THE Execution_Context SHALL expose each value through a getter and a setter matching the existing Capstone field accessor convention, and `ScenarioContext` SHALL delegate each accessor to Execution_Context.
5. IF a later S3 step reads `capstoneTeamUuid` and finds an absent or empty value, THEN THE Capstone_Team_Workflow SHALL fail that step with a message naming the creation step that produces the value.
6. IF `capstoneTeamUuid` already holds a value when the creation step begins, THEN THE Capstone_Team_Workflow SHALL overwrite `capstoneTeamUuid` and `capstoneTeamName` with the newly created team's `uuid` and `name` rather than reusing the stale values, so that a re-run within one suite verifies the newly created team rather than a previous team.

### Requirement 4: Admin-Side Membership Verification

**User Story:** As an admin, I want to see the runtime student as a member of the created team through the admin team listing, so that admin-visible membership is proven by a read rather than by the create response.

#### Acceptance Criteria

1. WHEN the step `Then the Capstone team should be created successfully` executes, THE Capstone_Team_Workflow SHALL send one `GET /api/v1/program/termCourse/{capstoneTermCourseId}/section/{capstoneSectionId}/getAllSectionGroups` request using the Admin_Session token with the query parameters `searchParam` empty, `pageSize` 10000 and `pageNo` 1, and SHALL assert an HTTP status of 200.
2. WHEN the admin-side read response is received, THE Capstone_Team_Workflow SHALL locate the team entry in `Data` by `uuid` equality against `capstoneTeamUuid`, and SHALL derive that match from zero use of `name`, array position or `Count`.
3. IF no entry of `Data` carries a `uuid` equal to `capstoneTeamUuid`, THEN THE Capstone_Team_Workflow SHALL fail the step with one message listing every team uuid returned in `Data` alongside the expected `capstoneTeamUuid`.
4. WHEN the matching team entry is located, THE Capstone_Team_Workflow SHALL assert that `name` equals `capstoneTeamName`, that `groupSize` equals 1 and that `status` equals `accepted`.
5. WHEN the step `Then the admin should see the runtime student as a member of the Capstone team` executes, THE Capstone_Team_Workflow SHALL assert that exactly one Team_Member_Entry of the matching team entry carries `User.uuid` equal to the Runtime_Student uuid.
6. WHEN the Runtime_Student Team_Member_Entry is located, THE Capstone_Team_Workflow SHALL assert that `isGroupRepresentative` marks the Runtime_Student as the Group_Representative.
7. WHERE the admin-side read returns a `Count` value, THE Capstone_Team_Workflow SHALL treat `Count` as the number of teams in the Capstone Section and SHALL derive membership evidence from `Data[i].UserGroups` rather than from `Count`.
8. WHEN each admin-side assertion passes, THE Capstone_Team_Workflow SHALL record a Capstone_Journey_Report checkpoint naming the verified team uuid and member uuid.

### Requirement 5: Student-Side Membership Verification and Same-Team Cross-Check

**User Story:** As a student, I want the team I belong to visible in my own Capstone view, so that the test proves the admin and the student see the SAME team rather than two unrelated teams.

#### Acceptance Criteria

1. WHEN the step `Then the student should see the Capstone team with the runtime student as a member` executes, THE Student_Capstone_Client SHALL send exactly one `GET /api/v1/program/capstoneGamifications/term/{programTermId}` request using the Runtime_Student token, with `programTermId` as a path segment and with the query parameters `courseId` set to `capstoneTermCourseId` and `sectionId` set to `capstoneSectionId`, and SHALL issue zero retries, relying on the framework's existing configured request timeout.
2. WHEN the student-side request is built, THE Capstone_Team_Workflow SHALL set `courseId` to the `capstoneTermCourseId` value held in Execution_Context, which is the Capstone TermCourse uuid, and SHALL source that parameter from no other value, since the API names the parameter `courseId` while keying it by TermCourse identity rather than by CourseMaster identity.
3. WHEN the student-side read returns an HTTP status of 200, THE Capstone_Team_Workflow SHALL assert that `Data.cousreData.uuid` equals `capstoneTermCourseId`, that `Data.cousreData.isGamify` equals the JSON boolean `true` and that `Data.cousreData.Section.uuid` equals `capstoneSectionId`, comparing each uuid by exact case-sensitive string equality with no trimming, and addressing the response field by the API's own spelling `cousreData` with zero correction of that spelling.
4. WHEN the student-visible team is read, THE Capstone_Team_Workflow SHALL assert that `Data.userGroup.uuid` equals `capstoneTeamUuid`, that `Data.userGroup.name` equals `capstoneTeamName` and that `Data.userGroup.status` equals the exact lowercase value `accepted`, comparing each value by exact case-sensitive string equality with no trimming.
5. WHEN the student-visible membership is read, THE Capstone_Team_Workflow SHALL assert that the count of `Data.userGroup.UserGroups` entries whose `User.uuid` equals the Runtime_Student uuid is exactly one, comparing uuids by exact case-sensitive string equality, and SHALL fail the step with a message reporting the observed count and every member uuid returned when that count is zero or greater than one.
6. IF `Data.userGroup.uuid` differs from `capstoneTeamUuid`, THEN THE Capstone_Team_Workflow SHALL fail the step with a message reporting both uuids and identifying the mismatch as the student observing a different team from the admin-created team, and SHALL perform zero membership assertions against the observed team.
7. WHERE the student-side response contains `doubtDiscussion.groupMember`, THE Capstone_Team_Workflow SHALL derive zero pass or fail verdict from that projection and SHALL derive the membership verdict from `Data.userGroup.UserGroups` only.
8. WHEN the student-side read is issued, THE Capstone_Team_Workflow SHALL pass the Runtime_Student `studentToken` held in Execution_Context as the bearer credential, and SHALL leave `adminToken` and `adminUserId` in Execution_Context holding the same values after the read as before it.
9. IF the student-side read returns any HTTP status other than 200, THEN THE Capstone_Team_Workflow SHALL fail the step with a message reporting the observed status and the requested `programTermId`, `courseId` and `sectionId` values, and SHALL read zero fields from the response body.
10. IF `Data.userGroup` is absent, null or an empty object, THEN THE Capstone_Team_Workflow SHALL fail the step with a message reporting that the student-side view exposes no team for the requested Capstone TermCourse and Section, and naming `capstoneTeamUuid` as the expected team.
11. WHEN every student-side assertion passes, THE Capstone_Team_Workflow SHALL record a Capstone_Journey_Report checkpoint naming the verified team uuid and the verified member uuid.

### Requirement 6: Capstone Metrics Inheritance

**User Story:** As an admin, I want the created team to carry the Capstone metrics configured in S2, so that team-scoped metric behaviour starts from the configured definitions.

#### Acceptance Criteria

1. WHEN the step `Then the Capstone team should inherit the configured Capstone metrics` executes, THE Capstone_Team_Workflow SHALL derive the configured metrics from the `GamificationConfig.metrics` array returned by an Authoritative_Read.
2. WHEN the team metric collection and the configuration metric collection are compared, THE Capstone_Team_Workflow SHALL compare the two collections as sets keyed by the Metric_Definition `metric` name field, asserting that each collection has a cardinality of six and that both collections carry the same six `metric` names.
3. WHEN two Metric_Definition entries are matched by `metric` name, THE Capstone_Team_Workflow SHALL assert equality of every remaining field, namely `target`, `type`, `showDashboard` and `currency`, preserving each value's original JSON type during comparison.
4. WHEN Metric_Definition entries are paired, THE Capstone_Team_Workflow SHALL pair them by `metric` name only, SHALL derive zero pairing from array position, and SHALL pass the step when the two collections differ in metric ordering only.
5. WHEN a Metric_Definition carries a `target` value equal to the empty String, THE Capstone_Team_Workflow SHALL compare that `target` by value, treating the empty String as the configured value.
6. IF the two metric collections differ, THEN THE Capstone_Team_Workflow SHALL fail the step with one message listing every `metric` name present in one collection only, and, for each name present in both collections, every field whose value or JSON type differs together with both observed values.
7. WHEN the metrics assertion passes, THE Capstone_Team_Workflow SHALL record a Capstone_Journey_Report checkpoint naming the number of inherited metrics.

### Requirement 7: Capstone Configuration Retention With Documented Exclusions

**User Story:** As a QA engineer, I want the team's configuration echo compared only against fields the API genuinely echoes, so that the scenario proves configuration retention without producing false failures.

#### Acceptance Criteria

1. WHEN the step `Then the Capstone team should retain the configured Capstone configuration` executes, THE Capstone_Team_Workflow SHALL assert equality for exactly the following 9 field pairs and for zero other fields: team `gamificationConfigId` paired with `GamificationConfig.id`, `metrics`, `leaderboard`, `xByXRows`, `xByXColumns`, `resources`, `scheduleStartDate`, `scheduleEndDate` and `allowTeamFormation`, comparing `scheduleStartDate` and `scheduleEndDate` as exact Strings with zero date, format or timezone normalization.
2. WHEN the `leaderboard` object is compared, THE Capstone_Team_Workflow SHALL compare the whole formula object requiring both JSON type and value to match, so that a numeric weightage such as 10, 20 or 30 is treated as unequal to a string weightage such as `"40"`, and SHALL report a JSON type difference as a mismatch even when the textual values are equal.
3. WHEN the configuration comparison is performed, THE Capstone_Team_Workflow SHALL exclude from comparison exactly the 6 Configuration_Exclusion_List fields `leaderboardType`, `triggerInterval`, `triggerDay`, `allowSkipUntil`, `analyticsDisplay` and `dashboardDisplay`, and SHALL pass the step when those 6 fields differ in value or are absent.
4. WHEN the Configuration_Exclusion_List is applied, THE Capstone_Team_Workflow SHALL carry, adjacent to each of the 6 exclusions, its HAR-observed rationale: `leaderboardType` null on the team object versus `formula` on the configuration object; `triggerInterval` null versus `daily`; `triggerDay` null versus the empty String; `allowSkipUntil` null versus the empty String; `analyticsDisplay` and `dashboardDisplay` absent from the team object while present on the configuration object.
5. WHEN the configuration identifier is compared, THE Capstone_Team_Workflow SHALL compare the team `gamificationConfigId` against `GamificationConfig.id` as numbers, SHALL use `capstoneGamificationConfigNumericId` wherever a numeric configuration identifier is required, SHALL use `capstoneGamificationConfigUuid` only where the API exposes the configuration's uuid, and SHALL treat interchanging the numeric id with the uuid as a defect.
6. IF one or more of the 9 compared pairs differ, THEN THE Capstone_Team_Workflow SHALL evaluate every remaining pair and SHALL fail the step once with a message listing, per mismatch, the field name, the team-visible value with its JSON type and the configuration value with its JSON type.
7. WHEN all 9 compared pairs match, THE Capstone_Team_Workflow SHALL record one Capstone_Journey_Report checkpoint naming the compared field groups and the count of excluded fields.
8. IF a field belonging to the 9 compared pairs is absent from either object, THEN THE Capstone_Team_Workflow SHALL fail the step with a message naming the field and naming which object omitted the field, distinguishing an absent field from a field present with a null value.
9. WHEN `resources`, `xByXRows` and `xByXColumns` are compared, THE Capstone_Team_Workflow SHALL compare each collection index-wise, since the HAR shows a stable single-element or fixed authored order, and WHEN `metrics` is compared, THE Capstone_Team_Workflow SHALL apply the order-independent set comparison defined in Requirement 6.
10. WHEN the step derives its expected configuration values, THE Capstone_Team_Workflow SHALL derive every value from an Authoritative_Read issued during S3, SHALL fail the step naming which Authoritative_Read is unavailable when a required response is missing, and SHALL substitute zero value cached by S2 in Scenario_Context_Attributes.

### Requirement 8: Fresh Authoritative Reads Independent of Per-Scenario State

**User Story:** As a QA engineer, I want S3's configuration evidence to come from live reads, so that S3 stays correct even though the per-scenario attribute map resets between scenarios.

#### Acceptance Criteria

1. WHEN S3 verifies persisted state, THE Capstone_Team_Workflow SHALL derive every expected value from an Authoritative_Read issued during S3 or from Execution_Context.
2. WHEN S3 requires a value produced by S2, THE Capstone_Team_Workflow SHALL read that value from Execution_Context or from an Authoritative_Read, treating Scenario_Context_Attributes as empty at S3 start.
3. WHEN a value is read from an Authoritative_Read, THE Capstone_Team_Workflow SHALL assert the response status before reading any field from the response body.
4. WHEN Execution_Context supplies an identifier to S3, THE Capstone_Team_Workflow SHALL use the stored runtime value, and the S3 implementation SHALL contain zero literal uuid, numeric entity id, email address or date values.
5. WHERE S3 requires a date or timestamp for comparison, THE Capstone_Team_Workflow SHALL source that value from an Authoritative_Read response rather than from a literal in the implementation.

### Requirement 9: Runtime Environment Isolation

**User Story:** As a QA engineer, I want S3 to add only the team, so that the other journeys keep passing on the same suite run.

#### Acceptance Criteria

1. WHEN S3 executes end to end, THE Capstone_Team_Workflow SHALL create exactly one new entity, the Capstone_Team, and SHALL issue zero creation requests for Program, Cohort, Program Term, Section, CourseMaster, TermCourse or Student.
2. WHEN S3 addresses a Section, THE Capstone_Team_Workflow SHALL address `capstoneSectionId` and SHALL leave the runtime `sectionId` and its roster unchanged.
3. WHEN S3 addresses a TermCourse, THE Capstone_Team_Workflow SHALL address `capstoneTermCourseId` and SHALL leave the runtime `termCourseId` and its SectionCourse mapping unchanged.
4. WHEN S3 requires a student, THE Capstone_Team_Workflow SHALL use the Runtime_Student created by Environment Setup.
5. WHEN S3 is added, THE S1 and S2 scenarios, their steps and their workflow methods SHALL remain unchanged, except where a change is strictly required for S3 to compile and execute.
6. WHEN S3 is added, THE feature files, step classes, workflows and clients of the Assignment, Session and Attendance journeys SHALL remain unchanged.
7. WHEN S3 is added, THE framework infrastructure components for configuration, hooks, reporting registration and the HTTP client SHALL remain unchanged except for additive changes required by S3.
8. WHEN the full suite runs with S3 present, THE Assignment, Session and Attendance journeys SHALL produce the same outcomes as a run without S3.

### Requirement 10: Cleanup Policy for the Created Team

**User Story:** As a QA engineer, I want the team left out of the cleanup registry, so that no cleanup path invokes an unsupported or out-of-scope deletion.

#### Acceptance Criteria

1. WHEN the Capstone_Team is created, THE Capstone_Team_Workflow SHALL leave the cleanup registry unchanged and SHALL register zero resources for the Capstone_Team.
2. WHEN S3 is added, THE `CleanupManager` and `ResourceType` components SHALL remain unchanged, and `ResourceType` SHALL retain its existing set of values.
3. WHEN S3 is added, THE Capstone_Team_Workflow SHALL contain zero team-deletion requests, and the existing `DELETE /api/v1/program/group/{uuid}/deleteSectionGroup` endpoint SHALL remain unused, since team deletion is out of scope for this feature.
4. WHEN the cleanup policy is implemented, THE Capstone_Team_Workflow SHALL document, adjacent to the creation call, that the Capstone_Team is deliberately unregistered because no existing `ResourceType` value represents a team and inventing a cleanup path is out of scope.
5. WHEN the Capstone Section created by S1 is removed by the existing cleanup journey, THE Capstone_Team SHALL be left to the platform's own section-scoped lifecycle.

### Requirement 11: Test Data Factory Prerequisite and Deterministic Team Data

**User Story:** As a QA engineer, I want the project to compile and team data to be deterministic, so that S3 can run and its read-back assertions can compare by value.

#### Acceptance Criteria

1. WHEN the Test_Data_Factory prerequisite is implemented, THE Test_Data_Factory SHALL expose `capstoneResourceFolderName`, `capstoneResourceName` and `capstoneResourceLink` helpers that satisfy the existing call sites in `CapstoneWorkflow` and `CapstoneWorkflowPayloadTest`.
2. WHEN `capstoneResourceLink` supplies a link value, THE Test_Data_Factory SHALL return a placeholder URL following the existing `assignmentSubmissionDriveLink` convention.
3. WHEN the three `capstoneResource*` helpers are restored, THE Test_Data_Factory SHALL return values that satisfy the existing S2 read-back equality assertions in `CapstoneWorkflow` and `CapstoneWorkflowPayloadTest`, and those existing assertions SHALL remain unmodified.
4. WHEN the Test_Data_Factory prerequisite is complete, THE project SHALL compile, and `CapstoneWorkflowPayloadTest` SHALL execute.
5. WHEN S3 requires a team name and a team description, THE Test_Data_Factory SHALL expose `capstoneTeamName` and `capstoneTeamDescription` helpers whose returned values are stable within a single suite run, so that a read-back assertion can compare each value by equality.
6. WHEN S3 requires the `groupRepresentative.label` display string, THE Test_Data_Factory SHALL supply a generated deterministic label, because Execution_Context holds no student first-name or last-name field from which a label could be composed, and the label is a cosmetic display value rather than an identifier.
7. WHEN the `groupRepresentative` object is asserted, THE Capstone_Team_Workflow SHALL derive the membership verdict from `value` matched against the Runtime_Student uuid rather than from `label`.

### Requirement 12: Framework Layering and Component Placement

**User Story:** As a maintainer, I want S3 to follow the framework's existing layering, so that the new scenario reads like its siblings and stays maintainable.

#### Acceptance Criteria

1. WHEN S3 is implemented, THE implementation SHALL follow the layering Gherkin -> Capstone_Team_Steps -> Capstone_Team_Workflow -> Client -> `RestClient`.
2. WHEN a Capstone_Team_Steps method is implemented, THE method SHALL consist of a single delegation to Capstone_Team_Workflow, containing zero HTTP calls, zero payload construction and zero assertions.
3. WHEN an admin-token Capstone team call is implemented, THE Capstone_Client SHALL host that call, covering the team creation request and the admin team listing read.
4. WHEN the student-token Capstone read is implemented, THE Student_Capstone_Client SHALL host that call at `src/test/java/com/lms/clients/student/StudentCapstoneClient.java`, because the existing Capstone_Client resides under the admin client package.
5. WHEN a client method is implemented, THE client SHALL perform request shaping and serialisation only, containing zero assertions, zero orchestration and zero business logic.
6. WHEN a request body is serialised by Capstone_Client, THE Capstone_Client SHALL serialise with the existing null-preserving Gson configuration and SHALL pass a JSON String to `RestClient`, preserving the caller's key order.
7. WHEN S3 reuses existing capability, THE implementation SHALL reuse the existing clients, workflow components, Execution_Context, `ScenarioContext` and models rather than introducing parallel equivalents.
8. WHEN an API behaviour is required by S3, THE implementation SHALL address only the endpoints, parameters and response fields evidenced by `references/Admin-team-creation.har` or by existing framework code.

### Requirement 13: Scenario Definition, Tags and Reporting

**User Story:** As a QA engineer, I want S3 expressed as the agreed Gherkin in the existing Capstone feature file, so that the journey reads as one continuous capstone story and reports like its siblings.

#### Acceptance Criteria

1. WHEN S3 is added, THE `src/test/resources/features/10_journeys/Capstone.feature` file SHALL contain the S3 scenario positioned after the S2 configuration scenario.
2. WHEN the S3 scenario is written, THE scenario SHALL use the title `Admin creates a Capstone team and verifies the team configuration` and SHALL use the agreed step wording for the readiness precondition, the team creation, the creation verification, the admin-side membership verification, the student-side membership verification, the metrics inheritance verification and the configuration retention verification.
3. WHEN the S3 scenario is tagged, THE scenario SHALL carry the tags `@journey`, `@capstone`, `@team-management` and `@requires-credentials`.
4. WHEN a tag-filtered run selects `@capstone`, THE run SHALL select S1, S2 and S3.
5. WHEN a tag-filtered run selects `@team-management`, THE run SHALL select S3.
6. WHEN an S3 assertion passes, THE Capstone_Team_Workflow SHALL record a Capstone_Journey_Report checkpoint after the assertion passes, and a 2xx response alone SHALL produce zero checkpoints.
7. WHEN the final S3 step completes, THE Capstone_Team_Workflow SHALL call `publish` on Capstone_Journey_Report exactly once.
8. WHEN S3 documentation is written, THE `Capstone.feature` comments SHALL state that S3 consumes the suite-scoped state produced by S1 and S2 and creates only the Capstone_Team.

### Requirement 14: Payload Builder Unit Test Coverage

**User Story:** As a maintainer, I want the team creation payload covered by a unit test, so that the HAR-proven wire contract is protected without a live API call.

#### Acceptance Criteria

1. WHEN the team creation payload is built, THE Capstone_Team_Workflow SHALL expose the builder as a package-private static pure function returning a `LinkedHashMap`, following the existing payload-builder convention.
2. WHEN the payload builder is unit tested, THE `CapstoneWorkflowPayloadTest` SHALL assert the exact top-level key set and key order and the exact `sectionGroup[0]` key set and key order.
3. WHEN the payload builder is unit tested, THE `CapstoneWorkflowPayloadTest` SHALL assert that `size` is a String, that `image` is the empty String, that `allowTeamFormation` is a boolean, that `userGroup` is a flat array of student uuids and that `groupRepresentative` carries `value` and `label`.
4. WHEN a unit test fixture is written, THE fixture SHALL use hand-built placeholder values and SHALL contain zero live uuids and zero credentials.
5. WHEN the unit tests run, THE `CapstoneWorkflowPayloadTest` SHALL pass without issuing any HTTP request.
