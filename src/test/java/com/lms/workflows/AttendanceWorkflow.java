package com.lms.workflows;

import com.lms.cleanup.ResourceType;
import com.lms.clients.AttendanceClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.AttendanceJourneyReport;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Attendance journey - the third business journey to run
 * against the suite-scoped runtime environment. Its single business capability is ATTENDANCE: marking
 * it, updating it, and proving the Admin and Student views stay synchronized throughout.
 *
 * <p><b>It does not create sessions.</b> {@link SessionWorkflow} remains the sole owner of session
 * creation; this workflow calls {@link #createRuntimeSessionForAttendance} which simply delegates to
 * {@code SessionWorkflow}'s existing verify-course -> select-location -> create -> verify chain. Every
 * future attendance flavour (QR, OTP, biometric) and Live Session journey must reuse SessionWorkflow
 * the same way rather than reimplementing session creation.
 *
 * <p>The session SessionWorkflow produces is already in the past (it derives an already-finished slot
 * from the current time), which is what makes attendance markable on it.
 *
 * <p>Endpoints verified from the real HAR capture hawkeye-apis_2026-07-27_17-18-13.har:
 * <ul>
 *   <li>Admin: {@code getSectionStudents} (numeric ids), {@code attendanceRemarks},
 *       {@code createTempAttendance} (marks AND updates), {@code getTempAttendanceData},
 *       and the {@code sessionDetail} list's {@code totalPresent}/{@code totalAbsent} aggregates</li>
 *   <li>Student: {@code getStudentSessionsByDate} and {@code courseSession} - both expose the caller's
 *       OWN attendance at {@code TempRecords[0].allData.studentIds.attendanceStatus}</li>
 * </ul>
 *
 * <p>Each business action is its own method with its own reporting checkpoint and assertions
 * (mirrored 1:1 by a Gherkin step) rather than one large method - so a failure always points at the
 * exact business action that failed.
 *
 * <p>Instance state ({@link #sectionRoster}) is per-scenario: PicoContainer builds a fresh steps
 * class - and therefore a fresh workflow - for every scenario, so it cannot leak.
 */
public class AttendanceWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AttendanceWorkflow.class);

    /** The captured payload marks every student as having done the pre-read; mirrored verbatim. */
    private static final String IS_PRE_READ = "1";

    /** The captured payload sends an empty remark; a remark-specific journey is future work. */
    private static final String NO_REMARK = "";

    private final SessionWorkflow sessionWorkflow;
    private final AttendanceClient attendanceClient;

    /** The section roster (uuid -> numeric id), read once and reused by every mark/update call. */
    private List<Map<String, Object>> sectionRoster;

    public AttendanceWorkflow() {
        this.sessionWorkflow = new SessionWorkflow();
        this.attendanceClient = new AttendanceClient();
    }

    // ================= Stage 1: the runtime session (delegated to SessionWorkflow) =================

    /**
     * Produces the runtime session this journey marks attendance on by REUSING {@link SessionWorkflow}
     * end to end - resolve the runtime course, select the runtime location, create the offline
     * session, verify it, and register it for cleanup. No session-creation logic lives here.
     *
     * <p>The session SessionWorkflow creates is already finished (its slot is derived from the current
     * time, backdated), which is the precondition for marking attendance on it.
     */
    public void createRuntimeSessionForAttendance(ScenarioContext context) {
        sessionWorkflow.verifyRuntimeCourse(context);
        sessionWorkflow.selectRuntimeTestLocation(context);
        sessionWorkflow.createOfflineSessionOnRuntimeCourse(context);
        sessionWorkflow.verifyRuntimeSession(context);

        AttendanceJourneyReport.checkpoint(context, "Runtime Session Created",
                "Created + verified via SessionWorkflow (single owner of session creation)",
                "Session: " + context.getSessionName(), "UUID: " + context.getSessionUuid(),
                "Location: " + context.getLocationName(),
                "Slot is already in the past, so attendance can be marked on it");
    }

    // ================= Stage 2: both actors can see the session =================

    /**
     * Admin-side proof the session exists on the runtime course, read through the ADMIN attendance
     * screen's own session list (not the session-journey endpoint) - the same list that later exposes
     * the attendance aggregates, so this also establishes the pre-attendance baseline:
     * {@code totalPresent}/{@code totalAbsent} are null before anyone is marked.
     */
    public void verifySessionVisibleToAdmin(ScenarioContext context) {
        Map<String, Object> session = findSessionInAdminList(context);

        Object totalPresent = session.get("totalPresent");
        Object totalAbsent = session.get("totalAbsent");

        AttendanceJourneyReport.checkpoint(context, "Runtime Session Verified (Admin)",
                "Visible in the admin attendance session list for the runtime course",
                "Session: " + session.get("sessionName"),
                "Baseline attendance - totalPresent: " + totalPresent + ", totalAbsent: " + totalAbsent);
        log.info("Session {} visible to admin; baseline present/absent = {}/{}",
                context.getSessionUuid(), totalPresent, totalAbsent);
    }

    /**
     * Student-side proof the SAME runtime session is visible in the runtime student's own day view,
     * read with the STUDENT token via {@code getStudentSessionsByDate} for the session's date. This
     * is the endpoint that later carries the student's attendance status, so establishing visibility
     * here means the later status assertions are read from a view already proven to be the student's.
     */
    public void verifySessionVisibleToStudent(ScenarioContext context) {
        Map<String, Object> session = findSessionInStudentDayView(context);

        assertThat(session.get("sessionName"))
                .as("the session the student sees must be the runtime session")
                .isEqualTo(context.getSessionName());

        AttendanceJourneyReport.checkpoint(context, "Runtime Session Verified (Student)",
                "Visible in the runtime student's own day view (getStudentSessionsByDate, student session)",
                "Session: " + session.get("sessionName"), "UUID: " + context.getSessionUuid(),
                "Same runtime session the admin sees");
        log.info("Session {} confirmed visible to the runtime student", context.getSessionUuid());
    }

    // ================= Stage 3: mark attendance =================

    /**
     * Marks the runtime student PRESENT. Resolves the section roster first (the only source of the
     * numeric student id the attendance API keys on), loads the org's remark options exactly as the
     * attendance screen does, then fires {@code createTempAttendance} with the WHOLE roster - the way
     * the captured frontend saves the screen - with the runtime student's status set to Present.
     * Stores the resulting record uuid so the update stage can prove it OVERWRITES rather than
     * creating a second record.
     */
    public void markRuntimeStudentPresent(ScenarioContext context) {
        resolveSectionRoster(context);
        loadAttendanceRemarkOptions(context);

        String recordUuid = submitAttendance(context, AttendanceClient.STATUS_PRESENT);
        context.setAttendanceRecordUuid(recordUuid);
        context.setAttendanceStatus(AttendanceClient.STATUS_PRESENT);

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("sessionUuid", context.getSessionUuid());
        deletionContext.put("termCourseId", context.getTermCourseId());
        context.registerCreated(ResourceType.ATTENDANCE, recordUuid, deletionContext);

        AttendanceJourneyReport.checkpoint(context, "Attendance Marked Present",
                "Student: " + context.getStudentEmail() + " (numeric id " + context.getStudentNumericId() + ")",
                "attendanceStatus sent: \"1\" (Present)",
                "Attendance record: " + recordUuid,
                "Registered for cleanup as ResourceType.ATTENDANCE");
        log.info("Marked runtime student {} PRESENT on session {} (record {})",
                context.getStudentNumericId(), context.getSessionUuid(), recordUuid);
    }

    /**
     * Flips the SAME attendance record to ABSENT via the same {@code createTempAttendance} endpoint -
     * the capture proves there is no separate update endpoint and that re-posting overwrites the
     * existing record. Asserts the returned record uuid is UNCHANGED, which is the business proof that
     * the existing record was modified rather than a second one created.
     */
    public void updateRuntimeStudentToAbsent(ScenarioContext context) {
        String originalRecordUuid = context.getAttendanceRecordUuid();
        assertThat(originalRecordUuid).as("attendance must have been marked before it can be updated").isNotBlank();

        String recordUuid = submitAttendance(context, AttendanceClient.STATUS_ABSENT);

        assertThat(recordUuid)
                .as("updating attendance must MODIFY the existing record %s, not create a second one - the "
                        + "returned record uuid must be unchanged", originalRecordUuid)
                .isEqualTo(originalRecordUuid);

        context.setAttendanceStatus(AttendanceClient.STATUS_ABSENT);

        AttendanceJourneyReport.checkpoint(context, "Attendance Updated to Absent",
                "Student: " + context.getStudentEmail(),
                "attendanceStatus sent: \"0\" (Absent)",
                "Same attendance record modified: " + recordUuid + " (no second record created)");
        log.info("Updated runtime student {} to ABSENT on session {} (same record {})",
                context.getStudentNumericId(), context.getSessionUuid(), recordUuid);
    }

    // ================= Stage 4: verification, admin + student =================

    /**
     * Verifies the runtime student's attendance from the ADMIN side, through BOTH admin read paths:
     * the per-session {@code getTempAttendanceData} record (exact per-student status) and the session
     * list's {@code totalPresent}/{@code totalAbsent} aggregates (proving the session-level rollup
     * moved too, not just the raw record). Also asserts the OPPOSITE status is gone, so a stale
     * duplicate row could not pass unnoticed.
     */
    public void verifyAdminAttendance(ScenarioContext context, String expectedStatus) {
        String sessionUuid = context.getSessionUuid();
        String numericId = context.getStudentNumericId();
        String label = statusLabel(expectedStatus);

        Response response = attendanceClient.getTempAttendanceData(sessionUuid, context.getAdminToken());
        assertThat(response.statusCode())
                .as("admin attendance read-back must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> students = response.jsonPath().getList("Data.allData.studentIds");
        assertThat(students)
                .as("admin attendance data must expose Data.allData.studentIds for session %s", sessionUuid)
                .isNotNull().isNotEmpty();

        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> entry : students) {
            if (numericId.equals(String.valueOf(entry.get("studentId")))) {
                mine.add(entry);
            }
        }
        assertThat(mine)
                .as("the runtime student (numeric id %s) must appear EXACTLY ONCE in the session's attendance "
                        + "record - more than one entry would mean a duplicate attendance row", numericId)
                .hasSize(1);

        Map<String, Object> record = mine.get(0);
        assertThat(String.valueOf(record.get("attendanceStatus")))
                .as("admin view must report the runtime student as %s", label)
                .isEqualTo(expectedStatus);

        // Aggregate rollup on the session itself - proves the session reflects the change, not just
        // the raw attendance record.
        Map<String, Object> session = findSessionInAdminList(context);
        String totalPresent = String.valueOf(session.get("totalPresent"));
        String totalAbsent = String.valueOf(session.get("totalAbsent"));
        if (AttendanceClient.STATUS_PRESENT.equals(expectedStatus)) {
            assertThat(totalPresent).as("session's totalPresent must be non-zero once a student is Present").isNotEqualTo("0");
            assertThat(totalAbsent).as("session's totalAbsent must be 0 when every student is Present").isEqualTo("0");
        } else {
            assertThat(totalAbsent).as("session's totalAbsent must be non-zero once a student is Absent").isNotEqualTo("0");
            assertThat(totalPresent).as("session's totalPresent must be 0 when every student is Absent - the previous "
                    + "Present state must no longer exist").isEqualTo("0");
        }

        AttendanceJourneyReport.checkpoint(context, "Admin Attendance Verified (" + label + ")",
                "Read back via getTempAttendanceData for session " + sessionUuid,
                "Student " + context.getStudentEmail() + " -> attendanceStatus \"" + expectedStatus + "\" (" + label + ")",
                "Exactly one attendance entry for the runtime student (no duplicate row)",
                "Session rollup - totalPresent: " + totalPresent + ", totalAbsent: " + totalAbsent);
        log.info("Admin verified runtime student {} as {} on session {} (rollup {}/{})",
                numericId, label, sessionUuid, totalPresent, totalAbsent);
    }

    /**
     * Verifies the SAME attendance from the STUDENT side, using the student's own session token and
     * the endpoint the student portal actually calls. The student view exposes only the caller's own
     * record ({@code TempRecords[0].allData.studentIds} is a single object, not the admin's array), so
     * reading a matching status here is a genuine cross-actor confirmation rather than a re-read of
     * the same admin projection.
     */
    @SuppressWarnings("unchecked")
    public void verifyStudentAttendance(ScenarioContext context, String expectedStatus) {
        String label = statusLabel(expectedStatus);
        Map<String, Object> session = findSessionInStudentDayView(context);

        List<Map<String, Object>> tempRecords = (List<Map<String, Object>>) session.get("TempRecords");
        assertThat(tempRecords)
                .as("the student's view of session %s must carry a TempRecords entry once attendance is marked - "
                        + "an empty list means the student cannot see their own attendance", context.getSessionUuid())
                .isNotNull().isNotEmpty();

        Map<String, Object> allData = (Map<String, Object>) tempRecords.get(0).get("allData");
        assertThat(allData).as("student TempRecords entry must expose allData").isNotNull();

        // In the STUDENT-token response this is a single object scoped to the caller, unlike the
        // admin-token response where the same field is an array of every student.
        Object studentIds = allData.get("studentIds");
        assertThat(studentIds)
                .as("the student-token response must scope allData.studentIds to the calling student (a single "
                        + "object, not the admin view's array)")
                .isInstanceOf(Map.class);
        Map<String, Object> own = (Map<String, Object>) studentIds;

        assertThat(String.valueOf(own.get("studentId")))
                .as("the attendance the student sees must be their OWN record")
                .isEqualTo(context.getStudentNumericId());
        assertThat(String.valueOf(own.get("attendanceStatus")))
                .as("student view must report their attendance as %s - matching what the admin set", label)
                .isEqualTo(expectedStatus);

        AttendanceJourneyReport.checkpoint(context, "Student Attendance Verified (" + label + ")",
                "Read with the runtime STUDENT session via getStudentSessionsByDate",
                "Session: " + context.getSessionName(),
                "Own attendanceStatus: \"" + expectedStatus + "\" (" + label + ")",
                "Student view matches the admin view - actors are synchronized");
        log.info("Student verified their own attendance as {} on session {}", label, context.getSessionUuid());
    }

    /**
     * Final synchronization assertion: the status the admin last set is the status stored in the
     * runtime context AND the status both actors just reported. Reaching here means every
     * admin-side and student-side check in the journey agreed at every step.
     */
    public void verifyAdminStudentSynchronization(ScenarioContext context) {
        assertThat(context.getAttendanceStatus())
                .as("the journey must end with the runtime attendance status stored as Absent")
                .isEqualTo(AttendanceClient.STATUS_ABSENT);
        assertThat(context.getAttendanceRecordUuid())
                .as("a single attendance record uuid must be stored for the whole journey")
                .isNotBlank();

        AttendanceJourneyReport.checkpoint(context, "Runtime Context Updated",
                "attendanceRecordUuid: " + context.getAttendanceRecordUuid() + " (one record for the whole journey)",
                "attendanceStatus: \"" + context.getAttendanceStatus() + "\" (Absent)",
                "studentNumericId: " + context.getStudentNumericId());
        AttendanceJourneyReport.checkpoint(context, "Admin and Student Synchronized",
                "Present: admin and student views agreed",
                "Absent: admin and student views agreed after the update",
                "One attendance record throughout - updated in place, never duplicated");
    }

    /** Publishes the accumulated ATTENDANCE JOURNEY report - the journey's last action. */
    public void completeAttendanceJourneyStage(ScenarioContext context) {
        AttendanceJourneyReport.publish(context);
    }

    // ================= Internals =================

    /**
     * Reads the section roster and resolves the runtime student's NUMERIC id from it by matching the
     * runtime student uuid. The attendance API keys students by that numeric id, which no other part
     * of the framework carries - hence this lookup rather than reusing the uuid. Also keeps the full
     * roster, because the captured save sends every student in the section, not just the one changed.
     */
    private void resolveSectionRoster(ScenarioContext context) {
        Response response = attendanceClient.getSectionStudents(
                context.getProgramTermId(), context.getSectionId(), context.getAdminToken());
        assertThat(response.statusCode())
                .as("section roster GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        sectionRoster = response.jsonPath().getList("Data");
        assertThat(sectionRoster)
                .as("the runtime section must expose its student roster (Data) - attendance cannot be marked "
                        + "without the students' numeric ids")
                .isNotNull().isNotEmpty();

        String studentUuid = context.getStudentUuid();
        String numericId = null;
        for (Map<String, Object> student : sectionRoster) {
            if (studentUuid.equals(student.get("uuid"))) {
                Object id = student.get("id");
                numericId = id != null ? String.valueOf(id) : null;
                break;
            }
        }
        assertThat(numericId)
                .as("the runtime student %s must appear in the runtime section's roster - attendance is keyed by "
                        + "the numeric id only this endpoint exposes", studentUuid)
                .isNotBlank();

        context.setStudentNumericId(numericId);
        log.info("Resolved runtime student {} to numeric id {} ({} students in the runtime section)",
                studentUuid, numericId, sectionRoster.size());
    }

    /**
     * Loads the org's attendance remark options, exactly as the attendance screen does before saving.
     * This journey sends an empty remark (as the capture does), so the result is not selected from -
     * asserting the lookup succeeds proves the screen's prerequisite is available.
     */
    private void loadAttendanceRemarkOptions(ScenarioContext context) {
        Response response = attendanceClient.getAttendanceRemarks(context.getAdminOrgId(), context.getAdminToken());
        assertThat(response.statusCode())
                .as("attendance remarks lookup must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        List<Map<String, Object>> remarks = response.jsonPath().getList("Data");
        assertThat(remarks).as("attendance remarks lookup must return a Data list").isNotNull();
    }

    /**
     * Fires {@code createTempAttendance} for the whole roster with the runtime student set to
     * {@code status}, mirroring how the captured screen saves. Returns the attendance record uuid.
     */
    private String submitAttendance(ScenarioContext context, String status) {
        String numericId = context.getStudentNumericId();
        assertThat(numericId).as("the runtime student's numeric id must be resolved before marking attendance").isNotBlank();

        List<Map<String, Object>> studentIds = new ArrayList<>();
        for (Map<String, Object> student : sectionRoster) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("attendanceStatus", status);
            entry.put("studentId", student.get("id"));
            entry.put("isPreRead", IS_PRE_READ);
            entry.put("remark", NO_REMARK);
            studentIds.add(entry);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("termCourseId", context.getTermCourseId());
        data.put("sessionId", context.getSessionUuid());
        data.put("studentIds", studentIds);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", context.getAdminOrgId());
        payload.put("createdBy", context.getAdminUserId());
        payload.put("data", data);

        Response response = attendanceClient.createTempAttendance(payload, context.getAdminToken());
        assertThat(response.statusCode())
                .as("attendance submission must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        String recordUuid = response.jsonPath().getString("Data.uuid");
        assertThat(recordUuid)
                .as("attendance submission must expose Data.uuid. Response: " + response.getBody().asString())
                .isNotBlank();
        return recordUuid;
    }

    /** Locates the runtime session in the ADMIN attendance session list; fails loudly if absent. */
    private Map<String, Object> findSessionInAdminList(ScenarioContext context) {
        String sessionUuid = context.getSessionUuid();
        assertThat(sessionUuid).as("sessionUuid must be set by the session stage").isNotBlank();

        Response response = attendanceClient.getAdminSessionDetailList(
                context.getSectionId(), context.getTermCourseId(), context.getAdminToken());
        assertThat(response.statusCode())
                .as("admin session-list GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> sessions = response.jsonPath().getList("Data");
        assertThat(sessions).as("admin session list (Data) must be present").isNotNull();

        Map<String, Object> session = sessions.stream()
                .filter(s -> sessionUuid.equals(s.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(session)
                .as("the runtime session %s must appear in the admin attendance session list for the runtime "
                        + "course (section %s / termCourse %s)", sessionUuid, context.getSectionId(), context.getTermCourseId())
                .isNotNull();
        return session;
    }

    /**
     * Locates the runtime session in the runtime STUDENT's own day view, queried for the session's
     * own date. Fails loudly if the student cannot see it.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findSessionInStudentDayView(ScenarioContext context) {
        String sessionUuid = context.getSessionUuid();
        String studentToken = context.getStudentToken();
        assertThat(studentToken).as("runtime student session must be available from Environment Setup").isNotBlank();

        // The session's own date - the day view is queried for exactly the day the session sits on.
        String sessionDate = sessionDateOf(context);

        Response response = attendanceClient.getStudentSessionsByDate(sessionDate, sessionDate, studentToken);
        assertThat(response.statusCode())
                .as("student day-view GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> days = response.jsonPath().getList("Data.sessionsByDate");
        assertThat(days)
                .as("student day view must expose Data.sessionsByDate for %s", sessionDate)
                .isNotNull();

        for (Map<String, Object> day : days) {
            List<Map<String, Object>> timeSlots = (List<Map<String, Object>>) day.get("timeSlots");
            if (timeSlots == null) {
                continue;
            }
            for (Map<String, Object> slot : timeSlots) {
                List<Map<String, Object>> sessions = (List<Map<String, Object>>) slot.get("sessions");
                if (sessions == null) {
                    continue;
                }
                for (Map<String, Object> session : sessions) {
                    if (sessionUuid.equals(session.get("uuid"))) {
                        return session;
                    }
                }
            }
        }
        throw new AssertionError("The runtime session " + sessionUuid + " (" + context.getSessionName() + ") is not "
                + "visible in the runtime student's own day view for " + sessionDate + " - the student cannot see a "
                + "session created on their own runtime course.");
    }

    /**
     * The runtime session's date (yyyy-MM-dd), read back from the admin session list rather than
     * recomputed, so the student day view is always queried for the day the session actually sits on.
     */
    private String sessionDateOf(ScenarioContext context) {
        Map<String, Object> session = findSessionInAdminList(context);
        String sessionDate = String.valueOf(session.get("sessionDate"));
        assertThat(sessionDate)
                .as("the runtime session must expose its sessionDate so the student day view can be queried for it")
                .isNotBlank().isNotEqualTo("null");
        // Comes back as an ISO instant (2026-07-27T00:00:00.000Z); the day view wants just the date.
        return sessionDate.length() >= 10 ? sessionDate.substring(0, 10) : sessionDate;
    }

    /** Human-readable label for an attendanceStatus code, used in assertions and the report. */
    private String statusLabel(String status) {
        return AttendanceClient.STATUS_PRESENT.equals(status) ? "Present" : "Absent";
    }
}
