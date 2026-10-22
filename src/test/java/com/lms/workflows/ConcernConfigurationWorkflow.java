package com.lms.workflows;

import com.lms.clients.ConcernClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.ConcernJourneyReport;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration prerequisite for the Concern journey: points the ENTIRE escalation ladder of the
 * target sub-category at the RUNTIME ADMIN, so the runtime concern always routes back to an actor
 * this execution owns.
 *
 * <p><b>Why this exists (runtime isolation).</b> The captured environment's escalation ladder for
 * "Technical Support / General IT Support" assigns four different seeded support users
 * (levels 0-3: Divesh Malhotra, akash sharma, and others). A journey that relied on those users
 * could not assert who a concern was assigned to, could not log in as the assignee, and would break
 * whenever the seed data changed. Re-pointing every level at the runtime admin removes that
 * dependency entirely: the concern is created by the runtime student, assigned to the runtime
 * admin, and escalates to the runtime admin - no external identity is referenced.
 *
 * <p><b>Endpoints</b> (verified from hawkeye-apis_2026-07-31_10-10-10.har):
 * <ul>
 *   <li>Entry 2: {@code POST /api/v2/organization/{orgUuid}/ticketEscalationFlow/bulk} - configures
 *       the ladder. The capture sent ONE flow (level 0); the {@code flows[]} array is variable
 *       length by construction, and the per-flow field shape used here is copied field-for-field
 *       from that entry.</li>
 *   <li>Entries 3/6: {@code GET /api/v2/organization/{orgUuid}/ticketCategory} - the only read-back
 *       path for escalation flows in the capture (no dedicated GET escalation endpoint exists), so
 *       verification walks the taxonomy down to the target sub-category.</li>
 *   <li>Entry 7: {@code GET /api/v1/organization/ticket-assign-to/get-Ticket-user} - confirms the
 *       runtime admin is assignable before the ladder is pointed at it.</li>
 * </ul>
 */
public class ConcernConfigurationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ConcernConfigurationWorkflow.class);

    /** Scenario 1's taxonomy: Technical Support -> General IT Support. */
    public static final String CATEGORY_NAME = "Technical Support";
    public static final String SUBCATEGORY_NAME = "General IT Support";

    /**
     * The escalation levels this workflow configures. The captured ladder for General IT Support
     * spans levels 0-3, so all four are re-pointed at the runtime admin - leaving any level on a
     * seeded user would let a timed escalation hand the concern to an actor outside this run.
     */
    private static final List<Integer> ESCALATION_LEVELS = List.of(0, 1, 2, 3);

    /**
     * Level 0's threshold, copied from the capture ({@code timeThresholdDays:1,
     * timeThresholdHours:0}). Thresholds only govern the backend's TIMED auto-escalation; this
     * journey drives escalation explicitly, so the captured value is reused unchanged rather than
     * tuned - a deliberately long threshold also guarantees no background escalation races the
     * assertions mid-scenario.
     */
    private static final int THRESHOLD_DAYS = 1;
    private static final int THRESHOLD_HOURS = 0;

    private final ConcernClient concernClient;

    private String subCategoryUuid;
    private List<Map<String, Object>> configuredFlows;

    public ConcernConfigurationWorkflow() {
        this.concernClient = new ConcernClient();
    }

    // ================= Stage 1: resolve the target sub-category =================

    /**
     * Resolves "Technical Support / General IT Support" to its runtime uuid from the live taxonomy.
     * The uuid is never hardcoded - the capture's value is an artefact of one environment, and this
     * lookup is what makes the journey portable across environments.
     */
    public void resolveTargetSubCategory(ScenarioContext context) {
        String orgUuid = context.getAdminOrgId();
        String cohortUuid = context.getCohortId();
        String adminToken = context.getAdminToken();

        Response response = concernClient.getTicketCategories(orgUuid, cohortUuid, adminToken);
        assertThat(response.statusCode())
                .as("ticket category GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> categories = response.jsonPath().getList("Data.categories");
        assertThat(categories).as("Data.categories must be present").isNotNull().isNotEmpty();

        Map<String, Object> category = categories.stream()
                .filter(c -> CATEGORY_NAME.equals(c.get("name")))
                .findFirst()
                .orElse(null);
        assertThat(category)
                .as("the '%s' category must exist in the runtime taxonomy", CATEGORY_NAME)
                .isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subCategories = (List<Map<String, Object>>) category.get("subCategories");
        assertThat(subCategories)
                .as("category '%s' must expose subCategories", CATEGORY_NAME).isNotNull().isNotEmpty();

        Map<String, Object> subCategory = subCategories.stream()
                .filter(s -> SUBCATEGORY_NAME.equals(s.get("name")))
                .findFirst()
                .orElse(null);
        assertThat(subCategory)
                .as("the '%s' sub-category must exist under '%s'", SUBCATEGORY_NAME, CATEGORY_NAME)
                .isNotNull();

        subCategoryUuid = String.valueOf(subCategory.get("uuid"));
        assertThat(subCategoryUuid).as("resolved sub-category must expose a uuid").isNotBlank();

        context.setConcernCategoryName(CATEGORY_NAME);
        context.setConcernSubCategoryName(SUBCATEGORY_NAME);
        context.setConcernSubCategoryUuid(subCategoryUuid);

        ConcernJourneyReport.checkpoint(context, "Concern Taxonomy Resolved",
                "Category: " + CATEGORY_NAME,
                "Sub-category: " + SUBCATEGORY_NAME + " (" + subCategoryUuid + ")",
                "Resolved from the live taxonomy - no hardcoded uuid");
        log.info("Resolved concern sub-category {} -> {}", SUBCATEGORY_NAME, subCategoryUuid);
    }

    // ================= Stage 2: confirm the runtime admin is assignable =================

    /**
     * Confirms the runtime admin appears in the assignable-handler list BEFORE the ladder is pointed
     * at it. Without this, a backend that silently drops an unassignable {@code assignTo} would
     * surface much later as a confusing assignment mismatch on the concern itself.
     */
    public void verifyRuntimeAdminIsAssignable(ScenarioContext context) {
        String adminUuid = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        Response response = concernClient.getAssignableTicketUsers(adminToken);
        assertThat(response.statusCode())
                .as("assignable ticket-user GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        List<Map<String, Object>> users = response.jsonPath().getList("Data");
        assertThat(users).as("Data must list assignable ticket users").isNotNull().isNotEmpty();

        boolean adminAssignable = users.stream().anyMatch(u -> adminUuid.equals(u.get("uuid")));
        assertThat(adminAssignable)
                .as("the runtime admin (%s) must be assignable as a concern handler - the whole journey "
                        + "depends on every escalation level routing back to it", adminUuid)
                .isTrue();

        ConcernJourneyReport.checkpoint(context, "Runtime Admin Is Assignable",
                "Admin uuid: " + adminUuid,
                "Confirmed present in get-Ticket-user (userType=Ticket)",
                "Assignable handlers in this org: " + users.size());
        log.info("Runtime admin {} confirmed assignable as concern handler", adminUuid);
    }

    // ================= Stage 3: configure the escalation ladder =================

    /**
     * Points every escalation level at the runtime admin, for the runtime cohort only. Scoping the
     * configuration to {@code programId = runtime cohort uuid} is what keeps this from being a
     * global mutation: other cohorts' ladders are untouched.
     */
    public void configureEscalationToRuntimeAdmin(ScenarioContext context) {
        String orgUuid = context.getAdminOrgId();
        String cohortUuid = context.getCohortId();
        String adminUuid = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        assertThat(subCategoryUuid)
                .as("the target sub-category must be resolved before configuring escalation").isNotBlank();

        Map<String, Object> payload = buildEscalationPayload(subCategoryUuid, cohortUuid, adminUuid);

        Response response = concernClient.configureEscalationFlows(orgUuid, payload, adminToken);
        assertThat(response.statusCode())
                .as("escalation configuration must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        configuredFlows = response.jsonPath().getList("Data");
        assertThat(configuredFlows)
                .as("Data must contain the configured flows").isNotNull()
                .hasSize(ESCALATION_LEVELS.size());

        String message = response.jsonPath().getString("message");
        assertThat(message).as("configuration response message").containsIgnoringCase("configured");

        ConcernJourneyReport.checkpoint(context, "Escalation Configured",
                "Sub-category: " + SUBCATEGORY_NAME + " (" + subCategoryUuid + ")",
                "Cohort scope: " + cohortUuid,
                "Levels configured: " + ESCALATION_LEVELS,
                "Every level assigned to the runtime admin: " + adminUuid,
                "No seeded support user is referenced by this configuration");
        log.info("Configured escalation levels {} -> runtime admin {} for sub-category {}",
                ESCALATION_LEVELS, adminUuid, subCategoryUuid);
    }

    // ================= Stage 4: verify configuration persisted =================

    /**
     * Re-reads the taxonomy and proves EVERY configured level now resolves to the runtime admin.
     * This is the business verification the HTTP 201 alone cannot provide: it asserts per level, on
     * a fresh read, against the assignee's own uuid - so a partially-applied bulk write, or a write
     * that silently kept a seeded assignee on some level, fails here.
     */
    public void verifyEscalationConfiguration(ScenarioContext context) {
        String orgUuid = context.getAdminOrgId();
        String cohortUuid = context.getCohortId();
        String adminUuid = context.getAdminUserId();
        String adminToken = context.getAdminToken();

        Response response = concernClient.getTicketCategories(orgUuid, cohortUuid, adminToken);
        assertThat(response.statusCode()).as("ticket category re-GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> categories = response.jsonPath().getList("Data.categories");
        Map<String, Object> subCategory = categories.stream()
                .filter(c -> CATEGORY_NAME.equals(c.get("name")))
                .flatMap(c -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> subs =
                            (List<Map<String, Object>>) c.get("subCategories");
                    return subs == null ? java.util.stream.Stream.empty() : subs.stream();
                })
                .filter(s -> subCategoryUuid.equals(s.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(subCategory)
                .as("sub-category %s must still be present on re-read", subCategoryUuid).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows = (List<Map<String, Object>>) subCategory.get("escalationFlows");
        assertThat(flows)
                .as("sub-category '%s' must expose escalationFlows after configuration", SUBCATEGORY_NAME)
                .isNotNull().isNotEmpty();

        for (Integer level : ESCALATION_LEVELS) {
            Map<String, Object> flow = flows.stream()
                    .filter(ConcernConfigurationWorkflow::isCohortScoped)
                    .filter(f -> level.equals(toInt(f.get("level"))))
                    .findFirst()
                    .orElse(null);
            assertThat(flow)
                    .as("escalation level %d must exist for '%s' after configuration", level, SUBCATEGORY_NAME)
                    .isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Object> assignee = (Map<String, Object>) flow.get("Assignee");
            assertThat(assignee)
                    .as("escalation level %d must expose its Assignee", level).isNotNull();
            assertThat(assignee.get("uuid"))
                    .as("escalation level %d must route to the RUNTIME ADMIN (%s), not a seeded support user",
                            level, adminUuid)
                    .isEqualTo(adminUuid);
        }

        context.setConcernEscalationConfigured(true);

        ConcernJourneyReport.checkpoint(context, "Configuration Verified",
                "Re-read via GET ticketCategory (independent of the 201 write response)",
                "All " + ESCALATION_LEVELS.size() + " levels resolve to the runtime admin " + adminUuid,
                "Runtime isolation holds - the concern cannot route outside this execution");
        log.info("Verified all escalation levels route to runtime admin {}", adminUuid);
    }

    public void completeConfigurationStage(ScenarioContext context) {
        ConcernJourneyReport.checkpoint(context, "Concern Configuration Complete",
                "The business journey may now begin against this configuration");
    }

    // ================= Internals =================

    /**
     * Mirrors entry 2's payload field-for-field. {@code assignmentMode:"single"} with a single-entry
     * {@code assignToPool} and empty {@code fallbackAssignees} is the captured shape;
     * {@code hostelIds} is sent empty because General IT Support declares no hostel scoping
     * ({@code hostelIds:[]} on the sub-category), unlike the capture's Hostel sub-category.
     */
    private Map<String, Object> buildEscalationPayload(String subCategoryId, String cohortUuid, String adminUuid) {
        List<Map<String, Object>> flows = new ArrayList<>();
        for (Integer level : ESCALATION_LEVELS) {
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("level", level);
            flow.put("assignTo", adminUuid);
            flow.put("assignmentMode", "single");
            flow.put("assignToPool", List.of(adminUuid));
            flow.put("fallbackAssignees", new ArrayList<>());
            flow.put("timeThresholdDays", THRESHOLD_DAYS);
            flow.put("timeThresholdHours", THRESHOLD_HOURS);
            flow.put("description", level == 0
                    ? "Assigned immediately upon concern creation"
                    : "Escalation level " + level);
            flow.put("isActive", true);
            flows.add(flow);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subCategoryId", subCategoryId);
        payload.put("programId", cohortUuid);
        payload.put("flows", flows);
        payload.put("hostelIds", new ArrayList<>());
        return payload;
    }

    /**
     * The taxonomy returns flows for every program that has configured this sub-category, so a
     * level can legitimately appear more than once. Only flows carrying a {@code programId} are
     * ours; global (null-program) seeded flows are ignored rather than asserted against.
     */
    private static boolean isCohortScoped(Map<String, Object> flow) {
        return flow.get("programId") != null;
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.valueOf(String.valueOf(value));
    }
}
