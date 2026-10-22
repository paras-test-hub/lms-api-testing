package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * The single client for the Concern (backend name: "reactOut"/"ticket") domain - admin
 * configuration + admin concern handling + student concern participation. All concern operations
 * stay in ONE client since they belong to the same backend domain; only the bearer token passed by
 * the caller differs (admin token for configuration/status/reply, student token for feedback).
 *
 * <p>Endpoints verified from the real HAR capture hawkeye-apis_2026-07-31_10-10-10.har, NOT the
 * OpenAPI spec. Note the backend's inconsistent naming across this domain - it is mirrored exactly
 * rather than normalised:
 * <ul>
 *   <li>{@code react-Out} (capital O) in {@code get-react-Out} / {@code update-react-Out-v2}</li>
 *   <li>{@code react-out} (lowercase) in {@code react-out-message-read}</li>
 *   <li>{@code reactOut} (camelCase) in {@code users/reactOut/{uuid}/feedBack}</li>
 *   <li>{@code getSingleTicketData} uses "ticket" vocabulary for the same resource</li>
 * </ul>
 *
 * <p>Configuration endpoints live on {@code /api/v2}; the concern lifecycle endpoints live on
 * {@code /api/v1}. This split is the backend's, and is mirrored here.
 *
 * <p><b>Deliberately absent:</b> concern CREATION. No create endpoint appears anywhere in this
 * capture (the concern under test already existed when recording started), nor in any of the 19
 * other HAR captures in this project. It is therefore not modelled here - inventing a URL would
 * violate the framework's "HAR is authoritative, never invent APIs" rule. See
 * {@code ConcernWorkflow} for the gap this leaves in the journey.
 *
 * <p>Returns the raw Response; the caller (workflow) builds payloads and verifies.
 */
public class ConcernClient {

    private final RestClient restClient;

    public ConcernClient() {
        this.restClient = new RestClient();
    }

    // ================= Admin: escalation configuration (/api/v2) =================

    /**
     * POST /api/v2/organization/{orgUuid}/ticketEscalationFlow/bulk - configures the escalation
     * ladder for one sub-category within one program (the backend's {@code programId} field carries
     * the COHORT uuid, cross-confirmed against the cohort uuid in the capture's route parameters).
     *
     * <p>Verified request shape: {@code { subCategoryId, programId, flows:[ { level, assignTo,
     * assignmentMode, assignToPool[], fallbackAssignees[], timeThresholdDays, timeThresholdHours,
     * description, isActive } ], hostelIds:[] } }.
     *
     * <p>Verified response: 201 with {@code { IsSuccess, Data:[ { uuid, subCategoryId, level,
     * assignTo, programId, isActive, description, ... } ], message: "Escalation flows configured
     * successfully" } }. Note the response echoes {@code assignTo} as the assignee's NUMERIC id and
     * folds the pool/fallback uuids into a {@code [FLOW_META]{...}} JSON suffix on {@code description}.
     */
    public Response configureEscalationFlows(String organizationUuid, Map<String, Object> payload, String bearerToken) {
        return restClient.post(
                TestConfig.API_V2 + "/organization/" + organizationUuid + "/ticketEscalationFlow/bulk",
                new HashMap<>(payload), bearerToken);
    }

    /**
     * GET /api/v2/organization/{orgUuid}/ticketCategory?programId={cohortUuid}&amp;isStudentCategory=true
     * - the student-facing concern taxonomy, and the read-back path for escalation configuration.
     *
     * <p>Verified response: {@code { IsSuccess, Data:{ categories:[ { uuid, name, shortName, order,
     * subCategories:[ { uuid, name, shortName, requiredFields[], dropdownFields[], hideFields{},
     * suggestions[], escalationFlows:[ { uuid, level, assignTo, timeThresholdDays,
     * timeThresholdHours, subCategoryId, programId, Assignee:{uuid, firstName, lastName,
     * officialEmail} } ] } ] } ] } }.
     *
     * <p>This is the ONLY read-back path for escalation flows in the capture - there is no
     * dedicated {@code GET ticketEscalationFlow} endpoint - so configuration verification walks
     * this taxonomy down to the target sub-category's {@code escalationFlows[]}.
     */
    public Response getTicketCategories(String organizationUuid, String cohortUuid, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("programId", cohortUuid);
        queryParams.put("isStudentCategory", true);
        return restClient.get(
                TestConfig.API_V2 + "/organization/" + organizationUuid + "/ticketCategory",
                queryParams, bearerToken);
    }

    /**
     * GET /api/v1/organization/ticket-assign-to/get-Ticket-user?searchParam=&amp;pageSize=200&amp;
     * pageNo=1&amp;userType=Ticket - the users assignable as concern handlers. Used to confirm the
     * runtime admin is itself assignable BEFORE the escalation ladder is pointed at it, so a
     * configuration failure is attributable rather than silent.
     *
     * <p>Verified response: {@code { IsSuccess, Data:[ { id, uuid, firstName, lastName,
     * officialEmail, designation, department, UserRoles:[{ Role:{ roleName } }] } ] } }.
     */
    public Response getAssignableTicketUsers(String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", 200);
        queryParams.put("pageNo", 1);
        queryParams.put("userType", "Ticket");
        return restClient.get(
                TestConfig.API_V1 + "/organization/ticket-assign-to/get-Ticket-user",
                queryParams, bearerToken);
    }

    // ================= Concern lifecycle (/api/v1) =================

    /**
     * GET /api/v1/organization/{orgUuid}/getSingleTicketData/{concernUuid} - the full concern
     * record. Readable by BOTH actors (verified with the admin token at entries 22/29/51/83/91 and
     * with the student token at entries 48/70/73/113/116), which makes it the single verification
     * surface for both sides of the journey.
     *
     * <p>Verified response: {@code { IsSuccess, Data:{ uuid, ticketNumber, status, title,
     * description, chatMessage:[...]|null, ticketAssignTo, ticketAssignToJson:[...],
     * currentEscalationLevel, escalationFlowId, escalationFlowjson:[...], nextEscalationAt,
     * categoryId, TicketSubCategory:{ name, category:{ name } }, User:{...}, Creator:{...},
     * Program:{...}, studentName, createdBy, ... } }.
     */
    public Response getConcern(String organizationUuid, String concernUuid, String bearerToken) {
        return restClient.get(
                TestConfig.API_V1 + "/organization/" + organizationUuid + "/getSingleTicketData/" + concernUuid,
                bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgUuid}/get-react-Out - the admin's concern list. Verified query
     * params from the capture: {@code pageSize=100, pageNo=1, searchParam=, status=all,
     * assignToFilter=} (plus further filter params left empty by the UI).
     */
    public Response getConcerns(String organizationUuid, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("pageSize", 100);
        queryParams.put("pageNo", 1);
        queryParams.put("searchParam", "");
        queryParams.put("status", "all");
        queryParams.put("assignToFilter", "");
        return restClient.get(
                TestConfig.API_V1 + "/organization/" + organizationUuid + "/get-react-Out",
                queryParams, bearerToken);
    }

    /**
     * PATCH /api/v1/organization/update-react-Out-v2/{concernUuid} - the admin's single mutation
     * endpoint for a concern. It serves BOTH "post a reply" and "change status", differentiated
     * only by which fields the payload carries:
     * <ul>
     *   <li>Reply + status (entry 28): {@code { adminFirstResponseEmail, isAdmin:true,
     *       messageType:"admin", message, fileData:null, status:"Pending" } }</li>
     *   <li>Status only (entries 50, 90): {@code { status:"Resolved", isAdmin:true,
     *       isWaitingForResponce:false, isCalendarPaused:false } }</li>
     * </ul>
     * Verified response: {@code { IsSuccess, Data:{ uuid, chatMessage:[...], status, ... },
     * message } } - the updated concern, so the caller can assert on the same response.
     */
    public Response updateConcern(String concernUuid, Map<String, Object> payload, String bearerToken) {
        return restClient.patch(
                TestConfig.API_V1 + "/organization/update-react-Out-v2/" + concernUuid,
                new HashMap<>(payload), bearerToken);
    }

    /**
     * PATCH /api/v1/organization/react-out-message-read/{concernUuid} with {@code { isAdmin }} -
     * marks the conversation read for one side. Verified response: {@code { IsSuccess, Data:{
     * isAdminRead, isUserRead, uuid, ticketAssignToJson:[...], chatMessage } , message } }.
     */
    public Response markConcernRead(String concernUuid, boolean isAdmin, String bearerToken) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("isAdmin", isAdmin);
        return restClient.patch(
                TestConfig.API_V1 + "/organization/react-out-message-read/" + concernUuid,
                payload, bearerToken);
    }

    /**
     * POST /api/v1/users/reactOut/{concernUuid}/feedBack - the STUDENT's only write operation on a
     * concern in this capture. There is no student chat-message endpoint; the student participates
     * by accepting or rejecting the admin's resolution, and the submission is appended to the
     * concern's {@code chatMessage[]} as a {@code messageType:"feedback"} entry.
     *
     * <p>Verified request shapes:
     * <ul>
     *   <li>Rejection (entry 72): {@code { userFeedBackMessage, issueResolved:"no" } }
     *       - drives the concern to status {@code "Escalate"}</li>
     *   <li>Acceptance (entry 115): {@code { rating:4, userFeedBackMessage, issueResolved:"yes" } }
     *       - drives the concern to status {@code "Closed"}</li>
     * </ul>
     * {@code issueResolved} is sent as the STRING "yes"/"no" and stored as a boolean.
     * {@code rating} appears only on the accepting call. Verified response: {@code { IsSuccess,
     * Data:{ uuid, userFeedBackMessage, issueResolved, reactOutId, userId, rating, ... },
     * message: "Thanks For FeedBack" } }.
     */
    public Response submitConcernFeedback(String concernUuid, Map<String, Object> payload, String bearerToken) {
        return restClient.post(
                TestConfig.API_V1 + "/users/reactOut/" + concernUuid + "/feedBack",
                new HashMap<>(payload), bearerToken);
    }
}
