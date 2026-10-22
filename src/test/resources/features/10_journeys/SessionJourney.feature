Feature: Session Journey
  As the LMS platform
  I want a complete session business journey to run against the runtime environment
  So that offline session creation is verified end-to-end against real, HAR-captured behaviour, with
    the session traced back to the same runtime program, cohort, term, section, and course

  # BUSINESS JOURNEY (organized by capability, not by actor): this feature describes ONE session
  # lifecycle that will transition between actors (Admin, Student, Faculty) as later phases land,
  # while consuming the SAME suite-scoped runtime environment ExecutionContext holds. It runs in
  # 10_journeys/, after 00_setup/EnvironmentSetup.feature has provisioned + authenticated the
  # environment and before 99_cleanup/Cleanup.feature tears it down. It never re-provisions a
  # Program/Cohort/Course/Section and it does NOT depend on the Assignment journey having run - it
  # resolves the runtime course's termCourse id itself, so journey execution order stays irrelevant.
  #
  # PROGRESSIVE: this journey is built one HAR-verified phase at a time and extended IN PLACE.
  # Implemented so far (Phase 1 - Admin creates an Offline Session):
  #
  #   Environment Setup (done in 00_setup)
  #        -> Verify runtime Course              (GET section detail: the course's termCourse id)
  #        -> Retrieve runtime Locations         (GET attendance/{orgId}/getAllRoomsList)
  #        -> Select the test Location           (roomName ~ "test", else buildingName ~ "test",
  #                                               else the first room - never a hardcoded uuid)
  #        -> Generate runtime Session name      (test_<timestamp>, unique per execution)
  #        -> Verify the slot is free            (POST sessions/checkSessionDateAndTime)
  #        -> Create Offline Session             (PATCH section/{sectionId}/sessionDetail, sessionId:"",
  #                                               sessionDate = TODAY, per business requirement)
  #        -> Verify Session                     (GET getBaseSessions read-back, diffed against the
  #                                               exact payload sent)
  #        -> Verify Session (2nd, independent)  (GET sessionDetail/sessions/courseSession?termCourseId=
  #                                               the SAME runtime termCourseId, via a DIFFERENT
  #                                               endpoint - re-diffed against the same create payload,
  #                                               plus its own embedded TermCourse/ProgramTerm relation)
  #        -> Cross-Verify runtime relation chain (GET termCourse/{id}: Program/Cohort/ProgramTerm/
  #                                               Section/Course + the course's SessionDetails)
  #        -> Store + Register Session           (sessionUuid/sessionName/sessionMode/locationUuid/
  #                                               locationName; ResourceType.SESSION)
  #
  # Not yet implemented (each awaits its own HAR): student-side session visibility, attendance,
  # QR attendance, live sessions, faculty assignment, session feedback, and session DELETION (the
  # session is registered for cleanup here; the delete handler lands when its endpoint is captured).
  #
  # Endpoints verified from HAR hawkeye-apis_2026-07-27_11-29-55.har (the full admin "add session to a
  # course" flow), hawkeye-apis_2026-07-27_14-56-29.har (the room/location lookup), and
  # hawkeye-apis_2026-07-27_16-02-28.har (the second, independent courseSession read-back path). All
  # requests go through the single SessionClient (com.lms.clients), mirroring AssignmentClient's
  # existing pattern.
  #
  # MODE: there is deliberately NO modality lookup. The captured frontend sends sessionMode as the
  # literal string "offline" - it is not an id resolved from any lookup endpoint, so inventing one
  # would mean inventing an API. Online/Hybrid sessions are separate future journeys.
  #
  # The captured session carried a post-class file uploaded through a genuine third-party Filestack
  # multipart flow (filestackapi.com) - deliberately NOT replicated (would require a separate
  # third-party integration + credentials this framework doesn't have), exactly as decided for the
  # assignment submission. The material lists are sent empty and the matching sessionResources flag is
  # false, so the payload stays internally consistent; the pre-read entries ARE sent, as link-only
  # entries (isUpload:false) matching the captured link entries verbatim.

  @journey @session @requires-credentials
  Scenario: Admin creates an offline session on the runtime course
    Given the runtime environment is ready for the session journey
    When the admin verifies the runtime course the session will belong to
    Then the runtime course is attached to the runtime section

    When the admin retrieves the runtime locations and selects a test location
    Then a runtime location is selected from the location lookup

    When the admin creates an offline session on the runtime course
    Then the offline session exists on the runtime course with a unique runtime name

    When the session is verified against the runtime course session list
    Then the session details match what the admin submitted

    When the session is verified using the course session lookup for the runtime termCourseId
    Then the session details match across both verification endpoints

    When the session is cross-verified against the runtime program, cohort, section, and course chain
    Then the session's full relation chain matches the runtime environment

    Then the session satisfies the session journey business rules
    And the session is stored in the runtime context for later journey phases
    And the session is registered for suite cleanup
    And the session journey stage is complete
