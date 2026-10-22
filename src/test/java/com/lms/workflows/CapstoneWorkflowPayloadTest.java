package com.lms.workflows;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the gamification course-edit body ({@code CapstoneWorkflow#buildTermCourseUpdatePayload}).
 *
 * <p>The update endpoint replaces the whole course-edit surface, so an omitted field is not "unchanged" -
 * it is potentially blanked, and a wrongly-sourced field silently rebinds the course. Those failures are
 * invisible in a 2xx and expensive to spot on a live run, which is why the payload shape is pinned here
 * instead: the builder is pure, so this test needs no HTTP, no context, no credentials and no live
 * environment. It runs under plain {@code mvn test}.
 *
 * <p>What is pinned for S1: the exact key SET (the thirty keys the builder emits - the thirty-first,
 * {@code updatedBy}, is stamped by {@code CohortClient.updateTermCourse}), the key ORDER, the three
 * read-vs-update transforms, the always-on values, and the absence of the sixteen read-back-only fields.
 *
 * <p>The second section of this class pins the S2 CONFIGURATION contract for the same reason: the
 * capstone configuration is one AGGREGATE write, so a mis-shaped slice is persisted silently behind a
 * 2xx. Pinned there: the twenty configuration keys and their order, the explicitly null
 * {@code timezone}, the API's own {@code mounth*} misspellings, the metrics / dashboard / analytics /
 * builder-grid / leaderboard / resource shapes, and the reward body with its runtime-minted question
 * uuids. Every builder involved is pure, so the whole section runs under plain {@code mvn test}.
 */
class CapstoneWorkflowPayloadTest {

    /** The verified wire order. {@code updatedBy} is absent by design - the client stamps it. */
    private static final List<String> EXPECTED_KEYS_IN_ORDER = Arrays.asList(
            "credits",
            "courseModeId",
            "courseTypeId",
            "courseTagId",
            "courseClassId",
            "hours",
            "isAttendanceCalculated",
            "departmentId",
            "evaluationCriteria",
            "courseOverview",
            "description",
            "courseOutcome",
            "workexperienced",
            "readingMaterial",
            "videoLink",
            "topicsJson",
            "courseDomain",
            "placementCourse",
            "subjectWeight",
            "showCourseToStudent",
            "isCourseCalculated",
            "taIds",
            "facultyIds",
            "isGamify",
            "isGamifyDomainWise",
            "attendanceType",
            "coursePurpose",
            "isCreditCalculated",
            "isGpaCalculated",
            "noOfSession");

    /** Read-back-only fields the update must never carry. */
    private static final List<String> FORBIDDEN_KEYS = Arrays.asList(
            "isMandatory",
            "isCourseReview",
            "isDraft",
            "displayOrder",
            "startDate",
            "endDate",
            "sectionId",
            "sessionFormat",
            "status",
            "remarks",
            "moderatedDiscussion",
            "transactionId",
            "isSubjectWeightEnable",
            "AttendanceStats",
            "feedbackSummary",
            "isAttendaceStatsProcessed");

    private static final String DEPARTMENT_UUID = "dept-uuid-from-nps-department";
    private static final Object NUMERIC_DEPARTMENT_ID = 4271;

    @Test
    void payloadCarriesExactlyTheThirtyUpdateKeysInTheVerifiedOrder() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.keySet())
                .as("the gamification update body must carry exactly the thirty builder-emitted keys - updatedBy is "
                        + "stamped by the client, and any extra or missing key changes what the course-edit endpoint "
                        + "overwrites")
                .containsExactlyElementsOf(EXPECTED_KEYS_IN_ORDER);
    }

    @Test
    void payloadPreservesTheWireOrderOfTheCapturedRequest() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload)
                .as("the builder must return an insertion-ordered map so the wire order matches the captured request")
                .isInstanceOf(LinkedHashMap.class);
        assertThat(new java.util.ArrayList<>(payload.keySet()))
                .as("key order must match the verified capture exactly")
                .isEqualTo(EXPECTED_KEYS_IN_ORDER);
    }

    @Test
    void creditsIsSentAsAStringEvenThoughTheReadReturnsANumber() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("credits"))
                .as("credits is numeric on read and a string on update - the transform must happen in the builder")
                .isInstanceOf(String.class)
                .isEqualTo("3");
    }

    @Test
    void nullSubjectWeightIsSentAsAnEmptyString() {
        Map<String, Object> termCourse = termCourseFixture();
        termCourse.put("subjectWeight", null);

        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourse, taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("subjectWeight"))
                .as("a null subjectWeight on read must go out as an empty string, not as null")
                .isEqualTo("");
    }

    @Test
    void departmentIdComesFromTheNestedNpsDepartmentUuidAndNeverFromTheNumericField() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("departmentId"))
                .as("departmentId must be the nested NpsDepartment uuid - the flat numeric departmentId is a "
                        + "different identifier space and would bind the course to the wrong department")
                .isEqualTo(DEPARTMENT_UUID)
                .isNotEqualTo(NUMERIC_DEPARTMENT_ID);
    }

    @Test
    void nestedLookupIdsComeFromTheirAssociationUuids() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("courseModeId")).as("courseModeId must be CourseMode.uuid").isEqualTo("mode-uuid");
        assertThat(payload.get("courseTypeId")).as("courseTypeId must be CourseType.uuid").isEqualTo("type-uuid");
        assertThat(payload.get("courseTagId")).as("courseTagId must be CourseTag.uuid").isEqualTo("tag-uuid");
        assertThat(payload.get("courseClassId")).as("courseClassId must be CourseClass.uuid").isEqualTo("class-uuid");
    }

    @Test
    void courseDomainIsAlwaysAnEmptyList() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("courseDomain"))
                .as("courseDomain is always sent as an empty list - a faithful no-change value for a course with "
                        + "no domains")
                .isInstanceOf(List.class);
        assertThat((List<?>) payload.get("courseDomain"))
                .as("courseDomain must be empty")
                .isEmpty();
        assertThat(payload)
                .as("no TermCourseDomains key is sent on update - only courseDomain")
                .doesNotContainKey("TermCourseDomains");
    }

    @Test
    void isGamifyIsTheSoleValueMutationAndIsAlwaysTrue() {
        Map<String, Object> termCourse = termCourseFixture();
        assertThat(termCourse.get("isGamify"))
                .as("the fixture must start from the pre-update state, gamification off")
                .isEqualTo(false);

        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourse, taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("isGamify"))
                .as("isGamify is the one value the update changes and must always be sent as true")
                .isEqualTo(true);
    }

    @Test
    void taIdsAndFacultyIdsComeFromTheTopLevelResponseFieldsVerbatim() {
        Object taIds = taIdsFixture();
        Object facultyIds = facultyIdsFixture();

        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIds, facultyIds);

        assertThat(payload.get("taIds"))
                .as("taIds is a TOP-LEVEL field of the read response, carried through verbatim")
                .isSameAs(taIds);
        assertThat(payload.get("facultyIds"))
                .as("facultyIds is a TOP-LEVEL field of the read response, carried through verbatim")
                .isSameAs(facultyIds);
    }

    @Test
    void everyCarriedThroughScalarIsVerbatim() {
        Map<String, Object> termCourse = termCourseFixture();

        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourse, taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.get("hours")).as("hours must be verbatim").isEqualTo(30);
        assertThat(payload.get("isAttendanceCalculated")).as("isAttendanceCalculated must be verbatim").isEqualTo(true);
        assertThat(payload.get("evaluationCriteria")).as("evaluationCriteria must be verbatim").isEqualTo("Internal");
        assertThat(payload.get("courseOverview")).as("courseOverview must be verbatim").isEqualTo("Overview text");
        assertThat(payload.get("description")).as("description must be verbatim").isEqualTo("Description text");
        assertThat(payload.get("courseOutcome")).as("courseOutcome must be verbatim").isEqualTo("Outcome text");
        assertThat(payload.get("workexperienced"))
                .as("workexperienced keeps the API's own spelling and value")
                .isEqualTo(false);
        assertThat(payload.get("readingMaterial")).as("readingMaterial must be verbatim").isEqualTo("Reading list");
        assertThat(payload.get("videoLink")).as("videoLink must be verbatim").isEqualTo("");
        assertThat(payload.get("topicsJson")).as("topicsJson must be verbatim").isEqualTo("[]");
        assertThat(payload.get("placementCourse")).as("placementCourse must be verbatim").isEqualTo(false);
        assertThat(payload.get("showCourseToStudent")).as("showCourseToStudent must be verbatim").isEqualTo(true);
        assertThat(payload.get("isCourseCalculated")).as("isCourseCalculated must be verbatim").isEqualTo(true);
        assertThat(payload.get("isGamifyDomainWise")).as("isGamifyDomainWise must be verbatim").isEqualTo(false);
        assertThat(payload.get("attendanceType")).as("attendanceType must be verbatim").isEqualTo("SESSION");
        assertThat(payload.get("coursePurpose")).as("coursePurpose must be verbatim").isEqualTo("REGULAR");
        assertThat(payload.get("isCreditCalculated")).as("isCreditCalculated must be verbatim").isEqualTo(true);
        assertThat(payload.get("isGpaCalculated")).as("isGpaCalculated must be verbatim").isEqualTo(true);
        assertThat(payload.get("noOfSession")).as("noOfSession must be verbatim").isEqualTo(12);
    }

    @Test
    void noneOfTheSixteenReadOnlyFieldsIsSent() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        for (String forbidden : FORBIDDEN_KEYS) {
            assertThat(payload)
                    .as("%s is a read-back-only field and must not appear in the course-edit update body", forbidden)
                    .doesNotContainKey(forbidden);
        }
    }

    @Test
    void noIdentityFieldAndNoNestedAssociationObjectIsSent() {
        Map<String, Object> payload = CapstoneWorkflow.buildTermCourseUpdatePayload(termCourseFixture(), taIdsFixture(),
                facultyIdsFixture());

        assertThat(payload.keySet())
                .as("uuid, createdBy, createdAt and updatedAt are read-only identity/audit fields")
                .doesNotContain("uuid", "createdBy", "createdAt", "updatedAt");
        assertThat(payload.keySet())
                .as("nested association objects are never sent - their *Id uuid equivalents are")
                .doesNotContain("CourseMode", "CourseType", "CourseTag", "CourseClass", "NpsDepartment", "Section",
                        "CourseMaster", "ProgramTerm");
    }

    /**
     * A {@code Data.termCourse} map shaped like the TermCourse detail read: scalars flat, associations
     * nested, gamification off, {@code subjectWeight} present, and the flat numeric {@code departmentId}
     * included precisely so the builder can be caught using it instead of the nested uuid. All values are
     * fixture data - no live uuid and no credential.
     */
    private static Map<String, Object> termCourseFixture() {
        Map<String, Object> termCourse = new LinkedHashMap<>();
        termCourse.put("uuid", "capstone-termcourse-uuid");
        termCourse.put("credits", 3);
        termCourse.put("hours", 30);
        termCourse.put("isAttendanceCalculated", true);
        termCourse.put("departmentId", NUMERIC_DEPARTMENT_ID);
        termCourse.put("evaluationCriteria", "Internal");
        termCourse.put("courseOverview", "Overview text");
        termCourse.put("description", "Description text");
        termCourse.put("courseOutcome", "Outcome text");
        termCourse.put("workexperienced", false);
        termCourse.put("readingMaterial", "Reading list");
        termCourse.put("videoLink", "");
        termCourse.put("topicsJson", "[]");
        termCourse.put("placementCourse", false);
        termCourse.put("subjectWeight", "");
        termCourse.put("showCourseToStudent", true);
        termCourse.put("isCourseCalculated", true);
        termCourse.put("isGamify", false);
        termCourse.put("isGamifyDomainWise", false);
        termCourse.put("attendanceType", "SESSION");
        termCourse.put("coursePurpose", "REGULAR");
        termCourse.put("isCreditCalculated", true);
        termCourse.put("isGpaCalculated", true);
        termCourse.put("noOfSession", 12);
        termCourse.put("createdBy", 101);
        termCourse.put("createdAt", "fixture-created-at");
        termCourse.put("updatedAt", "fixture-updated-at");

        // The sixteen read-back-only fields, all present on read and none of them sent on update.
        termCourse.put("isMandatory", true);
        termCourse.put("isCourseReview", false);
        termCourse.put("isDraft", false);
        termCourse.put("displayOrder", 1);
        termCourse.put("startDate", "fixture-start-date");
        termCourse.put("endDate", "fixture-end-date");
        termCourse.put("sectionId", 55);
        termCourse.put("sessionFormat", "OFFLINE");
        termCourse.put("status", "ACTIVE");
        termCourse.put("remarks", "");
        termCourse.put("moderatedDiscussion", false);
        termCourse.put("transactionId", "fixture-transaction-id");
        termCourse.put("isSubjectWeightEnable", false);
        termCourse.put("AttendanceStats", new LinkedHashMap<>());
        termCourse.put("feedbackSummary", new LinkedHashMap<>());
        termCourse.put("isAttendaceStatsProcessed", false);

        // The domain-related field as the READ returns it - the update sends courseDomain instead.
        termCourse.put("TermCourseDomains", java.util.Collections.emptyList());

        termCourse.put("CourseMode", nested("mode-uuid"));
        termCourse.put("CourseType", nested("type-uuid"));
        termCourse.put("CourseTag", nested("tag-uuid"));
        termCourse.put("CourseClass", nested("class-uuid"));
        termCourse.put("NpsDepartment", nested(DEPARTMENT_UUID));
        termCourse.put("Section", nested("capstone-section-uuid"));
        termCourse.put("CourseMaster", nested("course-master-uuid"));
        termCourse.put("ProgramTerm", nested("program-term-uuid"));
        return termCourse;
    }

    private static Map<String, Object> nested(String uuid) {
        Map<String, Object> association = new LinkedHashMap<>();
        association.put("uuid", uuid);
        return association;
    }

    /** TOP-LEVEL {@code Data.taIds} - a sibling of {@code termCourse}, not one of its fields. */
    private static Object taIdsFixture() {
        return Arrays.asList("ta-uuid-1");
    }

    /** TOP-LEVEL {@code Data.facultyIds} - a sibling of {@code termCourse}, not one of its fields. */
    private static Object facultyIdsFixture() {
        return Arrays.asList("faculty-uuid-1", "faculty-uuid-2");
    }

    // =====================================================================================
    // S2 - the capstone CONFIGURATION contract (metrics, metrics-builder grid, dashboard,
    // analytics, leaderboard, resources, schedule) and the REWARD contract.
    //
    // Same rationale as the S1 section above, only sharper: the configuration endpoint is an
    // AGGREGATE write, so one mis-shaped slice is persisted silently behind a 2xx, and several
    // parts of the captured contract look like bugs and are not (the 'mounth*' misspellings,
    // an explicitly null timezone, metrics that name themselves under 'metric', builder rows
    // that use 'inputType', a reduced two-key Point value, and weightages that mix numbers
    // with a string). Pinning them here is what stops a future "tidy-up" from sending a body
    // that was never proven against the API. All builders are pure, so these tests need no
    // HTTP, no context and no credentials.
    // =====================================================================================

    /** The verified wire order of the twenty aggregate-configuration keys. */
    private static final List<String> CONFIG_KEYS_IN_ORDER = Arrays.asList(
            "termCourseId",
            "sectionId",
            "metrics",
            "scheduleStartDate",
            "scheduleEndDate",
            "triggerInterval",
            "triggerDay",
            "allowSkipUntil",
            "allowSkip",
            "mounthTriggerDate",
            "mounthSkipDate",
            "dashboardDisplay",
            "analyticsDisplay",
            "xByXRows",
            "xByXColumns",
            "leaderboardType",
            "timezone",
            "leaderboard",
            "resources",
            "minBalanceThreshold");

    /** The verified wire order of the eight reward keys. */
    private static final List<String> REWARD_KEYS_IN_ORDER = Arrays.asList(
            "gamificationConfigId",
            "rewardName",
            "coinRequiredToClaimReward",
            "description",
            "totalClaimLimit",
            "perTeamClaimLimit",
            "fileUpload",
            "questions");

    /** The five keys of a full metric object, in order. NOTE the name key is {@code metric}. */
    private static final List<String> METRIC_KEYS_IN_ORDER = Arrays.asList(
            "metric", "target", "type", "showDashboard", "currency");

    private static final String CONFIG_TERM_COURSE_ID = "capstone-termcourse-uuid";
    private static final String CONFIG_SECTION_ID = "capstone-section-uuid";
    private static final String SCHEDULE_START = "2026-03-01";
    private static final String SCHEDULE_END = "2026-03-31";
    private static final String RESOURCE_DATE = "2026-01-01T00:00:00.000Z";
    private static final String CALENDAR_DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}";
    private static final int GAMIFICATION_CONFIG_ID = 9182;

    @Test
    void metricsCarryTheSixConfiguredMetricsWithTheirVerifiedTargetsAndTypes() {
        List<Map<String, Object>> metrics = CapstoneWorkflow.buildCapstoneMetrics();

        assertThat(metrics)
                .as("the configuration tracks exactly six metrics - a seventh (or a missing sixth) changes what "
                        + "the capstone course measures")
                .hasSize(6);
        assertThat(valuesOf(metrics, "metric"))
                .as("the six metric names, in the verified order")
                .containsExactly("Revenue", "Cost", "Number of Sales", "Profit", "Customers", "Funding Raised");
        assertThat(valuesOf(metrics, "target"))
                .as("only Revenue, Number of Sales and Profit carry a target - the other three are tracked but "
                        + "untargeted, and their target is an empty string rather than null or absent")
                .containsExactly("15000", "", "100", "10", "", "");
        assertThat(valuesOf(metrics, "type"))
                .as("the per-metric input types, in the verified order")
                .containsExactly("amount", "amount", "count", "percentage", "count", "amount");
    }

    @Test
    void everyMetricCarriesExactlyFiveKeysAndNamesItselfUnderMetricNeverUnderName() {
        for (Map<String, Object> metric : CapstoneWorkflow.buildCapstoneMetrics()) {
            assertThat(metric.keySet())
                    .as("metric %s must carry exactly the five verified keys in order", metric.get("metric"))
                    .containsExactlyElementsOf(METRIC_KEYS_IN_ORDER);
            assertThat(metric)
                    .as("a metric names itself under 'metric' - a 'name' key is a different contract and would be "
                            + "ignored by the API, leaving the metric unnamed")
                    .doesNotContainKey("name");
            assertThat(metric.get("showDashboard"))
                    .as("showDashboard is false on every metric - the dashboard selection lives in "
                            + "dashboardDisplay, not on the metric")
                    .isEqualTo(false);
            assertThat(metric.get("currency"))
                    .as("every metric carries the rupee currency, including the count and percentage ones")
                    .isEqualTo("rupee");
        }
    }

    @Test
    void dashboardDisplayPinsRevenueAndProfitAsFullMetricsUnderValue() {
        List<Map<String, Object>> dashboard = CapstoneWorkflow.buildDashboardDisplay();

        assertThat(dashboard)
                .as("exactly two metrics are pinned to the dashboard")
                .hasSize(2);
        assertThat(valuesOf(dashboard, "label"))
                .as("the pinned labels, in the verified order")
                .containsExactly("Revenue", "Profit");

        for (Map<String, Object> entry : dashboard) {
            assertThat(entry.keySet())
                    .as("a dashboard entry is exactly {value, label}")
                    .containsExactly("value", "label");
            assertThat(nested(entry.get("value")).keySet())
                    .as("a pinned entry's value is the FULL five-key metric, not just its name")
                    .containsExactlyElementsOf(METRIC_KEYS_IN_ORDER);
        }
    }

    @Test
    void analyticsDisplayChartsThreeMetricsIdentifiedByNameUnderType() {
        List<Map<String, Object>> analytics = CapstoneWorkflow.buildAnalyticsDisplay();

        assertThat(analytics)
                .as("exactly three metrics are charted")
                .hasSize(3);
        assertThat(valuesOf(analytics, "type"))
                .as("'type' on an analytics entry holds the metric NAME, not an amount/count/percentage input "
                        + "type - the captured contract's own naming")
                .containsExactly("Revenue", "Profit", "Number of Sales");

        for (Map<String, Object> entry : analytics) {
            assertThat(entry.keySet())
                    .as("an analytics entry is exactly {type, isDetailedVisualGraph, data}")
                    .containsExactly("type", "isDetailedVisualGraph", "data");
            assertThat(entry.get("isDetailedVisualGraph"))
                    .as("every charted metric is configured with the detailed visual graph on")
                    .isEqualTo(true);
            assertThat(nested(entry.get("data")).keySet())
                    .as("the charted metric object itself sits under 'data' as a full five-key metric")
                    .containsExactlyElementsOf(METRIC_KEYS_IN_ORDER);
        }
    }

    @Test
    void xByXRowsCarryThreeKeysAndNameTheirInputTypeUnderInputTypeNotType() {
        List<Map<String, Object>> rows = CapstoneWorkflow.buildXByXRows();

        assertThat(rows)
                .as("the metrics-builder grid has exactly three rows")
                .hasSize(3);
        assertThat(valuesOf(rows, "inputType"))
                .as("the per-row input types, in the verified order")
                .containsExactly("amount", "count", "percentage");

        for (Map<String, Object> row : rows) {
            assertThat(row.keySet())
                    .as("a builder row is exactly {metric, inputType, proofRequired} - deliberately NOT a full "
                            + "metric object")
                    .containsExactly("metric", "inputType", "proofRequired");
            assertThat(row)
                    .as("a builder row names its input type under 'inputType'; a 'type' key belongs to a metric "
                            + "object and would leave the row's input type unset")
                    .doesNotContainKey("type");
            assertThat(row.get("proofRequired"))
                    .as("no builder row requires proof")
                    .isEqualTo(false);
        }
    }

    @Test
    void xByXColumnsArePlainStringsRatherThanObjects() {
        List<String> columns = CapstoneWorkflow.buildXByXColumns();

        assertThat(columns)
                .as("the builder grid has the single 'Online' column")
                .containsExactly("Online");
        for (Object column : columns) {
            assertThat(column)
                    .as("a column is a PLAIN STRING - wrapping it in an object would not match the captured "
                            + "contract")
                    .isInstanceOf(String.class)
                    .isNotInstanceOf(Map.class);
        }
    }

    @Test
    void leaderboardIsAFormulaOverFourWeightedEntriesWithAReducedPointValue() {
        Map<String, Object> leaderboard = CapstoneWorkflow.buildLeaderboard();

        assertThat(leaderboard.keySet())
                .as("the leaderboard is exactly {type, formula}")
                .containsExactly("type", "formula");
        assertThat(leaderboard.get("type"))
                .as("the leaderboard is formula-based")
                .isEqualTo("formula");

        Map<String, Object> formula = nested(leaderboard.get("formula"));
        assertThat(formula.keySet())
                .as("the formula is exactly {setName, metrics}")
                .containsExactly("setName", "metrics");
        assertThat(formula.get("setName"))
                .as("the formula set name is part of the contract and is asserted by value on read-back")
                .isEqualTo("custom formula based calculation");

        List<Map<String, Object>> entries = nestedList(formula.get("metrics"));
        assertThat(entries)
                .as("the formula weights exactly four entries")
                .hasSize(4);

        List<Object> labels = new java.util.ArrayList<>();
        for (Map<String, Object> entry : entries) {
            assertThat(entry.keySet())
                    .as("a weighted entry is exactly {metric, weightage}")
                    .containsExactly("metric", "weightage");
            Map<String, Object> metricRef = nested(entry.get("metric"));
            assertThat(metricRef.keySet())
                    .as("a weighted entry's metric is exactly {value, label}")
                    .containsExactly("value", "label");
            labels.add(metricRef.get("label"));
        }
        assertThat(labels)
                .as("the weighted labels, in the verified order - 'Point' is a leaderboard-only pseudo-metric and "
                        + "is NOT one of the six configured metrics")
                .containsExactly("Point", "Revenue", "Number of Sales", "Profit");

        Map<String, Object> pointValue = nested(nested(entries.get(0).get("metric")).get("value"));
        assertThat(pointValue.keySet())
                .as("the Point entry's value is a REDUCED two-key object - the other three carry full five-key "
                        + "metrics, and that asymmetry is verified, not an oversight")
                .containsExactly("metric", "type");
        assertThat(pointValue)
                .as("the reduced Point value carries no target, no showDashboard and no currency")
                .doesNotContainKeys("target", "showDashboard", "currency");
    }

    @Test
    void leaderboardWeightagesMixThreeNumbersWithOneString() {
        List<Map<String, Object>> entries = nestedList(
                nested(CapstoneWorkflow.buildLeaderboard().get("formula")).get("metrics"));

        Object pointWeightage = entries.get(0).get("weightage");
        Object revenueWeightage = entries.get(1).get("weightage");
        Object salesWeightage = entries.get(2).get("weightage");
        Object profitWeightage = entries.get(3).get("weightage");

        assertThat(pointWeightage)
                .as("the Point weightage is a NUMBER")
                .isInstanceOf(Number.class)
                .isNotInstanceOf(String.class);
        assertThat(revenueWeightage)
                .as("the Revenue weightage is a NUMBER")
                .isInstanceOf(Number.class)
                .isNotInstanceOf(String.class);
        assertThat(salesWeightage)
                .as("the Number of Sales weightage is a NUMBER")
                .isInstanceOf(Number.class)
                .isNotInstanceOf(String.class);
        assertThat(profitWeightage)
                .as("the Profit weightage is the STRING \"40\" while the other three are numbers - the captured "
                        + "contract mixes both types in this one array, and normalising it would mean sending a "
                        + "body that was never proven against the API")
                .isInstanceOf(String.class)
                .isEqualTo("40");

        assertThat(Arrays.asList(String.valueOf(pointWeightage), String.valueOf(revenueWeightage),
                        String.valueOf(salesWeightage), String.valueOf(profitWeightage)))
                .as("the mixed typing is invisible through String.valueOf, which is exactly why every read-back "
                        + "weightage comparison goes through it")
                .containsExactly("10", "20", "30", "40");
    }

    @Test
    void resourcesCarryOneFolderHoldingOnePlaceholderLinkedResource() {
        List<Map<String, Object>> resources = CapstoneWorkflow.buildResources(RESOURCE_DATE);

        assertThat(resources)
                .as("exactly one resource folder is configured")
                .hasSize(1);
        Map<String, Object> folder = resources.get(0);
        assertThat(folder.get("folderName"))
                .as("the folder name comes from TestDataFactory and is deterministic, so the read-back can assert "
                        + "it by value")
                .isEqualTo(com.lms.utils.TestDataFactory.capstoneResourceFolderName());

        List<Map<String, Object>> folderData = nestedList(folder.get("folderData"));
        assertThat(folderData)
                .as("the folder holds exactly one resource")
                .hasSize(1);

        Map<String, Object> resource = folderData.get(0);
        assertThat(resource.keySet())
                .as("a resource is exactly {name, link, date, file}")
                .containsExactly("name", "link", "date", "file");
        assertThat(resource)
                .as("'file' is an EXPLICIT null - present on the wire, not omitted")
                .containsKey("file");
        assertThat(resource.get("file"))
                .as("no file is uploaded with the link-only resource")
                .isNull();
        assertThat(resource.get("date"))
                .as("the resource timestamp is INJECTED by the caller so this builder stays pure - it must be "
                        + "carried through verbatim rather than re-computed")
                .isEqualTo(RESOURCE_DATE);
        assertThat((String) resource.get("link"))
                .as("the resource link is a documented placeholder URL - it must NOT be an admin-dev (or any "
                        + "other environment) URL, because no approved capstone resource URL exists")
                .startsWith("https://example.com/")
                .doesNotContain("admin-dev");
    }

    @Test
    void configurationPayloadCarriesExactlyTheTwentyKeysInTheVerifiedWireOrder() {
        Map<String, Object> payload = configurationPayloadFixture();

        assertThat(payload.keySet())
                .as("the aggregate configuration body must carry exactly the twenty verified keys - the endpoint "
                        + "persists the whole configuration in one write, so an extra or missing key changes what "
                        + "is stored")
                .containsExactlyElementsOf(CONFIG_KEYS_IN_ORDER);
        assertThat(payload)
                .as("the builder must return an insertion-ordered map so the wire order matches the capture")
                .isInstanceOf(LinkedHashMap.class);
        assertThat(new java.util.ArrayList<>(payload.keySet()))
                .as("key order must match the verified capture exactly")
                .isEqualTo(CONFIG_KEYS_IN_ORDER);
        assertThat(payload.get("termCourseId"))
                .as("the configuration is bound to the CAPSTONE termCourse")
                .isEqualTo(CONFIG_TERM_COURSE_ID);
        assertThat(payload.get("sectionId"))
                .as("the configuration is bound to the CAPSTONE section")
                .isEqualTo(CONFIG_SECTION_ID);
    }

    @Test
    void configurationPayloadKeepsTimezonePresentAsAnExplicitNull() {
        Map<String, Object> payload = configurationPayloadFixture();

        assertThat(payload.containsKey("timezone"))
                .as("timezone must be PRESENT on the wire - it is an explicit JSON null that survives because the "
                        + "capstone client serialises nulls, and dropping the key is a different request")
                .isTrue();
        assertThat(payload.get("timezone"))
                .as("timezone's value is null, never an empty string and never a resolved zone id")
                .isNull();
    }

    @Test
    void configurationPayloadPreservesTheApisOwnMisspelledMonthKeys() {
        Map<String, Object> payload = configurationPayloadFixture();

        assertThat(payload)
                .as("'mounthTriggerDate' and 'mounthSkipDate' are the API's OWN misspellings and are reproduced "
                        + "exactly - correcting them silently drops both fields")
                .containsKeys("mounthTriggerDate", "mounthSkipDate");
        assertThat(payload)
                .as("the correctly spelled variants are NOT part of the contract and must not be sent")
                .doesNotContainKeys("monthTriggerDate", "monthSkipDate");
    }

    @Test
    void configurationPayloadSendsEmptyStringsRatherThanNullsForTheUnsetScheduleFields() {
        Map<String, Object> payload = configurationPayloadFixture();

        assertThat(payload.get("triggerInterval"))
                .as("metrics are collected daily")
                .isEqualTo("daily");
        assertThat(payload.get("allowSkip"))
                .as("skipping a collection is not allowed")
                .isEqualTo(false);
        assertThat(payload.get("triggerDay"))
                .as("triggerDay is an empty STRING, not null and not absent - a daily interval needs no day")
                .isEqualTo("");
        assertThat(payload.get("allowSkipUntil"))
                .as("allowSkipUntil is an empty STRING, not null and not absent")
                .isEqualTo("");
        assertThat(payload.get("mounthTriggerDate"))
                .as("mounthTriggerDate is an empty STRING, not null and not absent")
                .isEqualTo("");
        assertThat(payload.get("mounthSkipDate"))
                .as("mounthSkipDate is an empty STRING, not null and not absent")
                .isEqualTo("");
        assertThat(payload.get("leaderboardType"))
                .as("the top-level leaderboardType mirrors leaderboard.type")
                .isEqualTo("formula");
        assertThat(payload.get("minBalanceThreshold"))
                .as("minBalanceThreshold is an empty STRING, not null and not absent")
                .isEqualTo("");
    }

    @Test
    void configurationScheduleDatesArePlainCalendarDatesNotTimestamps() {
        Map<String, Object> payload = configurationPayloadFixture();

        assertThat((String) payload.get("scheduleStartDate"))
                .as("the schedule start is a plain yyyy-MM-dd calendar date - the resource 'date' is the only "
                        + "full ISO timestamp in this payload")
                .matches(CALENDAR_DATE_PATTERN);
        assertThat((String) payload.get("scheduleEndDate"))
                .as("the schedule end is a plain yyyy-MM-dd calendar date")
                .matches(CALENDAR_DATE_PATTERN);
    }

    @Test
    void rewardPayloadCarriesTheEightKeysInOrderWithNumericIdsAndExplicitNulls() {
        Map<String, Object> reward = CapstoneWorkflow.buildCapstoneRewardPayload(GAMIFICATION_CONFIG_ID);

        assertThat(reward.keySet())
                .as("the reward body must carry exactly the eight verified keys")
                .containsExactlyElementsOf(REWARD_KEYS_IN_ORDER);
        assertThat(new java.util.ArrayList<>(reward.keySet()))
                .as("reward key order must match the verified capture exactly")
                .isEqualTo(REWARD_KEYS_IN_ORDER);
        assertThat(reward)
                .as("the builder must return an insertion-ordered map")
                .isInstanceOf(LinkedHashMap.class);

        assertThat(reward.get("gamificationConfigId"))
                .as("gamificationConfigId is the NUMERIC configuration id sent as a number - the configuration "
                        + "uuid would not resolve here")
                .isInstanceOf(Number.class)
                .isNotInstanceOf(String.class)
                .isEqualTo(GAMIFICATION_CONFIG_ID);
        assertThat(reward.get("coinRequiredToClaimReward"))
                .as("the coin cost is a NUMBER, not a string")
                .isInstanceOf(Number.class)
                .isNotInstanceOf(String.class);

        assertThat(reward)
                .as("totalClaimLimit, perTeamClaimLimit and fileUpload are EXPLICIT nulls - present on the wire, "
                        + "not omitted")
                .containsKeys("totalClaimLimit", "perTeamClaimLimit", "fileUpload");
        assertThat(reward.get("totalClaimLimit")).as("totalClaimLimit is unlimited (null)").isNull();
        assertThat(reward.get("perTeamClaimLimit")).as("perTeamClaimLimit is unlimited (null)").isNull();
        assertThat(reward.get("fileUpload")).as("no file upload is configured on the reward").isNull();
    }

    @Test
    void rewardQuestionsCoverTheThreeQuestionTypesWithSixKeysEach() {
        List<Map<String, Object>> questions = nestedList(
                CapstoneWorkflow.buildCapstoneRewardPayload(GAMIFICATION_CONFIG_ID).get("questions"));

        assertThat(questions)
                .as("the reward asks exactly three claim questions")
                .hasSize(3);
        assertThat(valuesOf(questions, "type"))
                .as("the three question types, in the verified order")
                .containsExactly("text", "multi_select", "single_select");

        for (Map<String, Object> question : questions) {
            assertThat(question.keySet())
                    .as("a claim question is exactly {questionUuid, title, type, options, isRequired, isMandatory}")
                    .containsExactly("questionUuid", "title", "type", "options", "isRequired", "isMandatory");
        }

        assertThat((List<?>) questions.get(0).get("options"))
                .as("a free-text question carries an EMPTY options list - options are still sent, just empty")
                .isEmpty();
        assertThat((List<?>) questions.get(1).get("options"))
                .as("a multi-select question must offer options, or nothing can be selected")
                .isNotEmpty();
        assertThat((List<?>) questions.get(2).get("options"))
                .as("a single-select question must offer options, or nothing can be selected")
                .isNotEmpty();
    }

    @Test
    void everyRewardQuestionUuidIsAFreshlyMintedDistinctUuid() {
        List<Map<String, Object>> questions = nestedList(
                CapstoneWorkflow.buildCapstoneRewardPayload(GAMIFICATION_CONFIG_ID).get("questions"));

        List<String> uuids = new java.util.ArrayList<>();
        for (Map<String, Object> question : questions) {
            String questionUuid = (String) question.get("questionUuid");
            assertThat(java.util.UUID.fromString(questionUuid).toString())
                    .as("questionUuid must be a well-formed uuid")
                    .isEqualTo(questionUuid);
            uuids.add(questionUuid);
        }
        assertThat(uuids)
                .as("each question carries its OWN uuid - reusing one would merge two questions server-side")
                .doesNotHaveDuplicates();

        List<Map<String, Object>> secondInvocation = nestedList(
                CapstoneWorkflow.buildCapstoneRewardPayload(GAMIFICATION_CONFIG_ID).get("questions"));
        List<String> secondUuids = new java.util.ArrayList<>();
        for (Map<String, Object> question : secondInvocation) {
            secondUuids.add((String) question.get("questionUuid"));
        }

        assertThat(secondUuids)
                .as("two invocations must produce DIFFERENT questionUuids - they are minted at runtime, never "
                        + "taken from a captured literal")
                .doesNotContainAnyElementsOf(uuids);
    }

    /** The aggregate configuration body built from fixture ids - no live uuid and no credential. */
    private static Map<String, Object> configurationPayloadFixture() {
        return CapstoneWorkflow.buildCapstoneConfigurationPayload(CONFIG_TERM_COURSE_ID, CONFIG_SECTION_ID,
                SCHEDULE_START, SCHEDULE_END, RESOURCE_DATE);
    }

    /** Collects one key's value from every entry, so an ordered list assertion names the whole slice at once. */
    private static List<Object> valuesOf(List<Map<String, Object>> entries, String key) {
        List<Object> values = new java.util.ArrayList<>();
        for (Map<String, Object> entry : entries) {
            values.add(entry.get(key));
        }
        return values;
    }

    /** A nested object, asserted to BE an object so a shape change fails by name rather than by ClassCastException. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Object raw) {
        assertThat(raw).as("expected a nested JSON object, got: %s", raw).isInstanceOf(Map.class);
        return (Map<String, Object>) raw;
    }

    /** A nested array of objects, asserted the same way and for the same reason as {@link #nested(Object)}. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nestedList(Object raw) {
        assertThat(raw).as("expected a nested JSON array of objects, got: %s", raw).isInstanceOf(List.class);
        return (List<Map<String, Object>>) raw;
    }

    // =====================================================================================
    // S3 - the capstone TEAM-creation contract (CapstoneWorkflow#buildCapstoneTeamPayload).
    //
    // Same rationale as the two sections above: the create endpoint answers 201 without
    // echoing the shape it accepted, so a wrongly typed field is persisted (or silently
    // dropped) behind a success. Four of this body's wire types are HAR-proven and read as
    // mistakes - a STRING "1" size, an empty-string image, a top-level allowTeamFormation
    // sitting OUTSIDE sectionGroup[0], and a userGroup that is a FLAT uuid array here while
    // the same name is an OBJECT on the student-side read. Pinning key order as well as key
    // set matters because the body is serialised from a LinkedHashMap specifically so the
    // declared order reaches the wire. The fixtures below are distinguishable placeholders,
    // so a swapped argument fails loudly instead of passing on two equal-looking ids. The
    // builder is pure, so these tests need no HTTP, no context and no credentials.
    // =====================================================================================

    /** The verified wire order of the five team-creation top-level keys. */
    private static final List<String> TEAM_KEYS_IN_ORDER = Arrays.asList(
            "sectionId",
            "termCourseId",
            "createdBy",
            "sectionGroup",
            "allowTeamFormation");

    /** The verified wire order of the six {@code sectionGroup[0]} keys. */
    private static final List<String> SECTION_GROUP_KEYS_IN_ORDER = Arrays.asList(
            "name",
            "description",
            "image",
            "size",
            "userGroup",
            "groupRepresentative");

    private static final String TEAM_CAPSTONE_SECTION_ID = "capstone-section-placeholder";
    private static final String TEAM_CAPSTONE_TERM_COURSE_ID = "capstone-termcourse-placeholder";
    private static final String TEAM_ADMIN_USER_ID = "admin-user-placeholder";
    private static final String TEAM_STUDENT_UUID = "student-uuid-placeholder";
    private static final String TEAM_NAME = "team-name-placeholder";
    private static final String TEAM_DESCRIPTION = "team-description-placeholder";
    private static final String TEAM_REPRESENTATIVE_LABEL = "representative-label-placeholder";

    @Test
    void teamPayloadCarriesExactlyTheFiveTopLevelKeysInTheVerifiedWireOrder() {
        Map<String, Object> payload = teamPayloadFixture();

        assertThat(payload.keySet())
                .as("the team-creation body must carry exactly the five verified top-level keys - an extra or "
                        + "missing key changes what the create endpoint accepts, and it answers 201 without "
                        + "echoing the shape it was given")
                .containsExactlyElementsOf(TEAM_KEYS_IN_ORDER);
        assertThat(new java.util.ArrayList<>(payload.keySet()))
                .as("top-level key order must match the verified capture exactly")
                .containsExactly("sectionId", "termCourseId", "createdBy", "sectionGroup", "allowTeamFormation");
        assertThat(payload)
                .as("the builder must return an insertion-ordered map so the DECLARED order is the WIRE order - a "
                        + "HashMap would reorder the body unpredictably")
                .isInstanceOf(LinkedHashMap.class);
    }

    @Test
    void teamSectionGroupIsOneElementCarryingExactlyTheSixKeysInTheVerifiedWireOrder() {
        Map<String, Object> payload = teamPayloadFixture();

        List<Map<String, Object>> sectionGroups = nestedList(payload.get("sectionGroup"));
        assertThat(sectionGroups)
                .as("sectionGroup is an array holding exactly ONE team - the scenario creates one team per run")
                .hasSize(1);

        Map<String, Object> sectionGroup = sectionGroups.get(0);
        assertThat(sectionGroup.keySet())
                .as("sectionGroup[0] must carry exactly the six verified keys")
                .containsExactlyElementsOf(SECTION_GROUP_KEYS_IN_ORDER);
        assertThat(new java.util.ArrayList<>(sectionGroup.keySet()))
                .as("sectionGroup[0] key order must match the verified capture exactly")
                .containsExactly("name", "description", "image", "size", "userGroup", "groupRepresentative");
        assertThat(sectionGroup)
                .as("sectionGroup[0] must be insertion-ordered too, for the same reason the top level is")
                .isInstanceOf(LinkedHashMap.class);

        assertThat(sectionGroup.get("name"))
                .as("the team name is injected by the caller so the builder stays pure, and carried through verbatim")
                .isEqualTo(TEAM_NAME);
        assertThat(sectionGroup.get("description"))
                .as("the team description is injected and carried through verbatim for the same reason")
                .isEqualTo(TEAM_DESCRIPTION);
    }

    @Test
    void teamSizeIsTheStringOneRatherThanTheNumberOne() {
        Map<String, Object> sectionGroup = teamSectionGroupFixture();

        assertThat(sectionGroup.get("size"))
                .as("size is the JSON STRING \"1\" - the capture proves a string, so tidying it to the number 1 "
                        + "would send a body that was never proven against the API")
                .isInstanceOf(String.class)
                .isNotInstanceOf(Number.class)
                .isEqualTo("1");
    }

    @Test
    void teamImageIsTheEmptyStringRatherThanNullOrAbsent() {
        Map<String, Object> sectionGroup = teamSectionGroupFixture();

        assertThat(sectionGroup)
                .as("image must be PRESENT on the wire - an image-less team still carries the key")
                .containsKey("image");
        assertThat(sectionGroup.get("image"))
                .as("image is the EMPTY STRING, HAR-proven - not null and not absent")
                .isInstanceOf(String.class)
                .isEqualTo("");
    }

    @Test
    void allowTeamFormationIsATopLevelBooleanFalseAndNotASectionGroupKey() {
        Map<String, Object> payload = teamPayloadFixture();

        assertThat(payload.get("allowTeamFormation"))
                .as("allowTeamFormation is a JSON BOOLEAN false - the string \"false\" is a different value to "
                        + "the API")
                .isInstanceOf(Boolean.class)
                .isNotInstanceOf(String.class)
                .isEqualTo(false);
        assertThat(teamSectionGroupFixture())
                .as("allowTeamFormation sits at TOP LEVEL, a sibling of sectionGroup - moving it inside "
                        + "sectionGroup[0] silently drops the flag")
                .doesNotContainKey("allowTeamFormation");
    }

    @Test
    void userGroupIsAFlatSingleElementArrayOfTheStudentUuid() {
        Object userGroup = teamSectionGroupFixture().get("userGroup");

        assertThat(userGroup)
                .as("userGroup is a FLAT ARRAY here - the API names the same concept as an OBJECT on the "
                        + "student-side read, and the two shapes are not interchangeable")
                .isInstanceOf(List.class)
                .isNotInstanceOf(Map.class);

        List<?> members = (List<?>) userGroup;
        assertThat(members)
                .as("exactly one member is added at creation - the runtime student")
                .hasSize(1);
        assertThat(members.get(0))
                .as("the single member is the runtime student uuid as a plain STRING, not a wrapped object")
                .isInstanceOf(String.class)
                .isNotInstanceOf(Map.class)
                .isEqualTo(TEAM_STUDENT_UUID);
    }

    @Test
    void groupRepresentativeIsExactlyValueThenLabelWithTheStudentUuidAsValue() {
        Map<String, Object> representative = nested(teamSectionGroupFixture().get("groupRepresentative"));

        assertThat(representative.keySet())
                .as("groupRepresentative is exactly {value, label}, in that order")
                .containsExactly("value", "label");
        assertThat(representative.get("value"))
                .as("value is the runtime student uuid - the membership verdict is derived from value, never "
                        + "from the cosmetic label")
                .isEqualTo(TEAM_STUDENT_UUID);
        assertThat(representative.get("label"))
                .as("label is the injected cosmetic display string, carried through verbatim")
                .isEqualTo(TEAM_REPRESENTATIVE_LABEL);
    }

    @Test
    void teamIdentityFieldsBindToTheCapstoneIdentitiesAndTheAdminUser() {
        Map<String, Object> payload = teamPayloadFixture();

        assertThat(payload.get("sectionId"))
                .as("sectionId must be the CAPSTONE section - binding the team to the runtime section would "
                        + "write into the environment the assignment, session and attendance journeys depend on")
                .isEqualTo(TEAM_CAPSTONE_SECTION_ID);
        assertThat(payload.get("termCourseId"))
                .as("termCourseId must be the CAPSTONE termCourse, never the runtime termCourse")
                .isEqualTo(TEAM_CAPSTONE_TERM_COURSE_ID);
        assertThat(payload.get("createdBy"))
                .as("createdBy is the ADMIN user id - the student uuid here would attribute the team to the "
                        + "member being added")
                .isEqualTo(TEAM_ADMIN_USER_ID)
                .isNotEqualTo(TEAM_STUDENT_UUID);
    }

    /** The team-creation body built from distinguishable placeholders - no live uuid and no credential. */
    private static Map<String, Object> teamPayloadFixture() {
        return CapstoneWorkflow.buildCapstoneTeamPayload(TEAM_CAPSTONE_SECTION_ID, TEAM_CAPSTONE_TERM_COURSE_ID,
                TEAM_ADMIN_USER_ID, TEAM_NAME, TEAM_DESCRIPTION, TEAM_STUDENT_UUID, TEAM_REPRESENTATIVE_LABEL);
    }

    /** The single {@code sectionGroup[0]} element of {@link #teamPayloadFixture()}. */
    private static Map<String, Object> teamSectionGroupFixture() {
        return nestedList(teamPayloadFixture().get("sectionGroup")).get(0);
    }
}
