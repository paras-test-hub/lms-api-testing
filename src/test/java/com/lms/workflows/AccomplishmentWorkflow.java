package com.lms.workflows;

import com.lms.clients.StudentProfileClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.StudentProfileJourneyReport;
import com.lms.utils.DateUtils;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration for the Student Profile journey's Accomplishments sub-workflow.
 *
 * <p>Verified from hawkeye-apis_2026-07-30_12-57-10.har:
 * <ul>
 *   <li>Entry 57: POST {@code /userProfile/{studentUuid}/accomplishment} — creates first</li>
 *   <li>Entry 71: POST same endpoint — creates second (response Data[] has both)</li>
 *   <li>Entry 77: PATCH {@code /userProfile/{accomplishmentUuid}/accomplishment} — updates first
 *       (note: ACCOMPLISHMENT uuid in path, not student uuid)</li>
 *   <li>Entry 81: PATCH {@code /userProfile/{accomplishmentUuid}/accomplishment} with
 *       {@code {isDeleted:true}} — deletes second</li>
 *   <li>Profile GET exposes accomplishments in {@code generalData.Accomplishments[]} (capital A)</li>
 * </ul>
 *
 * <p>File uploads: the HAR shows {@code uploadedFile} with Filestack URLs. This framework has no
 * file-upload capability, so {@code uploadedFile} is sent as {@code null} and
 * {@code uploadedPresentation} as {@code null} — both are nullable in the create/update payloads.
 */
public class AccomplishmentWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AccomplishmentWorkflow.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StudentProfileClient profileClient;

    private List<Map<String, Object>> baselineAccomplishments;
    private String firstAccomplishmentUuid;
    private String secondAccomplishmentUuid;
    private Map<String, Object> firstCreatePayload;
    private Map<String, Object> secondCreatePayload;
    private Map<String, Object> updatePayload;

    public AccomplishmentWorkflow() {
        this.profileClient = new StudentProfileClient();
    }

    // ================= Stage 1: baseline =================

    public void retrieveRuntimeProfile(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode())
                .as("profile GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        baselineAccomplishments = response.jsonPath().getList("generalData.Accomplishments");
        if (baselineAccomplishments == null) {
            baselineAccomplishments = new ArrayList<>();
        }

        StudentProfileJourneyReport.checkpoint(context, "Runtime Profile Retrieved",
                "Student: " + context.getStudentEmail() + " (" + studentUuid + ")",
                "Existing accomplishments: " + baselineAccomplishments.size());
        log.info("Retrieved baseline for Accomplishments: {} existing", baselineAccomplishments.size());
    }

    // ================= Stage 2: create first accomplishment =================

    public void createFirstAccomplishment(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        firstCreatePayload = buildCreatePayload(studentUuid);

        Response response = profileClient.createAccomplishment(studentUuid, firstCreatePayload, studentToken);
        assertThat(response.statusCode())
                .as("first accomplishment create must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> data = response.jsonPath().getList("Data");
        assertThat(data).as("Data must contain the created accomplishment").isNotEmpty();

        Map<String, Object> newest = data.get(data.size() - 1);
        firstAccomplishmentUuid = (String) newest.get("uuid");
        assertThat(firstAccomplishmentUuid).as("first accomplishment must have a uuid").isNotBlank();
        assertThat(newest.get("title")).as("title must match").isEqualTo(firstCreatePayload.get("title"));
        assertThat(newest.get("issuerBy")).as("issuerBy must match").isEqualTo(firstCreatePayload.get("issuerBy"));

        StudentProfileJourneyReport.checkpoint(context, "First Accomplishment Created",
                "uuid: " + firstAccomplishmentUuid,
                "title: " + firstCreatePayload.get("title"),
                "issuerBy: " + firstCreatePayload.get("issuerBy"));
        log.info("Created first accomplishment {} for student {}", firstAccomplishmentUuid, studentUuid);
    }

    // ================= Stage 3: create second accomplishment =================

    public void createSecondAccomplishment(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        secondCreatePayload = buildCreatePayload(studentUuid);

        Response response = profileClient.createAccomplishment(studentUuid, secondCreatePayload, studentToken);
        assertThat(response.statusCode())
                .as("second accomplishment create must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> data = response.jsonPath().getList("Data");
        assertThat(data).as("Data must contain both accomplishments")
                .hasSizeGreaterThanOrEqualTo(2);

        Map<String, Object> newest = data.get(data.size() - 1);
        secondAccomplishmentUuid = (String) newest.get("uuid");
        assertThat(secondAccomplishmentUuid).as("second accomplishment must have a uuid").isNotBlank();
        assertThat(secondAccomplishmentUuid).as("second uuid must differ from first")
                .isNotEqualTo(firstAccomplishmentUuid);

        boolean firstStillPresent = data.stream()
                .anyMatch(a -> firstAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(firstStillPresent).as("first accomplishment must still be in the response").isTrue();

        StudentProfileJourneyReport.checkpoint(context, "Second Accomplishment Created",
                "uuid: " + secondAccomplishmentUuid,
                "title: " + secondCreatePayload.get("title"),
                "First accomplishment still present: " + firstStillPresent);
        log.info("Created second accomplishment {} for student {}", secondAccomplishmentUuid, studentUuid);
    }

    // ================= Stage 4: verify both in profile GET =================

    public void verifyBothInProfileGet(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> accomplishments = response.jsonPath().getList("generalData.Accomplishments");
        assertThat(accomplishments).as("Accomplishments must contain entries").isNotEmpty();

        boolean hasFirst = accomplishments.stream()
                .anyMatch(a -> firstAccomplishmentUuid.equals(a.get("uuid")));
        boolean hasSecond = accomplishments.stream()
                .anyMatch(a -> secondAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(hasFirst).as("first accomplishment must be in generalData.Accomplishments").isTrue();
        assertThat(hasSecond).as("second accomplishment must be in generalData.Accomplishments").isTrue();

        Map<String, Object> firstMatch = accomplishments.stream()
                .filter(a -> firstAccomplishmentUuid.equals(a.get("uuid"))).findFirst().orElse(null);
        assertThat(DateUtils.toCalendarDate(firstMatch.get("Date")))
                .as("first accomplishment Date must persist as the same calendar date")
                .isEqualTo(LocalDate.parse((String) firstCreatePayload.get("Date")));

        StudentProfileJourneyReport.checkpoint(context, "Both Accomplishments Persisted in Profile GET",
                "First: " + firstAccomplishmentUuid + " — present, Date verified",
                "Second: " + secondAccomplishmentUuid + " — present");
        log.info("Verified both accomplishments in profile GET");
    }

    // ================= Stage 5: update first accomplishment =================

    public void updateFirstAccomplishment(ScenarioContext context) {
        String studentToken = context.getStudentToken();

        updatePayload = buildUpdatePayload();

        Response response = profileClient.updateAccomplishment(firstAccomplishmentUuid, updatePayload, studentToken);
        assertThat(response.statusCode())
                .as("accomplishment update must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> data = response.jsonPath().getList("Data");
        assertThat(data).as("Data must not be empty after update").isNotEmpty();

        Map<String, Object> updated = data.stream()
                .filter(a -> firstAccomplishmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(updated).as("updated accomplishment must retain uuid").isNotNull();
        assertThat(updated.get("title")).as("title must be updated").isEqualTo(updatePayload.get("title"));
        assertThat(updated.get("Description")).as("Description must be updated")
                .isEqualTo(updatePayload.get("Description"));

        boolean secondStillPresent = data.stream()
                .anyMatch(a -> secondAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(secondStillPresent).as("second accomplishment must be unaffected by update").isTrue();

        StudentProfileJourneyReport.checkpoint(context, "First Accomplishment Updated",
                "uuid unchanged: " + firstAccomplishmentUuid,
                "title: " + updatePayload.get("title"),
                "Description: " + updatePayload.get("Description"),
                "Second accomplishment unaffected: " + secondStillPresent);
        log.info("Updated first accomplishment {}", firstAccomplishmentUuid);
    }

    // ================= Stage 6: verify update persisted =================

    public void verifyUpdatePersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> accomplishments = response.jsonPath().getList("generalData.Accomplishments");
        Map<String, Object> match = accomplishments.stream()
                .filter(a -> firstAccomplishmentUuid.equals(a.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("updated accomplishment must exist in profile").isNotNull();
        assertThat(match.get("title")).as("updated title must persist").isEqualTo(updatePayload.get("title"));
        assertThat(DateUtils.toCalendarDate(match.get("Date")))
                .as("updated Date must persist as the same calendar date")
                .isEqualTo(LocalDate.parse((String) updatePayload.get("Date")));

        StudentProfileJourneyReport.checkpoint(context, "Update Persisted in Profile GET",
                "title, Description, Date match updated values");
        log.info("Verified update persistence for accomplishment {}", firstAccomplishmentUuid);
    }

    // ================= Stage 7: delete second accomplishment =================

    public void deleteSecondAccomplishment(ScenarioContext context) {
        String studentToken = context.getStudentToken();

        Map<String, Object> deletePayload = new LinkedHashMap<>();
        deletePayload.put("isDeleted", Boolean.TRUE);

        Response response = profileClient.updateAccomplishment(secondAccomplishmentUuid, deletePayload, studentToken);
        assertThat(response.statusCode())
                .as("accomplishment delete must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> remaining = response.jsonPath().getList("Data");
        boolean secondGone = remaining == null || remaining.stream()
                .noneMatch(a -> secondAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(secondGone).as("deleted accomplishment must not be in response").isTrue();

        boolean firstSurvived = remaining != null && remaining.stream()
                .anyMatch(a -> firstAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(firstSurvived).as("first accomplishment must survive the delete of second").isTrue();

        StudentProfileJourneyReport.checkpoint(context, "Second Accomplishment Deleted",
                "Deleted uuid: " + secondAccomplishmentUuid,
                "First accomplishment survived: " + firstSurvived);
        log.info("Deleted second accomplishment {}", secondAccomplishmentUuid);
    }

    // ================= Stage 8: verify deletion persisted =================

    public void verifyDeletionPersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> accomplishments = response.jsonPath().getList("generalData.Accomplishments");
        if (accomplishments == null) {
            accomplishments = new ArrayList<>();
        }

        boolean secondGone = accomplishments.stream()
                .noneMatch(a -> secondAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(secondGone).as("deleted accomplishment must not appear in profile GET").isTrue();

        boolean firstSurvived = accomplishments.stream()
                .anyMatch(a -> firstAccomplishmentUuid.equals(a.get("uuid")));
        assertThat(firstSurvived).as("first accomplishment must still exist").isTrue();

        StudentProfileJourneyReport.checkpoint(context, "Deletion Verified in Profile GET",
                "Second (" + secondAccomplishmentUuid + ") absent",
                "First (" + firstAccomplishmentUuid + ") still present");
        log.info("Verified deletion persistence — first survived, second gone");
    }

    public void completeAccomplishmentJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "ACCOMPLISHMENTS");
    }

    // ================= Internals =================

    private Map<String, Object> buildCreatePayload(String studentUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Date", LocalDate.now().minusDays(5).format(DATE_FMT));
        payload.put("Description", TestDataFactory.accomplishmentDescription());
        payload.put("eventLink", "");
        payload.put("issuerBy", TestDataFactory.accomplishmentIssuer());
        payload.put("status", "Pending");
        payload.put("title", TestDataFactory.accomplishmentTitle());
        payload.put("remarks", "");
        payload.put("uploadedFile", null);
        payload.put("uploadedPresentation", null);
        payload.put("createdBy", studentUuid);
        return payload;
    }

    private Map<String, Object> buildUpdatePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Date", LocalDate.now().minusDays(10).format(DATE_FMT));
        payload.put("Description", TestDataFactory.accomplishmentDescription());
        payload.put("eventLink", "");
        payload.put("issuerBy", TestDataFactory.accomplishmentIssuer());
        payload.put("status", "Pending");
        payload.put("title", TestDataFactory.accomplishmentTitle());
        payload.put("remarks", "");
        payload.put("uploadedFile", null);
        payload.put("uploadedPresentation", null);
        return payload;
    }
}
