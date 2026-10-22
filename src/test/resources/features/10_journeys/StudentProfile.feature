Feature: Student Profile Journey
  As the LMS platform
  I want every section of a student's profile to be provably correct
  So that what the admin and student see reflects exactly what the backend persisted

  # ONE BUSINESS JOURNEY, ONE SCENARIO PER TAB. Each scenario exercises a single profile section's
  # full lifecycle (create/update/verify/delete where applicable) against the runtime student created
  # during Environment Setup. All scenarios use the student token from the runtime environment.
  #
  # Workflow classes remain modular (PersonalDetailsWorkflow, FamilyBackgroundWorkflow, etc.) — only
  # the feature structure is consolidated here. Each workflow owns its own endpoint knowledge and
  # assertions; the step definitions are thin delegates.
  #
  # Endpoints by section:
  #   Personal Details / Family & Background / Guardian Details / Career Preferences:
  #     GET + PATCH /users/{studentUuid}/userProfile (same resource, different fields)
  #   Address Details:
  #     POST /users/userProfile/{studentUuid}/address (full-replace semantics)
  #   Work Experience:
  #     POST + PATCH /users/userProfile/{studentUuid}/experience
  #   Education Qualifications:
  #     POST + PATCH /users/userProfile/{studentUuid}/qualifications
  #   Accomplishments:
  #     POST /users/userProfile/{studentUuid}/accomplishment (create)
  #     PATCH /users/userProfile/{accomplishmentUuid}/accomplishment (update/delete)

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 1: Personal Details
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Personal Details are retrieved, saved, and provably persisted for the runtime student
    Given the runtime environment is ready for the student profile journey
    When the runtime student's profile is retrieved
    Then the profile belongs to the runtime student

    When the immutable name fields are captured from the profile
    Then first name and last name are present and captured as the baseline

    When each non-immutable mandatory field is probed blank against the backend
    Then the backend's mandatory field behaviour is recorded, not assumed

    When the admin submits updated personal details with a unique runtime mobile number
    Then personal details are saved successfully

    When the profile is retrieved again
    Then every submitted field matches the backend, and immutable fields are unchanged

    When the profile is read independently a second time
    Then the second read still matches what was submitted

    Then the personal details journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 2: Family & Background
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Family & Background is retrieved, saved, and provably persisted for the runtime student
    Given the runtime environment is ready for the family background journey
    When the runtime student's profile is retrieved for family background
    Then the family background baseline is captured

    When a non-numeric family income is probed against the backend
    Then the backend's numeric-only behaviour is recorded, not assumed

    When the admin submits family background with poverty line, disability, and a runtime numeric family income
    Then family background is saved successfully

    When the profile is retrieved again for family background
    Then poverty line, disability, and family income all match the backend

    Then the family background journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 3: Guardian Details
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Both guardians are added, one is updated, and neither corrupts the other
    Given the runtime environment is ready for the guardian details journey
    When the runtime student's profile is retrieved for guardian details
    Then the guardian details baseline is captured

    When the first guardian, the father, is added
    Then the father's details are saved and persisted

    When the second guardian, the mother, is added with a malformed mobile number
    Then the mother's details are saved, the father is unaffected, and the mobile format behaviour is recorded

    When the mother's mobile number is updated to a valid runtime number
    Then the guardian info update is saved successfully

    When the profile is retrieved again for guardian details
    Then both guardians match the backend and the update did not corrupt either

    Then the guardian details journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 4: Address Details
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Multiple addresses are managed, the permanent flag toggles both ways, and only the first address is protected from deletion
    Given the runtime environment is ready for the address details journey
    When the runtime student's profile is retrieved for address details
    Then the address details baseline is captured

    When the first address is added
    Then the first address is saved and persisted

    When the first address is marked as permanent
    Then the first address's permanent flag is persisted as true

    When the first address is unmarked as permanent
    Then the first address's permanent flag is persisted as false

    When a second address is added
    Then both addresses are present and the first is unaffected

    When the second address is marked as permanent
    Then the second address's permanent flag is persisted as true and the first is unaffected

    When each mandatory address field is probed blank against the backend
    Then the backend's address field validation behaviour is recorded, not assumed, and the real state is restored

    When deleting the first address is attempted while a second address exists
    Then the backend's first-address deletion protection is recorded, not assumed, and the real state is restored

    When the second address is deleted
    Then only the first address remains

    When the profile is retrieved again for address details
    Then the remaining address matches the backend exactly

    Then the address details journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 5: Work Experience
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Work experience is created, updated, verified, and deleted through its full lifecycle
    Given the runtime environment is ready for the work experience journey
    When the runtime student's profile is retrieved for work experience
    Then the work experience baseline is captured

    When a new work experience entry is created
    Then the created experience is returned with a uuid

    When the profile is retrieved to verify the experience creation
    Then the created experience is persisted in experienceData

    When the work experience entry is updated with new company and job profile
    Then the updated experience retains its uuid with modified fields

    When the profile is retrieved to verify the experience update
    Then the updated fields are persisted in experienceData

    When the work experience entry is deleted
    Then the deletion is confirmed by the backend

    When the profile is retrieved to verify the experience deletion
    Then the deleted experience no longer appears in experienceData

    Then the work experience journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 6: Education Qualifications
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Education qualification is created, updated with subjects and marks, and deleted
    Given the runtime environment is ready for the education qualification journey
    When the runtime student's profile is retrieved for education qualifications
    Then the education qualification baseline is captured

    When a new education qualification is created with minimal fields
    Then the created qualification is returned with a uuid

    When the profile is retrieved to verify the qualification creation
    Then the created qualification is persisted in educationData

    When the qualification is updated with subjects marks and school details
    Then the updated qualification retains its uuid with enriched fields

    When the profile is retrieved to verify the qualification update
    Then the updated subjects and marks are persisted in educationData

    When the qualification is deleted
    Then the deletion of the qualification is confirmed by the backend

    When the profile is retrieved to verify the qualification deletion
    Then the deleted qualification no longer appears in educationData

    Then the education qualification journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 7: Accomplishments
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Multiple accomplishments are created and managed with sibling isolation
    Given the runtime environment is ready for the accomplishments journey
    When the runtime student's profile is retrieved for accomplishments
    Then the accomplishments baseline is captured

    When the first accomplishment is created
    Then the first accomplishment is returned with a uuid

    When the second accomplishment is created
    Then both accomplishments exist in the response

    When the profile is retrieved to verify both accomplishments
    Then both accomplishments are persisted in the profile

    When the first accomplishment is updated with new title and description
    Then the first accomplishment retains its uuid with modified fields and the second is unaffected

    When the profile is retrieved to verify the accomplishment update
    Then the updated accomplishment fields are persisted

    When the second accomplishment is deleted
    Then the second accomplishment is removed and the first survives

    When the profile is retrieved to verify the accomplishment deletion
    Then only the first accomplishment remains in the profile

    Then the accomplishments journey stage is complete

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 8: Career Preferences
  # ─────────────────────────────────────────────────────────────────────────────

  @journey @student-profile @requires-credentials
  Scenario: Career preferences are saved, updated, and verified through their full lifecycle
    Given the runtime environment is ready for the career preferences journey
    When the runtime student's profile is retrieved for career preferences
    Then the career preferences baseline is captured

    When the career preferences are saved with domain selections and dream companies
    Then the career preferences save is accepted

    When the profile is retrieved to verify the career preferences save
    Then all career preference fields and dream company entries are persisted

    When the career preferences are updated with modified domain and dream companies
    Then the career preferences update is accepted

    When the profile is retrieved to verify the career preferences update
    Then the updated career preference fields are persisted

    Then the career preferences journey stage is complete
