package com.lms.workflows;

import com.lms.cleanup.ResourceType;
import com.lms.clients.SessionClient;
import com.lms.clients.admin.CohortClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.SessionJourneyReport;
import com.lms.utils.DateUtils;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Session journey - the second business journey to run
 * against the suite-scoped runtime environment. It consumes the environment
 * {@code EnvironmentSetup} already provisioned (Program -> Cohort -> Program Term -> Section ->
 * Course, plus the authenticated admin) and creates an Offline Session ON the runtime course - it
 * never re-provisions any of that and never depends on the Assignment journey having run.
 *
 * <p>Phase 1 (admin creates an Offline Session): request/response shapes verified from real HAR
 * captures - hawkeye-apis_2026-07-27_11-29-55.har (the create flow: a
 * {@code checkSessionDateAndTime} clash pre-check, then the {@code sessionDetail} PATCH that creates
 * the session, then a {@code getBaseSessions} read-back and a {@code termCourse/{id}} detail GET),
 * hawkeye-apis_2026-07-27_14-56-29.har (the {@code getAllRoomsList} room lookup that populates the
 * form's Location dropdown), and hawkeye-apis_2026-07-27_16-02-28.har (a SECOND, independent
 * read-back path - {@code sessionDetail/sessions/courseSession?termCourseId=...} - used by
 * {@link #verifySessionViaCourseSessionLookup} alongside {@link #verifyRuntimeSession} so the
 * created session is proven correct via two different endpoints, not just one).
 *
 * <p>The session MODE is fixed to {@value #SESSION_MODE} for this journey - there is no modality
 * lookup, because the captured frontend sends {@code sessionMode} as a literal string, not an id
 * resolved from a lookup endpoint. Online/Hybrid sessions are separate future journeys.
 *
 * <p>Each business action is its own method with its own reporting checkpoint and assertions
 * (mirrored 1:1 by a Gherkin step in SessionJourney.feature) rather than one large method - so a
 * failure always points at the exact business action that failed, not a multi-step black box.
 *
 * <p>Instance state ({@link #createSessionPayload}, {@link #locationMatchReason}) is per-scenario:
 * Cucumber's PicoContainer builds a fresh {@code SessionJourneySteps} - and therefore a fresh
 * workflow - for every scenario, so it cannot leak across scenarios. It exists so the verification
 * step can diff the read-back against the EXACT payload that was sent, instead of recomputing
 * expected values (or duplicating raw payload fields into ExecutionContext, which deliberately only
 * holds the runtime entities later phases reuse).
 */
public class SessionWorkflow {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkflow.class);

    /** The only session mode this journey creates. Sent verbatim - not resolved from any lookup. */
    private static final String SESSION_MODE = "offline";

    /** Business rule: the runtime location must be a room (or building) whose name contains this. */
    private static final String LOCATION_KEYWORD = "test";

    private static final String TIMEZONE = "Asia/Kolkata";
    private static final ZoneId SESSION_ZONE = ZoneId.of(TIMEZONE);

    /**
     * The session slot is derived from the CURRENT time, never fixed: it starts
     * {@value #SESSION_STARTS_HOURS_AGO} hours ago and runs for {@value #SESSION_DURATION_HOURS}
     * hour(s), so by the time any journey runs the session has ALREADY FINISHED. That matters for
     * downstream journeys - attendance can only be marked on a session that is over - and it also
     * means two runs never request the identical window, unlike a hardcoded slot. The date is always
     * TODAY, matching the captured create request.
     */
    private static final int SESSION_STARTS_HOURS_AGO = 2;
    private static final int SESSION_DURATION_HOURS = 1;

    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** The captured payload sends start/end as UTC instants with no fraction, e.g. 2026-07-27T05:56:00Z. */
    private static final DateTimeFormatter UTC_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final SessionClient sessionClient;
    private final CohortClient cohortClient;

    /** The exact create payload that was sent - the read-back verification diffs against this. */
    private Map<String, Object> createSessionPayload;

    /** How the runtime location was chosen (room name / building name / fallback) - for reporting. */
    private String locationMatchReason;

    /** True when the selected location genuinely matched the "test" keyword (not the fallback). */
    private boolean locationMatchedKeyword;

    public SessionWorkflow() {
        this.sessionClient = new SessionClient();
        this.cohortClient = new CohortClient();
    }

    // ================= Stage 1: the runtime course this session belongs to =================

    /**
     * Verifies the runtime course is attached to the runtime section and resolves its termCourse id -
     * the id every session endpoint is keyed by. Located FRESH from the section-detail read-back (the
     * same GET the admin UI fires) rather than assuming an earlier journey already resolved it, so the
     * Session journey stays independent of the Assignment journey and of feature execution order.
     * Fails loudly if the runtime course is not attached to the runtime section.
     */
    public void verifyRuntimeCourse(ScenarioContext context) {
        context.execution().requireEnvironmentReady();

        String adminToken = context.getAdminToken();
        String programTermId = context.getProgramTermId();
        String sectionId = context.getSectionId();
        String courseId = context.getCourseId();

        assertThat(context.getAdminOrgId()).as("adminOrgId must be set by admin login").isNotBlank();
        assertThat(context.getAdminUserId()).as("adminUserId must be set by admin login").isNotBlank();
        assertThat(programTermId).as("programTermId must be set by Environment Setup").isNotBlank();
        assertThat(sectionId).as("sectionId must be set by Environment Setup").isNotBlank();
        assertThat(courseId).as("courseId must be set by Environment Setup").isNotBlank();

        Response response = cohortClient.getSectionDetail(programTermId, sectionId, adminToken);
        assertThat(response.statusCode())
                .as("section-detail GET must succeed to locate the runtime course. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> attachedCourses = response.jsonPath().getList("Data.data");
        assertThat(attachedCourses)
                .as("section detail must expose its attached-course list (Data.data)")
                .isNotNull();

        String termCourseId = null;
        for (Map<String, Object> entry : attachedCourses) {
            Object courseMaster = entry.get("CourseMaster");
            if (courseMaster instanceof Map && courseId.equals(((Map<?, ?>) courseMaster).get("uuid"))) {
                Object uuid = entry.get("uuid");
                termCourseId = uuid != null ? uuid.toString() : null;
                break;
            }
        }
        assertThat(termCourseId)
                .as("the runtime course %s must be attached to section %s (found via section-detail GET) so a "
                        + "session can be created on it", courseId, sectionId)
                .isNotBlank();

        context.setTermCourseId(termCourseId);

        SessionJourneyReport.checkpoint(context, "Runtime Course Verified",
                "Course: " + context.getCourseName(), "TermCourse UUID: " + termCourseId,
                "Section: " + sectionId, "Verified attached via section-detail GET");
        log.info("Session journey will use runtime termCourse {} (course {} in section {})",
                termCourseId, courseId, sectionId);
    }

    // ================= Stage 2: the runtime location =================

    /**
     * Retrieves every room configured for the organization and selects the runtime location by the
     * business rule, never by a hardcoded uuid:
     * <ol>
     *   <li>the first room whose {@code roomName} contains "test" (case-insensitive), else</li>
     *   <li>the first room whose {@code BiometricBuilding.buildingName} contains "test", else</li>
     *   <li>the first room in the list (explicit fallback - reported and logged as such, never
     *       silently presented as a "test" match).</li>
     * </ol>
     * Fails loudly if the lookup fails or returns no rooms at all - there is then no location to
     * create an offline session in.
     */
    @SuppressWarnings("unchecked")
    public void selectRuntimeTestLocation(ScenarioContext context) {
        String orgId = context.getAdminOrgId();
        String adminToken = context.getAdminToken();

        Response response = sessionClient.getAllRooms(orgId, adminToken);
        assertThat(response.statusCode())
                .as("room lookup (getAllRoomsList) must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> rooms = response.jsonPath().getList("Data");
        assertThat(rooms)
                .as("getAllRoomsList must return a Data list of rooms for organization %s - with no rooms there "
                        + "is no location an offline session can be created in", orgId)
                .isNotNull().isNotEmpty();

        SessionJourneyReport.checkpoint(context, "Runtime Location Retrieved",
                "GET attendance/" + orgId + "/getAllRoomsList",
                "Rooms available: " + rooms.size());

        Map<String, Object> selected = firstRoomMatching(rooms, true);
        if (selected != null) {
            locationMatchedKeyword = true;
            locationMatchReason = "roomName contains '" + LOCATION_KEYWORD + "'";
        } else {
            selected = firstRoomMatching(rooms, false);
            if (selected != null) {
                locationMatchedKeyword = true;
                locationMatchReason = "BiometricBuilding.buildingName contains '" + LOCATION_KEYWORD + "'";
            } else {
                selected = rooms.get(0);
                locationMatchedKeyword = false;
                locationMatchReason = "no room or building name contained '" + LOCATION_KEYWORD
                        + "' - fell back to the first room returned by the lookup";
                log.warn("No runtime location matching '{}' was returned by the room lookup - falling back to the "
                        + "first room in the list ({}).", LOCATION_KEYWORD, selected.get("roomName"));
            }
        }

        Object locationUuid = selected.get("uuid");
        Object locationName = selected.get("roomName");
        assertThat(locationUuid).as("the selected room must expose a uuid. Room: " + selected).isNotNull();
        assertThat(locationName).as("the selected room must expose a roomName. Room: " + selected).isNotNull();

        String buildingName = "";
        Object building = selected.get("BiometricBuilding");
        if (building instanceof Map) {
            Object name = ((Map<String, Object>) building).get("buildingName");
            buildingName = name != null ? name.toString() : "";
        }

        context.setLocationUuid(locationUuid.toString());
        context.setLocationName(locationName.toString());

        SessionJourneyReport.checkpoint(context, "Test Location Selected",
                "Location: " + locationName, "Location UUID: " + locationUuid,
                "Building: " + buildingName, "Matched by: " + locationMatchReason);
        log.info("Selected runtime location '{}' ({}) - {}", locationName, locationUuid, locationMatchReason);
    }

    /**
     * First room whose room name (when {@code byRoomName}) or building name (otherwise) contains the
     * location keyword, case-insensitively; null when none match.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRoomMatching(List<Map<String, Object>> rooms, boolean byRoomName) {
        for (Map<String, Object> room : rooms) {
            Object candidate;
            if (byRoomName) {
                candidate = room.get("roomName");
            } else {
                Object building = room.get("BiometricBuilding");
                candidate = building instanceof Map ? ((Map<String, Object>) building).get("buildingName") : null;
            }
            if (candidate != null && candidate.toString().toLowerCase(Locale.ROOT).contains(LOCATION_KEYWORD)) {
                return room;
            }
        }
        return null;
    }

    // ================= Stage 3: create the offline session =================

    /**
     * Creates the Offline Session on the runtime course: generates a unique {@code test_<timestamp>}
     * name, derives an ALREADY-FINISHED slot from the current time, runs the frontend's date/time
     * clash pre-check, then fires the {@code sessionDetail} PATCH with an empty {@code sessionId}
     * (the captured create contract). Stores the session identity in ExecutionContext for later
     * phases and registers it for suite cleanup.
     *
     * <p>This is the SINGLE owner of session creation in the framework. Later journeys that need a
     * runtime session (Attendance, and in future QR/OTP/Biometric attendance and Live Session) call
     * this method rather than reimplementing it - see {@code AttendanceWorkflow}.
     */
    public void createOfflineSessionOnRuntimeCourse(ScenarioContext context) {
        String adminToken = context.getAdminToken();
        String adminUserId = context.getAdminUserId();
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();
        String courseMasterId = context.getCourseId();
        String programTermId = context.getProgramTermId();
        String locationUuid = context.getLocationUuid();
        String locationName = context.getLocationName();

        assertThat(termCourseId).as("termCourseId must be resolved by the runtime-course stage").isNotBlank();
        assertThat(locationUuid).as("locationUuid must be selected by the location stage").isNotBlank();

        String sessionName = TestDataFactory.sessionName();

        // An ALREADY-FINISHED slot derived from the current time (never a fixed window): it started
        // SESSION_STARTS_HOURS_AGO hours ago and ran for SESSION_DURATION_HOURS. Minutes are truncated
        // to the whole minute because the API's fromTime/toTime are HH:mm.
        ZonedDateTime nowLocal = ZonedDateTime.now(SESSION_ZONE).withSecond(0).withNano(0);
        ZonedDateTime start = nowLocal.minusHours(SESSION_STARTS_HOURS_AGO);
        ZonedDateTime end = start.plusHours(SESSION_DURATION_HOURS);
        LocalDate sessionDate = start.toLocalDate();
        String sessionDateText = sessionDate.format(LOCAL_DATE);
        String fromTime = start.format(LOCAL_TIME);
        String toTime = end.format(LOCAL_TIME);
        String sessionStartTime = UTC_INSTANT.format(start);
        String sessionEndTime = UTC_INSTANT.format(end);

        SessionJourneyReport.checkpoint(context, "Session Name Generated",
                "Name: " + sessionName, "Format: test_<timestamp> (unique per execution)");

        log.info("==============================\nCreating Offline Session\nCourse: {}\nTermCourse: {}\nSection: {}\n"
                        + "Name: {}\nDate: {} {}-{} ({})\nLocation: {}\n==============================",
                context.getCourseName(), termCourseId, sectionId, sessionName, sessionDateText, fromTime, toTime,
                TIMEZONE, locationName);

        // The frontend's pre-save clash check. Its meetingLink deliberately carries the LOCATION NAME
        // (not a URL) for an offline session - mirrored from the capture rather than "corrected".
        Response clashCheck = sessionClient.checkSessionDateAndTime(
                Collections.singletonList(buildSlotCheckPayload(sessionDateText, fromTime, toTime, termCourseId,
                        courseMasterId, programTermId, locationName)),
                adminToken);
        assertThat(clashCheck.statusCode())
                .as("session date/time pre-check must succeed. Response: " + clashCheck.getBody().asString())
                .isBetween(200, 299);

        boolean slotConflicts = readSlotConflict(clashCheck, context, sessionDateText, fromTime, toTime);

        createSessionPayload = buildCreatePayload(sessionName, sessionDateText, fromTime, toTime, sessionStartTime,
                sessionEndTime, locationUuid, termCourseId, courseMasterId, programTermId,
                resolveSessionZoomAttendance(context), adminUserId, slotConflicts);

        Response createResponse = sessionClient.createSession(sectionId, createSessionPayload, adminToken);
        assertThat(createResponse.statusCode())
                .as("offline session creation must succeed. Response: " + createResponse.getBody().asString())
                .isBetween(200, 299);

        String sessionUuid = createResponse.jsonPath().getString("Data.uuid");
        assertThat(sessionUuid)
                .as("created session must expose Data.uuid. Response: " + createResponse.getBody().asString())
                .isNotBlank();

        context.setSessionUuid(sessionUuid);
        context.setSessionName(sessionName);
        context.setSessionMode(SESSION_MODE);

        Map<String, String> deletionContext = new LinkedHashMap<>();
        deletionContext.put("termCourseId", termCourseId);
        deletionContext.put("sectionId", sectionId);
        context.registerCreated(ResourceType.SESSION, sessionUuid, deletionContext);

        SessionJourneyReport.checkpoint(context, "Runtime Offline Session Created",
                "Name: " + sessionName, "UUID: " + sessionUuid, "Mode: " + SESSION_MODE,
                "Location: " + locationName, "On runtime termCourse " + termCourseId);
        SessionJourneyReport.checkpoint(context, "Runtime Context Updated",
                "Stored sessionUuid + sessionName + sessionMode + locationUuid + locationName in ExecutionContext");
        SessionJourneyReport.checkpoint(context, "Cleanup Registered",
                "ResourceType.SESSION [" + sessionUuid + "] (termCourseId + sectionId captured)");

        log.info("==============================\nCreating Offline Session\nName: {}\nUUID: {}\nResult: SUCCESS (created)\n==============================",
                sessionName, sessionUuid);
    }

    /**
     * The org-level attendance threshold the session form pre-fills into {@code sessionZoomAttendance}
     * - read from the lookup the frontend fires rather than hardcoded, since it is org configuration.
     * Falls back to the payload default only if the lookup answers without a numeric value.
     */
    private int resolveSessionZoomAttendance(ScenarioContext context) {
        Response response = sessionClient.getZoomAttendancePercentage(context.getAdminOrgId(), context.getAdminToken());
        assertThat(response.statusCode())
                .as("zoom attendance-percentage lookup must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        Integer percentage = response.jsonPath().getObject("Data", Integer.class);
        assertThat(percentage)
                .as("getZoomAttendancePercentage must expose a numeric Data value (the session form pre-fills it "
                        + "into sessionZoomAttendance). Response: " + response.getBody().asString())
                .isNotNull();
        return percentage;
    }

    /**
     * Reads the clash pre-check result and reports it, returning whether the slot conflicts.
     *
     * <p>The endpoint answers with TWO different {@code Data} shapes, both captured:
     * <ul>
     *   <li><b>no conflict</b> - {@code Data: []} (an empty ARRAY)</li>
     *   <li><b>conflict</b> - {@code Data: { allSessionsName:[{sessionDetail, fromTime, toTime,
     *       roomName, ...}], sessionExist:true, courseIds:[...] }} (an OBJECT)</li>
     * </ul>
     *
     * <p>A conflict is deliberately NOT a failure and does NOT trigger a retry with a different slot:
     * the captured frontend warns the user and, on confirmation, creates the session anyway with
     * {@code isConfirmed:true} (the no-conflict capture used {@code isConfirmed:false}). Generating an
     * alternative slot would be automation-only business logic the frontend never performs, so this
     * mirrors the confirm-and-proceed path instead and records the conflict in the journey report.
     */
    private boolean readSlotConflict(Response clashCheck, ScenarioContext context, String sessionDate,
                                     String fromTime, String toTime) {
        String slot = "Date: " + sessionDate + ", Time: " + fromTime + " - " + toTime + " (" + TIMEZONE + ")";

        // Data is an OBJECT only when the slot conflicts; on a free slot it is an EMPTY ARRAY. Read it
        // as a raw Object and branch on its actual type FIRST - asking Gson to deserialize straight
        // into a Boolean (e.g. getObject("Data.sessionExist", Boolean.class)) throws
        // JsonSyntaxException on the free-slot response, because GPath resolves that path against an
        // empty array as an empty LIST (auto-spread across elements), not null - Gson then chokes
        // trying to read that list as a boolean. Verified against the free-slot response captured in
        // hawkeye-apis_2026-07-28_12-36-05.har ({@code Data: []}).
        Object data = clashCheck.jsonPath().get("Data");
        boolean sessionExist = data instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) data).get("sessionExist"));

        if (!sessionExist) {
            SessionJourneyReport.checkpoint(context, "Session Slot Verified", slot,
                    "No conflict reported by checkSessionDateAndTime", "Creating with isConfirmed=false");
            return false;
        }

        List<String> conflicting = clashCheck.jsonPath().getList("Data.allSessionsName.sessionDetail", String.class);
        String conflictSummary = conflicting == null || conflicting.isEmpty()
                ? "(the API reported a conflict without naming the sessions)"
                : String.join("; ", conflicting);

        SessionJourneyReport.checkpoint(context, "Session Slot Conflict Reported", slot,
                "checkSessionDateAndTime reported sessionExist=true",
                "Conflicting session(s): " + conflictSummary,
                "Proceeding with isConfirmed=true - the same confirm-and-create path the frontend takes");
        log.warn("Session slot {} conflicts with existing session(s): {} - proceeding with isConfirmed=true, "
                + "mirroring the frontend's confirm-and-create behaviour.", slot, conflictSummary);
        return true;
    }

    /**
     * The clash pre-check body (one slot). Mirrors the captured request field-for-field, including
     * {@code sessionId:""} (create, not update) and the offline quirk that {@code meetingLink} carries
     * the location's display name rather than a URL.
     */
    private Map<String, Object> buildSlotCheckPayload(String sessionDate, String fromTime, String toTime,
                                                      String termCourseId, String courseMasterId,
                                                      String programTermId, String locationName) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("sessionDate", sessionDate);
        slot.put("fromTime", fromTime);
        slot.put("toTime", toTime);
        slot.put("termCourseId", termCourseId);
        slot.put("courseMasterId", courseMasterId);
        slot.put("programTermId", programTermId);
        slot.put("meetingLink", locationName);
        slot.put("sessionId", "");
        return slot;
    }

    /**
     * Builds the create payload, mirroring the real frontend request field-for-field (runtime values +
     * the captured defaults). Two deliberate, documented reductions - neither invents a field or
     * changes a shape:
     * <ul>
     *   <li>{@code outClassUploadMaterialMultiple} is empty: the captured entry is a real file uploaded
     *       through a genuine third-party Filestack multipart flow (filestackapi.com), which this
     *       framework does not replicate - the same decision, for the same reason, as
     *       {@code AssignmentClient#submitAssignment}. {@code PostClassMaterialIsChecked} is therefore
     *       false, keeping the resource flags consistent with what is actually sent.</li>
     *   <li>{@code preReadJson} carries LINK-only entries ({@code isUpload:false}), exactly like two of
     *       the three captured entries - no file upload involved, so these round-trip faithfully.</li>
     * </ul>
     */
    private Map<String, Object> buildCreatePayload(String sessionName, String sessionDate, String fromTime,
                                                   String toTime, String sessionStartTime, String sessionEndTime,
                                                   String locationUuid, String termCourseId, String courseMasterId,
                                                   String programTermId, int sessionZoomAttendance, String adminUserId,
                                                   boolean slotConflicts) {
        Map<String, Object> sessionResources = new LinkedHashMap<>();
        sessionResources.put("PreReadMaterialIsChecked", true);
        sessionResources.put("InClassMaterialIsChecked", false);
        sessionResources.put("PostClassMaterialIsChecked", false);

        Map<String, Object> preReadLink = new LinkedHashMap<>();
        preReadLink.put("preReadLink", TestDataFactory.assignmentSubmissionDriveLink());
        preReadLink.put("fileName", "");
        preReadLink.put("key", "");
        preReadLink.put("isUpload", false);
        preReadLink.put("title", "Runtime pre-read link");

        Map<String, Object> subTopic = new LinkedHashMap<>();
        subTopic.put("topic", "Topic 1");
        subTopic.put("heading", "Runtime session topic");
        subTopic.put("pedagogy", "software");
        subTopic.put("topicOrder", 1);
        subTopic.put("subTopicOrder", 1);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionName", sessionName);
        payload.put("description", TestDataFactory.sessionDescriptionHtml());
        payload.put("sessionDate", sessionDate);
        payload.put("meetingLink", "");
        payload.put("sessionTopic", "");
        payload.put("fromTime", fromTime);
        payload.put("toTime", toTime);
        payload.put("isDraft", false);
        payload.put("preReadHeading", "Runtime pre-read material");
        payload.put("sessionRecording", "");
        payload.put("sessionVideo", "");
        payload.put("inClassHeading", "");
        payload.put("outClassHeading", "");
        payload.put("zoomData", new LinkedHashMap<String, Object>());
        payload.put("sessionMode", SESSION_MODE);
        payload.put("zoomHost", "No User Available");
        payload.put("sessionZoomAttendance", sessionZoomAttendance);
        payload.put("sessionResources", sessionResources);
        payload.put("transcript", null);
        payload.put("transcriptUrl", null);
        payload.put("transcriptFileName", "");
        payload.put("videoAttendance", false);
        payload.put("isSession", true);
        payload.put("askSessionFeedBack", false);
        payload.put("feedBackAttendanceRequired", false);
        payload.put("roomLocationId", locationUuid);
        payload.put("inClassUploadMaterialMultiple", new ArrayList<>());
        payload.put("outClassUploadMaterialMultiple", new ArrayList<>());
        payload.put("preReadJson", Collections.singletonList(preReadLink));
        payload.put("facultyData", new ArrayList<>());
        payload.put("sessionSubTopic", Collections.singletonList(subTopic));
        payload.put("deviceId", "");
        payload.put("sessionStartTime", sessionStartTime);
        payload.put("sessionEndTime", sessionEndTime);
        payload.put("timezone", TIMEZONE);
        payload.put("termCourseId", termCourseId);
        payload.put("sessionId", "");
        payload.put("courseMasterId", courseMasterId);
        payload.put("programTermId", programTermId);
        // false on a free slot, true when the pre-check reported a conflict - exactly how the captured
        // frontend distinguishes "no warning shown" from "user confirmed past the warning".
        payload.put("isConfirmed", slotConflicts);
        payload.put("createdBy", adminUserId);
        payload.put("updatedBy", adminUserId);
        return payload;
    }

    // ================= Stage 4: verify the session =================

    /**
     * Read-back verification via {@code getBaseSessions} - never trusts the create response alone.
     * Finds the just-created session by uuid in the runtime termCourse/section's own session list
     * (which alone proves it belongs to the runtime course, since the list is scoped to that
     * termCourse + section) and asserts every business field round-tripped against the EXACT payload
     * that was sent: name, mode, date, from/to time, start/end instants, timezone, draft state, and
     * the linked room (uuid + display name).
     */
    @SuppressWarnings("unchecked")
    public void verifyRuntimeSession(ScenarioContext context) {
        String sessionUuid = context.getSessionUuid();
        String termCourseId = context.getTermCourseId();
        String sectionId = context.getSectionId();

        assertThat(sessionUuid).as("sessionUuid must be set by the session-create stage").isNotBlank();
        assertThat(createSessionPayload).as("the create payload must have been captured by the create stage").isNotNull();

        Response response = sessionClient.getBaseSessions(termCourseId, sectionId, context.getAdminToken());
        assertThat(response.statusCode())
                .as("session read-back GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> sessions = response.jsonPath().getList("Data");
        assertThat(sessions).as("session list (Data) must be present for verification").isNotNull();

        Map<String, Object> mine = sessions.stream()
                .filter(s -> sessionUuid.equals(s.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(mine)
                .as("the created session %s must appear in the runtime course's session list (termCourse %s / "
                        + "section %s) - proving it belongs to the runtime course", sessionUuid, termCourseId, sectionId)
                .isNotNull();

        assertThat(mine.get("sessionName")).as("session name must round-trip").isEqualTo(context.getSessionName());
        assertThat(mine.get("sessionMode")).as("session mode must round-trip as '%s'", SESSION_MODE).isEqualTo(SESSION_MODE);
        assertThat(mine.get("fromTime")).as("session fromTime must round-trip").isEqualTo(createSessionPayload.get("fromTime"));
        assertThat(mine.get("toTime")).as("session toTime must round-trip").isEqualTo(createSessionPayload.get("toTime"));
        assertThat(mine.get("timezone")).as("session timezone must round-trip").isEqualTo(TIMEZONE);
        assertThat(mine.get("isDraft")).as("session must not be a draft").isEqualTo(false);
        assertThat(mine.get("isDeleted")).as("session must not be deleted").isEqualTo(false);
        assertThat(mine.get("isSession")).as("the created record must be a session (isSession)").isEqualTo(true);

        LocalDate sentDate = LocalDate.parse(String.valueOf(createSessionPayload.get("sessionDate")));
        assertThat(DateUtils.toCalendarDate(mine.get("sessionDate")))
                .as("session date must round-trip as the same calendar date").isEqualTo(sentDate);
        assertThat(String.valueOf(mine.get("sessionStartTime")))
                .as("session start time must round-trip").startsWith(instantPrefix("sessionStartTime"));
        assertThat(String.valueOf(mine.get("sessionEndTime")))
                .as("session end time must round-trip").startsWith(instantPrefix("sessionEndTime"));

        Object biometricRoom = mine.get("BiometricRoom");
        assertThat(biometricRoom)
                .as("the verified session must expose its BiometricRoom (the location it was created in)")
                .isInstanceOf(Map.class);
        Map<String, Object> room = (Map<String, Object>) biometricRoom;
        assertThat(room.get("uuid"))
                .as("session must be linked to the selected runtime location").isEqualTo(context.getLocationUuid());
        assertThat(room.get("roomName"))
                .as("session's location display name must match the selected room").isEqualTo(context.getLocationName());

        SessionJourneyReport.checkpoint(context, "Session Verified",
                "Read back via getBaseSessions GET (termCourse " + termCourseId + " / section " + sectionId + ")",
                "UUID: " + sessionUuid,
                "Name / mode / date / times / timezone / draft state all match what was sent",
                "Location: " + context.getLocationName() + " (" + context.getLocationUuid() + ")");
        log.info("Session {} verified on runtime termCourse {}", sessionUuid, termCourseId);
    }

    /** The sent UTC instant without its trailing 'Z', so it prefix-matches the response's ...:00.000Z form. */
    private String instantPrefix(String payloadKey) {
        String sent = String.valueOf(createSessionPayload.get(payloadKey));
        return sent.endsWith("Z") ? sent.substring(0, sent.length() - 1) : sent;
    }

    /**
     * A SECOND, independent read-back verification via {@code sessionDetail/sessions/courseSession} -
     * the SAME runtime {@code termCourseId}, but fetched through a completely different endpoint than
     * {@link #verifyRuntimeSession} (unscoped by section, different response shape: lowercase
     * {@code message}/{@code data}, no {@code IsSuccess}). A bug isolated to one read path (e.g. a
     * stale cache behind {@code getBaseSessions}) cannot silently pass if this independent path is
     * also asserted. Re-diffs the same fields against the exact create payload, and additionally
     * cross-checks the session's OWN embedded {@code TermCourse.uuid}/{@code TermCourse.ProgramTerm.uuid}
     * relation - a second, independent proof of the Program Term/Course chain alongside
     * {@link #verifySessionRelationChain}'s.
     */
    @SuppressWarnings("unchecked")
    public void verifySessionViaCourseSessionLookup(ScenarioContext context) {
        String sessionUuid = context.getSessionUuid();
        String termCourseId = context.getTermCourseId();

        assertThat(sessionUuid).as("sessionUuid must be set by the session-create stage").isNotBlank();
        assertThat(createSessionPayload).as("the create payload must have been captured by the create stage").isNotNull();

        Response response = sessionClient.getCourseSessions(termCourseId, context.getAdminToken());
        assertThat(response.statusCode())
                .as("course-session lookup GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> sessions = response.jsonPath().getList("data.session");
        assertThat(sessions).as("course-session lookup (data.session) must be present for verification").isNotNull();

        Map<String, Object> mine = sessions.stream()
                .filter(s -> sessionUuid.equals(s.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(mine)
                .as("the created session %s must appear in the courseSession lookup for termCourse %s - a second, "
                        + "independent verification path", sessionUuid, termCourseId)
                .isNotNull();

        assertThat(mine.get("sessionName")).as("session name must match via courseSession lookup").isEqualTo(context.getSessionName());
        assertThat(mine.get("sessionMode")).as("session mode must match via courseSession lookup").isEqualTo(context.getSessionMode());
        assertThat(mine.get("fromTime")).as("session fromTime must match via courseSession lookup").isEqualTo(createSessionPayload.get("fromTime"));
        assertThat(mine.get("toTime")).as("session toTime must match via courseSession lookup").isEqualTo(createSessionPayload.get("toTime"));
        assertThat(mine.get("isSession")).as("courseSession entry must be a session").isEqualTo(true);

        LocalDate sentDateForLookup = LocalDate.parse(String.valueOf(createSessionPayload.get("sessionDate")));
        assertThat(DateUtils.toCalendarDate(mine.get("sessionDate")))
                .as("session date must match via courseSession lookup").isEqualTo(sentDateForLookup);
        assertThat(String.valueOf(mine.get("sessionStartTime")))
                .as("session start time must match via courseSession lookup").startsWith(instantPrefix("sessionStartTime"));
        assertThat(String.valueOf(mine.get("sessionEndTime")))
                .as("session end time must match via courseSession lookup").startsWith(instantPrefix("sessionEndTime"));

        Object biometricRoom = mine.get("BiometricRoom");
        assertThat(biometricRoom)
                .as("courseSession entry must expose its BiometricRoom (the location it was created in)")
                .isInstanceOf(Map.class);
        Map<String, Object> room = (Map<String, Object>) biometricRoom;
        assertThat(room.get("uuid"))
                .as("courseSession entry must be linked to the selected runtime location").isEqualTo(context.getLocationUuid());
        assertThat(room.get("roomName"))
                .as("courseSession entry's location name must match the selected room").isEqualTo(context.getLocationName());

        Object termCourse = mine.get("TermCourse");
        assertThat(termCourse).as("courseSession entry must expose its own TermCourse relation").isInstanceOf(Map.class);
        Map<String, Object> termCourseMap = (Map<String, Object>) termCourse;
        assertThat(termCourseMap.get("uuid"))
                .as("courseSession entry's TermCourse must match the runtime termCourse").isEqualTo(termCourseId);

        Object programTerm = termCourseMap.get("ProgramTerm");
        assertThat(programTerm).as("courseSession entry's TermCourse must expose its ProgramTerm").isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) programTerm).get("uuid"))
                .as("courseSession entry must belong to the runtime program term")
                .isEqualTo(context.getProgramTermId());

        SessionJourneyReport.checkpoint(context, "Session Verified (Course Session Lookup)",
                "Read back via GET sessionDetail/sessions/courseSession?termCourseId=" + termCourseId,
                "UUID: " + sessionUuid,
                "Name / mode / date / times / location match the first verification",
                "TermCourse + ProgramTerm relation independently confirmed");
        log.info("Session {} independently verified via courseSession lookup on termCourse {}", sessionUuid, termCourseId);
    }

    // ================= Stage 5: cross-verify the runtime relation chain =================

    /**
     * Cross-verifies the complete dependency chain - Program -&gt; Cohort -&gt; Program Term -&gt;
     * Section -&gt; Course -&gt; Offline Session - against the runtime environment, so the session is
     * proven to hang off the runtime entities rather than merely "some" course. A single
     * {@code GET termCourse/{id}} returns the whole chain AND the course's {@code SessionDetails},
     * so this needs no extra endpoint. Fails loudly on any mismatch.
     */
    public void verifySessionRelationChain(ScenarioContext context) {
        String termCourseId = context.getTermCourseId();
        String sessionUuid = context.getSessionUuid();

        Response response = cohortClient.getTermCourseDetail(termCourseId, context.getAdminToken());
        assertThat(response.statusCode())
                .as("termCourse detail GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        String courseMasterUuid = response.jsonPath().getString("Data.termCourse.CourseMaster.uuid");
        String sectionUuid = response.jsonPath().getString("Data.termCourse.Section.uuid");
        String programTermUuid = response.jsonPath().getString("Data.termCourse.ProgramTerm.uuid");
        String cohortUuid = response.jsonPath().getString("Data.termCourse.ProgramTerm.Program.uuid");
        String programUuid = response.jsonPath().getString("Data.termCourse.ProgramTerm.Program.ProgramMaster.uuid");

        assertThat(courseMasterUuid).as("termCourse's CourseMaster must match the runtime course").isEqualTo(context.getCourseId());
        assertThat(sectionUuid).as("termCourse's Section must match the runtime section").isEqualTo(context.getSectionId());
        assertThat(programTermUuid).as("termCourse's ProgramTerm must match the runtime program term").isEqualTo(context.getProgramTermId());
        assertThat(cohortUuid).as("termCourse's Program (cohort) must match the runtime cohort").isEqualTo(context.getCohortId());
        assertThat(programUuid).as("termCourse's ProgramMaster must match the runtime program").isEqualTo(context.getProgramId());

        List<String> sessionUuids = response.jsonPath().getList("Data.termCourse.SessionDetails.uuid");
        assertThat(sessionUuids)
                .as("the runtime course's SessionDetails must contain the created session %s - closing the "
                        + "Program -> Cohort -> Program Term -> Section -> Course -> Session chain", sessionUuid)
                .isNotNull()
                .contains(sessionUuid);

        SessionJourneyReport.checkpoint(context, "Relationship Chain Verified",
                "Program -> Cohort -> Program Term -> Section -> Course -> Session confirmed via GET termCourse/" + termCourseId,
                "Program: " + programUuid, "Cohort: " + cohortUuid, "Program Term: " + programTermUuid,
                "Section: " + sectionUuid, "Course: " + courseMasterUuid, "Session: " + sessionUuid);
        log.info("Session {} cross-verified against the runtime Program/Cohort/ProgramTerm/Section/Course chain", sessionUuid);
    }

    // ================= Stage 6: business rules + stage completion =================

    /**
     * Asserts the journey's own business rules on the stored runtime entities: the session name must
     * carry the {@code test_} prefix, the mode must be offline, and the location must have matched the
     * "test" keyword. The location rule is asserted ONLY when a keyword match was actually found - the
     * explicit first-room fallback is a documented business allowance, and it is reported as a
     * fallback rather than being passed off as a "test" match.
     */
    public void verifySessionBusinessRules(ScenarioContext context) {
        assertThat(context.getSessionName())
                .as("runtime session name must follow the test_<timestamp> business format").startsWith("test_");
        assertThat(context.getSessionMode())
                .as("this journey must create an offline session").isEqualTo(SESSION_MODE);
        assertThat(context.getLocationUuid()).as("runtime locationUuid must be stored").isNotBlank();
        assertThat(context.getLocationName()).as("runtime locationName must be stored").isNotBlank();

        if (locationMatchedKeyword) {
            SessionJourneyReport.checkpoint(context, "Business Rules Verified",
                    "Session name starts with 'test_': " + context.getSessionName(),
                    "Session mode is '" + SESSION_MODE + "'",
                    "Location matched '" + LOCATION_KEYWORD + "' (" + locationMatchReason + ")");
        } else {
            SessionJourneyReport.checkpoint(context, "Business Rules Verified",
                    "Session name starts with 'test_': " + context.getSessionName(),
                    "Session mode is '" + SESSION_MODE + "'",
                    "Location fallback applied - " + locationMatchReason);
        }
    }

    /** Publishes the accumulated SESSION JOURNEY report - the journey's last action. */
    public void completeSessionJourneyStage(ScenarioContext context) {
        SessionJourneyReport.publish(context);
    }
}
