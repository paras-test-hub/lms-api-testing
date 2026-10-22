package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single client for the entire session domain - the room lookup that feeds the session form, the
 * date/time clash pre-check, the create call, and the read-back list used to verify it. All session
 * operations stay in ONE client rather than being split by actor or by sub-feature, mirroring
 * {@link AssignmentClient}'s existing precedent; only the bearer token passed by the caller differs
 * when later phases add the student side.
 *
 * <p>Endpoints verified from real HAR captures, NOT the OpenAPI spec:
 * <ul>
 *   <li>Create + verify (hawkeye-apis_2026-07-27_11-29-55.har): the full "admin adds a session to a
 *       course" flow - {@code POST .../program/sessions/checkSessionDateAndTime}, then
 *       {@code PATCH .../program/section/{sectionId}/sessionDetail}, then
 *       {@code GET .../program/termCourse/{termCourseId}/sectionId/{sectionId}/getBaseSessions}</li>
 *   <li>Room lookup (hawkeye-apis_2026-07-27_14-56-29.har):
 *       {@code GET .../attendance/{organizationId}/getAllRoomsList}</li>
 * </ul>
 *
 * <p>Note both lookups answer <b>201</b> (not 200) on success in the captures - callers must assert a
 * 2xx range, never {@code == 200}.
 *
 * <p>Returns the raw Response; the caller ({@code SessionWorkflow}) builds payloads and verifies.
 */
public class SessionClient {

    private final RestClient restClient;

    public SessionClient() {
        this.restClient = new RestClient();
    }

    // ---- Lookups the session form depends on ----

    /**
     * GET .../attendance/{organizationId}/getAllRoomsList - every room (location) configured for the
     * organization, used to populate the session form's Location dropdown. Verified response:
     * {@code { IsSuccess, Data:[ { roomName, uuid, longitude, latitude, buildingId, rtcp:[...],
     * BiometricBuilding:{ buildingName, uuid, id } } ], message:"Rooms get successfully" }} with HTTP
     * 201. The room's {@code uuid} is what the create payload sends as {@code roomLocationId}; the
     * create response echoes it back as a NUMERIC internal id plus a {@code BiometricRoom:{uuid,
     * roomName}} relation, which is what verification matches on.
     */
    public Response getAllRooms(String organizationId, String bearerToken) {
        return restClient.get(
                TestConfig.API_V1 + "/attendance/" + organizationId + "/getAllRoomsList", bearerToken);
    }

    /**
     * GET .../zoom/{organizationId}/getZoomAttendancePercentage - the org-level attendance threshold
     * the session form pre-fills into {@code sessionZoomAttendance}. Fetched (rather than hardcoded to
     * the captured 51) because it is org configuration, not a constant. The real frontend fires this
     * on the session form for every mode, including offline, so mirroring it costs nothing and keeps
     * the payload free of a hardcoded environment-specific number. Verified response:
     * {@code { IsSuccess, Data: 51, message }}.
     */
    public Response getZoomAttendancePercentage(String organizationId, String bearerToken) {
        return restClient.get(
                TestConfig.API_V1 + "/zoom/" + organizationId + "/getZoomAttendancePercentage", bearerToken);
    }

    // ---- Admin: create + verify a session on a termCourse/section ----

    /**
     * POST .../program/sessions/checkSessionDateAndTime - the clash pre-check the session form fires
     * before saving, validating the requested date/time slot against existing sessions. The body is an
     * ARRAY of slot objects (one per session being saved). Verified response: 201 with
     * {@code { IsSuccess, Data:[], message }} - an EMPTY {@code Data} means "no clash"; entries would
     * describe the conflicting sessions.
     */
    public Response checkSessionDateAndTime(List<Map<String, Object>> slots, String bearerToken) {
        return restClient.post(TestConfig.API_V1 + "/program/sessions/checkSessionDateAndTime", slots, bearerToken);
    }

    /**
     * PATCH .../program/section/{sectionId}/sessionDetail - creates a session on the section. It is a
     * PATCH (not a POST) and the CREATE case is signalled by sending an empty {@code sessionId} in the
     * body - the same endpoint updates an existing session when that field carries a uuid. That is the
     * real frontend's own contract, mirrored here rather than "corrected" to a POST. Verified response:
     * 201 with {@code { IsSuccess, Data:{ uuid, sessionName, sessionMode, roomLocationId (numeric),
     * sessionDate, fromTime, toTime, sessionStartTime, sessionEndTime, isDraft, ... },
     * message:"Session updated" }} - note the message says "updated" even on create.
     */
    public Response createSession(String sectionId, Map<String, Object> payload, String bearerToken) {
        return restClient.patch(
                TestConfig.API_V1 + "/program/section/" + sectionId + "/sessionDetail",
                new LinkedHashMap<>(payload), bearerToken);
    }

    /**
     * GET .../program/termCourse/{termCourseId}/sectionId/{sectionId}/getBaseSessions - the sessions
     * attached to this termCourse/section, used to read a session back after creation. Because the list
     * is scoped to the termCourse AND section, finding a session in it is itself proof the session
     * belongs to the runtime course. Verified response: {@code { IsSuccess, Data:[ { uuid, sessionName,
     * sessionDate, fromTime, toTime, sessionStartTime, sessionEndTime, sessionMode, timezone, isDraft,
     * isDeleted, isSession, sessionNumber, roomLocationId, BiometricRoom:{uuid, roomName},
     * SessionFaculties:[...], Creator:{...}, ... } ], message:"Fetched session details" }}.
     */
    public Response getBaseSessions(String termCourseId, String sectionId, String bearerToken) {
        return restClient.get(
                TestConfig.API_V1 + "/program/termCourse/" + termCourseId + "/sectionId/" + sectionId
                        + "/getBaseSessions", bearerToken);
    }

    /**
     * GET .../program/sessionDetail/sessions/courseSession?termCourseId={termCourseId} - a SECOND,
     * independent read of every session on a termCourse (not scoped by section), verified from
     * hawkeye-apis_2026-07-27_16-02-28.har. Response shape differs from every other session endpoint
     * in this client - lowercase {@code message}/{@code data} (no {@code IsSuccess}), and
     * {@code data.session[]} is the array of sessions, each entry carrying its own embedded
     * {@code TermCourse:{uuid, ProgramTerm:{uuid, renameTerm}}} relation. Used as a second
     * verification path for the just-created session, independent of {@link #getBaseSessions}.
     */
    public Response getCourseSessions(String termCourseId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("termCourseId", termCourseId);
        return restClient.get(
                TestConfig.API_V1 + "/program/sessionDetail/sessions/courseSession", queryParams, bearerToken);
    }
}
