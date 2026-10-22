@journey @concern @requires-credentials
Feature: Concern Journey
  As the LMS platform
  I want a student's concern to be routed, answered, escalated and resolved provably
  So that support outcomes reflect exactly what the backend persisted

  # Endpoints verified from hawkeye-apis_2026-07-31_10-10-10.har. The backend calls this domain
  # "reactOut"/"ticket"; the business calls it a Concern. Naming is inconsistent in the backend
  # (react-Out / react-out / reactOut / getSingleTicketData) and is mirrored, not normalised.
  #
  # RUNTIME ISOLATION: the captured environment routes this sub-category's escalation ladder to four
  # seeded support users. Scenario 1 re-points EVERY level (0-3) at the runtime admin, scoped to the
  # runtime cohort, so the concern can never route to an actor outside this execution.
  #
  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 1: Escalation configuration (prerequisite for the business journey)
  # ─────────────────────────────────────────────────────────────────────────────

  @concern-configuration
  Scenario: Runtime admin owns the whole escalation ladder for Technical Support
    Given the runtime environment is ready for the concern journey
    When the concern taxonomy is resolved for technical support
    Then the target concern sub-category is identified
    When the runtime admin is confirmed as an assignable concern handler
    And the escalation flow is configured to the runtime admin for every level
    Then every escalation level routes back to the runtime admin
    And the concern configuration stage is complete
    And the concern configuration report is published

  # ─────────────────────────────────────────────────────────────────────────────
  # Scenario 2: Concern lifecycle — BLOCKED, not yet implemented
  # ─────────────────────────────────────────────────────────────────────────────
  #
  # The concern lifecycle (create -> assign -> reply -> escalate -> resolve -> close) is fully
  # mapped from the HAR EXCEPT its first step: no concern-CREATION endpoint appears anywhere in
  # hawkeye-apis_2026-07-31_10-10-10.har (the concern under test already existed when recording
  # began), nor in any of the 19 other HAR captures in this project. Modelling it would require
  # inventing a URL and payload, which this framework does not do.
  #
  # Everything downstream of creation IS verified and ready to implement once a capture of a
  # student creating a concern is available:
  #   admin reply + status : PATCH organization/update-react-Out-v2/{uuid}
  #                          { adminFirstResponseEmail, isAdmin, messageType:"admin", message,
  #                            fileData, status }
  #   status only          : PATCH organization/update-react-Out-v2/{uuid}
  #                          { status, isAdmin, isWaitingForResponce, isCalendarPaused }
  #   student participation: POST users/reactOut/{uuid}/feedBack
  #                          { userFeedBackMessage, issueResolved:"yes"|"no", rating? }
  #   read (both actors)   : GET organization/{orgUuid}/getSingleTicketData/{uuid}
  #   mark read            : PATCH organization/react-out-message-read/{uuid} { isAdmin }
  #
  # VERIFIED STATE MACHINE (differs from the originally specified one — see the analysis notes):
  #   Open --admin reply(status:"Pending")--> Pending
  #        --admin status:"Resolved"--------> Resolved
  #        --student feedBack issueResolved:"no"---> Escalate      (student-driven, not admin)
  #        --admin status:"Resolved"--------> Resolved
  #        --student feedBack issueResolved:"yes"--> Closed
