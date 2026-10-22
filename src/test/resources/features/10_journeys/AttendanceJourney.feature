Feature: Attendance Journey
  As the LMS platform
  I want a complete attendance business journey to run against the runtime environment
  So that attendance marked by the admin is provably reflected in the runtime student's own view, and
    stays synchronized between both actors when it is updated

  # BUSINESS JOURNEY (organized by capability, not by actor): this feature describes ONE attendance
  # lifecycle that transitions between Admin and Student while consuming the SAME suite-scoped runtime
  # environment ExecutionContext holds. It runs in 10_journeys/, after 00_setup/EnvironmentSetup.feature
  # has provisioned + authenticated the environment and before 99_cleanup/Cleanup.feature tears it down.
  #
  # WORKFLOW REUSE: this journey does NOT create sessions itself. SessionWorkflow remains the single
  # owner of session creation; AttendanceWorkflow calls into it (verify course -> select location ->
  # create -> verify) and then owns ONLY the attendance operations. Future attendance flavours (QR,
  # OTP, biometric) and Live Session journeys must reuse SessionWorkflow the same way.
  #
  #   Environment Setup (done in 00_setup)
  #        -> Create Offline Session            (delegated to SessionWorkflow; slot is derived from the
  #                                              current time and BACKDATED so the session has already
  #                                              finished - the precondition for marking attendance)
  #        -> Verify Session (Admin)            (GET section/{s}/termCourse/{tc}/sessionDetail/ - also
  #                                              captures the null/null attendance baseline)
  #        -> Verify Session (Student)          (GET sessions/getStudentSessionsByDate, STUDENT token)
  #        -> Mark Attendance = Present         (POST createTempAttendance, attendanceStatus "1")
  #        -> Verify Attendance (Admin)         (GET session/{uuid}/getTempAttendanceData + the session
  #                                              list's totalPresent/totalAbsent rollup)
  #        -> Verify Attendance (Student)       (GET getStudentSessionsByDate, STUDENT token)
  #        -> Update Attendance = Absent        (POST createTempAttendance again, attendanceStatus "0")
  #        -> Verify Attendance (Admin)         (same two admin reads; asserts Present no longer exists)
  #        -> Verify Attendance (Student)       (same student read; must now show Absent)
  #        -> Register Cleanup                  (ResourceType.ATTENDANCE)
  #
  # Endpoints verified from HAR hawkeye-apis_2026-07-27_17-18-13.har (the full admin-marks/student-sees/
  # admin-updates/student-sees flow). All attendance requests go through the single AttendanceClient
  # (com.lms.clients), mirroring AssignmentClient's and SessionClient's existing pattern.
  #
  # STATUS CODES: attendanceStatus is a STRING - "1" = Present, "0" = Absent.
  #
  # UPDATE USES THE SAME ENDPOINT: there is no separate attendance-update API. Re-posting
  # createTempAttendance for the same session OVERWRITES the existing record - verified in the capture,
  # where the Present call and the later Absent call both returned the SAME Data.uuid. The journey
  # asserts that uuid is unchanged after the update, which is the business proof that the record was
  # modified rather than duplicated.
  #
  # NUMERIC STUDENT IDS: attendance keys students by a NUMERIC id that no other part of the framework
  # carries. It is resolved at runtime from getSectionStudents by matching the runtime student's uuid -
  # never hardcoded. The save then sends the WHOLE section roster in one call, exactly as the captured
  # screen does.
  #
  # CROSS-ACTOR PROOF: the student-token responses scope TempRecords[0].allData.studentIds to a SINGLE
  # object for the calling student (the admin-token response returns an array of every student), so the
  # student-side assertions read a genuinely different projection rather than re-reading the admin's.

  @journey @attendance @requires-credentials
  Scenario: Admin marks attendance and the runtime student sees it stay in sync through an update
    Given the runtime environment is ready for the attendance journey
    When a completed offline session is created on the runtime course for attendance
    Then the runtime session is available for attendance

    When the admin views the runtime session
    Then the runtime session is visible to the admin

    When the runtime student views their sessions
    Then the runtime session is visible to the student

    When the admin marks the runtime student present
    Then the attendance record exists for the runtime student

    When the admin verifies the attendance is present
    And the runtime student verifies their attendance is present
    Then the admin and student both report the runtime student as present

    When the admin updates the runtime student to absent
    Then the same attendance record is modified rather than duplicated

    When the admin verifies the attendance is absent
    And the runtime student verifies their attendance is absent
    Then the admin and student remain synchronized throughout the attendance journey

    And the attendance is registered for suite cleanup
    And the attendance journey stage is complete
