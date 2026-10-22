package com.lms.clients.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin "program" tag - Capstone gamification configuration and rewards subset, covering the
 * two-step chain the admin UI performs when a capstone course is gamified:
 * createCapstoneGamification (the whole configuration in one shot) -> createGamificationReward
 * (one call per reward) -> getGamificationRewards (read-back). Part of the Capstone journey, not
 * a standalone per-tag CRUD client.
 *
 * <p>Paths, REQUEST shapes and RESPONSE shapes verified directly from a HAR capture of the admin
 * capstone flow - NOT the OpenAPI spec, whose request bodies are unreliable (see
 * ProgramMasterClient's Javadoc for the same finding on a sibling endpoint, and CohortClient for
 * the same HAR-first approach on the rest of the /program tag).
 *
 * <p>Scope note: request shaping and serialisation only. No assertions, no orchestration, no
 * business logic - the caller owns payload construction and all verification.
 */
public class CapstoneClient {

    /**
     * Gson configured with {@code serializeNulls()} because the HAR-proven contract for these
     * endpoints contains EXPLICIT nulls that must be transmitted, not omitted: {@code timezone:null}
     * in the configuration payload, and {@code fileUpload:null}, {@code totalClaimLimit:null},
     * {@code perTeamClaimLimit:null} plus {@code file:null} (inside the resources folderData) in the
     * reward/configuration payloads. Gson's default behaviour drops null-valued entries, which would
     * silently remove those keys and change the contract that was proven against the live API.
     *
     * <p>Serialising here (rather than handing the Map to RestAssured) also preserves the
     * LinkedHashMap key order the callers deliberately build, so the wire body matches the HAR
     * byte-for-byte in ordering as well as in content.
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final RestClient restClient;

    public CapstoneClient() {
        this.restClient = new RestClient();
    }

    private static String programBase() {
        return TestConfig.API_V1 + "/program";
    }

    /**
     * POST /api/v1/program/capstoneGamifications - creates the capstone gamification configuration.
     *
     * <p>HAR-verified: response 201 {@code { IsSuccess, Data:{...},
     * message:"Gamification configuration created successfully" }}.
     *
     * <p>CRITICAL on the two identifiers in {@code Data}: {@code Data.uuid} is the configuration's
     * uuid and {@code Data.id} is a NUMERIC internal id (e.g. 291). They are NOT interchangeable.
     * The rewards API keys off the NUMERIC {@code Data.id} - both as the {@code gamificationConfigId}
     * field in {@link #createGamificationReward}'s payload and as the path segment of
     * {@link #getGamificationRewards}. The uuid is not accepted there.
     *
     * <p>This is an AGGREGATE endpoint: metrics, schedule, dashboardDisplay, analyticsDisplay,
     * xByXRows, xByXColumns, leaderboard, resources and thresholds are ALL persisted in this ONE
     * call. There is no separate per-section endpoint, and the HAR contains no PUT/PATCH/DELETE for
     * this resource - so there is no way to amend a configuration after the fact, and no delete
     * method is offered here.
     *
     * <p>The payload is serialised with {@link #GSON} so the HAR-proven explicit {@code timezone:null}
     * (and the {@code file:null} inside resources folderData) survive onto the wire.
     */
    public Response createCapstoneGamification(Map<String, Object> payload, String bearerToken) {
        return restClient.post(programBase() + "/capstoneGamifications", GSON.toJson(payload), bearerToken);
    }

    /**
     * POST /api/v1/program/gamificationRewards - creates one gamification reward against an existing
     * configuration.
     *
     * <p>HAR-verified: response 201 {@code { IsSuccess, Data:{ uuid, id, gamificationConfigId,
     * rewardName, coinRequiredToClaimReward, description, fileUpload, questions[], totalClaimLimit,
     * perTeamClaimLimit, ... }, message:"Gamification reward created successfully" }}.
     *
     * <p>NOTE: {@code gamificationConfigId} in the payload is the NUMERIC configuration id
     * ({@code Data.id} from {@link #createCapstoneGamification}), never the configuration uuid.
     *
     * <p>NOTE: each {@code questions[]} entry carries a CLIENT-GENERATED {@code questionUuid} - the
     * caller mints it and the server echoes it back unchanged. It is not server-minted reference
     * data, so there is no lookup endpoint to source it from.
     *
     * <p>The payload is serialised with {@link #GSON} so the HAR-proven explicit
     * {@code fileUpload:null}, {@code totalClaimLimit:null} and {@code perTeamClaimLimit:null}
     * survive onto the wire instead of being dropped.
     */
    public Response createGamificationReward(Map<String, Object> payload, String bearerToken) {
        return restClient.post(programBase() + "/gamificationRewards", GSON.toJson(payload), bearerToken);
    }

    /**
     * GET /api/v1/program/gamificationRewards/{gamificationConfigId} - the rewards belonging to a
     * configuration. The path segment is the NUMERIC configuration id ({@code Data.id} from
     * {@link #createCapstoneGamification}), never the uuid - hence the {@code int} parameter.
     *
     * <p>HAR-verified: response 200 {@code { IsSuccess, Data:[...],
     * message:"Gamification rewards fetched successfully" }}, where {@code Data} IS the rewards array
     * itself - there is no nested {@code rewards}/{@code items} key to unwrap.
     *
     * <p>NOTE: before any reward exists the call still succeeds with 200 and {@code Data} as an EMPTY
     * array, so an empty result is not an error condition.
     */
    public Response getGamificationRewards(int gamificationConfigId, String bearerToken) {
        return restClient.get(programBase() + "/gamificationRewards/" + gamificationConfigId, bearerToken);
    }

    /**
     * POST /api/v1/program/addStudentsInGroup - creates ONE capstone team (a {@code sectionGroup}) and
     * attaches its members in the same call. There is no separate "create team" then "add member" pair;
     * the members travel in the team payload itself.
     *
     * <p>HAR-verified: response 201, where {@code Data} is an ARRAY of the created teams - the single
     * created team is {@code Data[0]}, not {@code Data} itself. {@code Data[0].uuid} is the team
     * identity and {@code Data[0].gamificationConfigId} is the NUMERIC configuration id the team
     * inherited (see {@link #createCapstoneGamification} on the numeric-vs-uuid distinction).
     *
     * <p>The payload is serialised with {@link #GSON} and handed to {@link RestClient} as a JSON
     * String, exactly as the two calls above: that preserves the caller's deliberate
     * {@code LinkedHashMap} key order on the wire and keeps any explicit nulls, whereas passing the
     * raw Map would let RestAssured's own serialiser reorder the keys and drop null-valued entries.
     */
    public Response addStudentsInGroup(Map<String, Object> payload, String bearerToken) {
        return restClient.post(programBase() + "/addStudentsInGroup", GSON.toJson(payload), bearerToken);
    }

    /**
     * GET /api/v1/program/termCourse/{termCourseId}/section/{sectionId}/getAllSectionGroups - the
     * admin-side listing of every team in one section of one term course.
     *
     * <p>HAR-verified: response 200 {@code { IsSuccess, Data:[...], Count, message }}, where
     * {@code Data} IS the teams array and {@code Count} is the number of TEAMS in the section - not a
     * membership count. Each team's members hang off {@code Data[i].UserGroups}.
     *
     * <p>The pagination trio {@code searchParam=&pageSize=10000&pageNo=1} is sent VERBATIM as captured:
     * these parameters are required rather than optional on the sibling {@code /program} reads (see
     * {@link CohortClient#listProgramTerms} and {@link ProgramMasterClient#list}), so omitting any one
     * of them is not a safe simplification.
     *
     * <p>Both identifiers are ordinary String parameters supplied by the caller; this client reads no
     * context, so it cannot address the runtime section by accident.
     */
    public Response getAllSectionGroups(String termCourseId, String sectionId, String bearerToken) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("searchParam", "");
        queryParams.put("pageSize", 10000);
        queryParams.put("pageNo", 1);
        return restClient.get(
                programBase() + "/termCourse/" + termCourseId + "/section/" + sectionId + "/getAllSectionGroups",
                queryParams, bearerToken);
    }
}
