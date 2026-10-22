package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single client for the entire attendance domain - the section roster that supplies the numeric
 * student ids attendance is keyed by, the mark/update call, and both the admin and student read-back
 * paths. All attendance operations stay in ONE client rather than being split by actor, mirroring
 * {@link AssignmentClient} and {@link SessionClient}; only the bearer token passed by the caller
 * differs (admin token to mark and verify, student token for the student-side verification).
 *
 * <p>Endpoints verified from the real HAR capture hawkeye-apis_2026-07-27_17-18-13.har - the full
 * "admin marks attendance, student sees it, admin flips it, student sees the change" flow.
 *
 * <p><b>Attendance status codes</b> (sent and returned as STRINGS, not booleans/ints):
 * {@code "1"} = Present, {@code "0"} = Absent.
 *
 * <p>Returns the raw Response; the caller ({@code AttendanceWorkflow}) builds payloads and verifies.
 */
public class AttendanceClient {

    /** attendanceStatus value for Present, as sent and returned by the API. */
    public static final String STATUS_PRESENT = "1";

    /** attendanceStatus value for Absent, as sent and returned by the API. */
    public static final String STATUS_ABSENT = "0";

    private final RestClient restClient;

    public AttendanceClient() {
        this.restClient = new RestClient();
    }

    // ---- Admin: roster + lookups the attendance screen depends on ----

    /**
     * GET .../program/programTerm/{programTermId}/section/{sectionId}/getSectionStudents - the
     * section roster the attendance screen renders. Critically, this is the ONLY source of each
     * student's NUMERIC {@code id} - {@link #createTempAttendance} keys students by that numeric id,
     * not by the uuid the rest of the framework carries, so the runtime student must be matched here
     * by uuid to obtain it. Verified response: {@code { IsSuccess, Data:[ { uuid, firstName, lastName,
     * officialEmail, id, userStatus, UserSections:[{ uuid, programTermId, Section:{uuid, name} }] } ],
     * Count, message }}.
     */
    public Response getSectionStudents(String programTermId, String sectionId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", "");
        queryParams.put("pageNo", "");
        return restClient.get(
                TestConfig.API_V1 + "/program/programTerm/" + programTermId + "/section/" + sectionId
                        + "/getSectionStudents", queryParams, bearerToken);
    }

    /**
     * GET .../organization/{organizationId}/attendanceRemarks - the org's configured attendance
     * remark options, loaded by the attendance screen to populate its remark dropdown. Verified
     * response: {@code { IsSuccess, Data:[ { id, name } ], message }}. This journey sends an empty
     * remark (as the capture does), so the lookup is informational - it proves the screen's
     * prerequisite loads, and a future "attendance with remark" journey will select from it by name.
     */
    public Response getAttendanceRemarks(String organizationId, String bearerToken) {
        return restClient.get(
                TestConfig.API_V1 + "/organization/" + organizationId + "/attendanceRemarks", bearerToken);
    }

    // ---- Admin: mark + update attendance ----

    /**
     * POST .../program/createTempAttendance - marks attendance for a session. Despite the "create" in
     * its name this is ALSO the update path: firing it again for the same session OVERWRITES the
     * existing record rather than adding a second one (verified in the capture - the Present call and
     * the later Absent call both returned the SAME {@code Data.uuid} / {@code Data.id}). There is no
     * separate update endpoint.
     *
     * <p>The captured body sends the WHOLE roster in one call, not just the changed student:
     * {@code { organizationId, createdBy, data:{ termCourseId, sessionId, studentIds:[ { attendanceStatus,
     * studentId (NUMERIC), isPreRead, remark } ] } }}. Verified response: 201 with
     * {@code { IsSuccess, Data:{ id, uuid, moduleName:"attendance", allData:{ sessionId (numeric),
     * studentIds:[ { studentId, studentName, studentEmail, attendanceStatus, adminName, createdAt,
     * ... } ] } }, message }}.
     */
    public Response createTempAttendance(Map<String, Object> payload, String bearerToken) {
        return restClient.post(
                TestConfig.API_V1 + "/program/createTempAttendance", new LinkedHashMap<>(payload), bearerToken);
    }

    // ---- Admin: verification ----

    /**
     * GET .../program/session/{sessionUuid}/getTempAttendanceData - the admin read-back for one
     * session's attendance. Verified response: {@code { IsSuccess, Data:{ allData:{ sessionId,
     * studentIds:[ { studentId, studentName, studentEmail, attendanceStatus, remark, isPreRead,
     * adminName, createdAt, ... } ] } }, attendanceLogs, message }} - note {@code studentIds} here is
     * an ARRAY covering every student, unlike the student-token views which expose only the caller.
     */
    public Response getTempAttendanceData(String sessionUuid, String bearerToken) {
        return restClient.get(
                TestConfig.API_V1 + "/program/session/" + sessionUuid + "/getTempAttendanceData", bearerToken);
    }

    /**
     * GET .../program/section/{sectionId}/termCourse/{termCourseId}/sessionDetail/ - the admin's
     * paginated session list for a course, which also carries each session's AGGREGATE attendance
     * counts. Used as a second admin verification: {@code totalPresent}/{@code totalAbsent} are
     * {@code null} before attendance is taken, then flip as it is marked and re-marked (verified in
     * the capture: null/null -> "24"/"0" -> "0"/"24"). Verified response: {@code { IsSuccess,
     * Data:[ { uuid, sessionName, totalPresent, totalAbsent, totalNotApplicableCount, TempRecords:[...],
     * ... } ], Count, courseAttendancePercentage, attendanceType, message }} - note the counts come
     * back as STRINGS. The trailing slash before the query string is part of the captured path.
     */
    public Response getAdminSessionDetailList(String sectionId, String termCourseId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", 10);
        queryParams.put("pageNo", 1);
        return restClient.get(
                TestConfig.API_V1 + "/program/section/" + sectionId + "/termCourse/" + termCourseId
                        + "/sessionDetail/", queryParams, bearerToken);
    }

    // ---- Student: verification ----

    /**
     * GET .../program/sessions/getStudentSessionsByDate?startDate=&endDate= - the student's own
     * day view, called with the STUDENT token. Each session carries a {@code TempRecords} array whose
     * {@code allData.studentIds} is a SINGLE OBJECT scoped to the calling student (not the admin
     * view's array), so {@code TempRecords[0].allData.studentIds.attendanceStatus} is that student's
     * own attendance for the session. Verified response: {@code { IsSuccess, Data:{ sessionsByDate:[
     * { date, timeSlots:[ { date, fromTime, toTime, sessions:[ { uuid, sessionName, TermCourse:{...},
     * TempRecords:[...], ... } ] } ] } ] }, message }}.
     */
    public Response getStudentSessionsByDate(String startDate, String endDate, String studentToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("startDate", startDate);
        queryParams.put("endDate", endDate);
        return restClient.get(
                TestConfig.API_V1 + "/program/sessions/getStudentSessionsByDate", queryParams, studentToken);
    }
}
