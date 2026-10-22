Feature: Assignment Journey
  As the LMS platform
  I want a complete assignment business journey to run against the runtime environment
  So that assignment creation, retrieval, and submission are all verified end-to-end against real,
    HAR-captured behaviour, with every entity traced back to the same runtime course and student

  # BUSINESS JOURNEY (organized by capability, not by actor): this feature describes ONE end-to-end
  # assignment lifecycle that transitions between actors (Admin, Student, Faculty) while consuming
  # the SAME suite-scoped runtime environment ExecutionContext holds. It runs in 10_journeys/, after
  # 00_setup/EnvironmentSetup.feature has provisioned + authenticated the environment and before
  # 99_cleanup/Cleanup.feature tears it down. It never re-provisions a Program/Cohort/Course/Student
  # and never logs the student in again - it reuses the ids and sessions already in ExecutionContext.
  #
  # PROGRESSIVE: this journey is built one HAR-verified stage at a time and extended IN PLACE (never
  # forked into a second assignment feature). Implemented so far:
  #
  #   Environment Setup (done in 00_setup)
  #        -> Admin creates Assignment           (POST createAssignmentDetails, verified)
  #        -> Verify Assignment (Admin)          (GET getAllSessionAssignmentDetails read-back)
  #        -> Store + Register Assignment        (uuid/name/dueDate/publishStatus; ResourceType.ASSIGNMENT)
  #        -> Cross-Verify runtime relation chain (GET termCourse/{id}: Program/Cohort/ProgramTerm/Course)
  #        -> Student retrieves Assignment list   (GET getAllStudentAssignments, located by UUID only)
  #        -> Student retrieves Assignment detail (GET assignmentUpload/{uuid}, cross-verified)
  #        -> Student submits Assignment          (POST assignmentUpload/{uuid}, text/link-only)
  #        -> Verify Submission                   (GET getAllStudentAssignments re-fetch: latestSubmissionExists)
  #        -> Store Submission + Register cleanup (ResourceType.ASSIGNMENT_SUBMISSION)
  #
  # Also implemented, as their own SEPARATE scenarios (each business rule gets its own scenario, since
  # each creates its OWN fresh assignment - never reusing the first scenario's assignment):
  #   Draft != Visible:  Admin creates a Draft assignment (isDraft=true) -> confirmed ABSENT from the
  #                       student's own assignment list -> confirmed still retrievable by the admin.
  #   Draft -> Published: Admin creates a Draft, publishes it via PATCH assignmentDetail/{id}
  #                       (isDraft: true -> false) -> confirmed the student NOW sees it.
  #   Published -> Unpublished: publish -> student sees it -> unpublish (isDraft: false -> true, same
  #                       PATCH mechanism, same buildUpdatePayload) -> confirmed student refresh no
  #                       longer shows it. Visibility toggles correctly in BOTH directions.
  #   Multiple assignments: creates 3 separate assignments (A, B, C) -> confirms all 3 belong to the
  #                       runtime course -> submits ONLY B -> confirms ONLY B shows a submission (A/C
  #                       unaffected). Submission isolation between sibling assignments.
  #   Admin edits content: publishes an assignment -> admin edits title/description/marks/due date via
  #                       the SAME PATCH mechanism (content overrides on buildUpdatePayload, deliberately
  #                       different values, not a coincidental match) -> confirms the student's refreshed
  #                       detail view shows the NEW values, not the originals.
  #   Deadline enforcement (two scenarios, same setup, opposite deadLineToggle): a published assignment
  #   is backdated to yesterday via PATCH (no HAR proves CREATE accepts a past date, so backdating via
  #   the already-proven PATCH mechanism is the evidence-backed choice). With deadLineToggle=false (the
  #   default) submission is rejected 406 "Submission is not allowed after the deadline" (a real,
  #   live-captured error) though viewing still succeeds. With deadLineToggle=true (the admin's "Allow
  #   Submission after deadline" toggle) submission SUCCEEDS despite being late.
  #
  # Pending further HAR captures (to be appended to THIS feature, no stubs, no guessed endpoints):
  #   Admin evaluates Submission -> Admin publishes Grade/Feedback -> Student retrieves Grade
  #   -> Student retrieves Feedback
  #
  # Endpoints verified from HAR hawkeye-apis_2026-07-21_22-22-20.har (admin create/verify),
  # hawkeye-apis_2026-07-23_09-54-52.har (admin re-confirmed field-for-field + the full student
  # retrieve/submit flow + the direct termCourse detail GET used for cross-verification), and
  # hawkeye-apis_2026-07-23_17-59-37.har (the PATCH assignmentDetail/{id} publish flow). All requests
  # go through the single, cross-actor AssignmentClient (com.lms.clients) - admin and student calls
  # differ only in which bearer token is passed, exactly like AuthenticationClient's existing pattern.
  #
  # The captured student submission included a real file uploaded via a genuine third-party Filestack
  # multipart flow (filestackapi.com) - deliberately NOT replicated (would require a separate third-party
  # integration + credentials this framework doesn't have). Submission here is TEXT/LINK-only, a
  # non-invented reduced variant: the assignment's own assignmentFormatType.types=[desc,file,link]
  # (set at creation) declares description/link submissions as independently valid, not exclusively
  # tied to a file upload. See AssignmentClient#submitAssignment's Javadoc for the full reasoning.
  #
  # The PATCH publish flow mirrors the real edit-and-publish UI: it fetches the assignment's CURRENT
  # state (the same getAssignments GET used for admin verification) and resends it unchanged except for
  # isDraft/updatedBy - never sending fresh/different content as a side effect of publishing. See
  # AssignmentWorkflow#buildUpdatePayload's Javadoc.

  @journey @assignment @requires-credentials
  Scenario: Admin creates an assignment and the runtime student retrieves and submits it
    Given the runtime environment is ready for the assignment journey
    When the admin creates and publishes an assignment on the runtime course
    Then the assignment exists on the runtime course with the submitted details
    And the assignment is stored in the runtime context for later journey stages
    And the assignment is registered for suite cleanup

    When the assignment is cross-verified against the runtime program, cohort, and course chain
    Then the assignment's full relation chain matches the runtime environment

    When the runtime student retrieves their assignment list
    Then the runtime assignment appears in the student's own assignment list

    When the runtime student retrieves the assignment details
    Then the assignment details match the assignment the runtime admin created

    When the runtime student submits the assignment
    Then the submission is linked to the runtime assignment and stored in the runtime context

    When the submission is verified via the student's assignment list
    Then the runtime assignment shows a verified submission for the runtime student
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: A draft assignment stays hidden from the runtime student but remains visible to the admin
    Given the runtime environment is ready for the assignment journey
    When the admin creates a draft assignment on the runtime course
    Then the draft assignment exists on the runtime course and is verified as a draft

    When the draft assignment's visibility to the student is checked
    Then the draft assignment does not appear in the student's own assignment list

    When the draft assignment is retrieved by the admin
    Then the draft assignment is still retrievable by the admin
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: Publishing a draft assignment makes it visible to the runtime student
    Given the runtime environment is ready for the assignment journey
    When the admin creates a draft assignment on the runtime course
    Then the draft assignment exists on the runtime course and is verified as a draft

    When the admin publishes the draft assignment
    Then the draft assignment is now published

    When the draft assignment's visibility to the student is re-checked
    Then the published assignment now appears in the student's own assignment list
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: Unpublishing an assignment hides it from the runtime student again
    Given the runtime environment is ready for the assignment journey
    When the admin creates a draft assignment on the runtime course
    Then the draft assignment exists on the runtime course and is verified as a draft

    When the admin publishes the draft assignment
    Then the draft assignment is now published

    When the draft assignment's visibility to the student is re-checked
    Then the published assignment now appears in the student's own assignment list

    When the admin unpublishes the assignment
    Then the assignment is now unpublished

    When the draft assignment's visibility to the student is checked
    Then the draft assignment does not appear in the student's own assignment list
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: Submitting one of several assignments does not affect its siblings
    Given the runtime environment is ready for the assignment journey
    When the admin creates three assignments A, B, and C on the runtime course
    Then all three assignments are created and verified on the runtime course

    When the runtime student retrieves their assignment list containing A, B, and C
    Then assignments A, B, and C all belong to the runtime course

    When the runtime student submits only assignment B
    Then only assignment B is registered as submitted

    When the student's assignment list is re-checked for submission isolation
    Then only assignment B shows a submission while A and C remain unaffected
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: Admin edits propagate to the student's view of the assignment
    Given the runtime environment is ready for the assignment journey
    When the admin creates a draft assignment on the runtime course
    Then the draft assignment exists on the runtime course and is verified as a draft

    When the admin publishes the draft assignment
    Then the draft assignment is now published

    When the admin edits the assignment's title, description, marks, and due date
    Then the assignment is updated with the new values

    When the runtime student refreshes and retrieves the assignment details
    Then the student sees the updated title, description, marks, and due date
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: A student cannot submit an assignment after its deadline has passed
    Given the runtime environment is ready for the assignment journey
    When the admin creates a draft assignment on the runtime course
    Then the draft assignment exists on the runtime course and is verified as a draft

    When the admin publishes the draft assignment
    Then the draft assignment is now published

    When the admin sets the assignment's due date to yesterday
    Then the assignment's due date is now in the past

    When the runtime student views the past-due assignment
    Then the student can view the past-due assignment

    When the runtime student attempts to submit the past-due assignment
    Then the submission is rejected because the deadline has passed
    Then the assignment journey stage is complete

  @journey @assignment @requires-credentials
  Scenario: A student can submit a past-due assignment when late submission is explicitly allowed
    Given the runtime environment is ready for the assignment journey
    When the admin creates a draft assignment on the runtime course
    Then the draft assignment exists on the runtime course and is verified as a draft

    When the admin publishes the draft assignment
    Then the draft assignment is now published

    When the admin enables late submission on the past-due assignment
    Then the assignment now allows submission after its deadline

    When the runtime student submits the past-due assignment with late submission allowed
    Then the submission succeeds despite being past the deadline
    Then the assignment journey stage is complete
