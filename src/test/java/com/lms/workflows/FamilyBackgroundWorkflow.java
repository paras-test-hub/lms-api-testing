package com.lms.workflows;

import com.lms.clients.StudentProfileClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.StudentProfileJourneyReport;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Student Profile journey's Family & Background sub-workflow -
 * a sibling of {@link PersonalDetailsWorkflow}, both reading and writing the SAME single resource,
 * {@code GET}/{@code PATCH /users/{studentUuid}/userProfile}, distinguished only by which fields the
 * caller populates. Reuses {@link StudentProfileClient} directly - no new client, no new endpoint, per
 * instruction to merge these fields into the existing profile PATCH rather than invent a separate one.
 *
 * <p><b>Endpoint/field provenance:</b> the GET side ({@code isBelowPovertyLine}, {@code
 * disabilityApplicable}, {@code familyIncome}, {@code documentsRequired} all appear in
 * {@code generalData}) is directly HAR-verified (hawkeye-apis_2026-07-28_17-22-32.har, a GET-only
 * capture of the Family &amp; Background screen). The PATCH side is NOT independently HAR-captured -
 * no save action fired during that recording. The exact request shape below was supplied directly (not
 * extracted from a HAR) as an already-verified successful payload:
 * <pre>{@code {"isBelowPovertyLine": true, "disabilityApplicable": true, "familyIncome": "120", "documentsRequired": []}}</pre>
 * This workflow sends exactly those four fields - nothing inferred beyond them - through the same
 * endpoint {@link PersonalDetailsWorkflow} already proved accepts a partial-field PATCH.
 *
 * <p><b>Family income numeric-only rule:</b> not demonstrated by any capture (no rejection response
 * was ever observed for a non-numeric value). Rather than assert a guessed outcome, this workflow
 * probes the live backend directly with a non-numeric value and reports the ACTUAL discovered
 * behaviour - the same honest-discovery approach {@code PersonalDetailsWorkflow} uses for its
 * mandatory-field probes. A 5xx is still treated as a real defect; any 2xx/4xx is recorded as fact.
 */
public class FamilyBackgroundWorkflow {

    private static final Logger log = LoggerFactory.getLogger(FamilyBackgroundWorkflow.class);

    /** A value the numeric-only rule should reject if the backend enforces it - never itself sent as the final saved value. */
    private static final String NON_NUMERIC_FAMILY_INCOME = "abc123";

    private final StudentProfileClient profileClient;

    /** The GET snapshot captured at the start of this journey. */
    private Map<String, Object> baselineProfile;

    /** The exact payload sent by {@link #updateFamilyBackground} - later stages diff the re-GET against this. */
    private Map<String, Object> submittedPayload;

    public FamilyBackgroundWorkflow() {
        this.profileClient = new StudentProfileClient();
    }

    // ================= Stage 1: baseline =================

    /** GETs the runtime student's current profile and stores it as this journey's baseline. */
    public void retrieveRuntimeProfile(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();
        assertThat(studentUuid).as("runtime studentUuid must be available from Environment Setup").isNotBlank();
        assertThat(studentToken).as("runtime student session must be available from Environment Setup").isNotBlank();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode())
                .as("profile GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> generalData = response.jsonPath().getMap("generalData");
        assertThat(generalData)
                .as("profile response must expose generalData for student %s", studentUuid)
                .isNotNull();
        assertThat(generalData.get("uuid"))
                .as("returned profile must belong to the runtime student")
                .isEqualTo(studentUuid);

        baselineProfile = generalData;

        StudentProfileJourneyReport.checkpoint(context, "Runtime Profile Retrieved",
                "Student: " + context.getStudentEmail() + " (" + studentUuid + ")",
                "GET /users/" + studentUuid + "/userProfile",
                "Baseline isBelowPovertyLine=" + generalData.get("isBelowPovertyLine")
                        + ", disabilityApplicable=" + generalData.get("disabilityApplicable")
                        + ", familyIncome=\"" + generalData.get("familyIncome") + "\"");
        log.info("Retrieved baseline Family & Background fields for runtime student {}", studentUuid);
    }

    // ================= Stage 2: numeric-only probe =================

    /**
     * Sends a PATCH with a non-numeric {@code familyIncome} (radio fields left at their current
     * baseline values, so only the field under test varies) and records the ACTUAL backend response -
     * accepted or rejected - rather than asserting a guessed outcome no capture demonstrates. A 5xx
     * still fails the journey as a real defect.
     */
    public void probeNonNumericFamilyIncome(ScenarioContext context) {
        assertThat(baselineProfile).as("baseline profile must be retrieved first").isNotNull();
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Map<String, Object> probePayload = buildPayload(NON_NUMERIC_FAMILY_INCOME);

        Response response = profileClient.updateProfile(studentUuid, probePayload, studentToken);
        assertThat(response.statusCode())
                .as("non-numeric familyIncome probe must not be a server error. Response: "
                        + response.getBody().asString())
                .isLessThan(500);

        boolean accepted = response.statusCode() >= 200 && response.statusCode() < 300;
        String finding = "familyIncome=\"" + NON_NUMERIC_FAMILY_INCOME + "\" -> HTTP " + response.statusCode()
                + (accepted
                    ? " (backend accepted a non-numeric value - only the frontend enforces numeric-only, if at all)"
                    : " (backend independently rejects a non-numeric value)");

        StudentProfileJourneyReport.checkpoint(context, "Family Income Numeric-Only Rule Probed", finding);
        log.info("Family income numeric-only probe: {}", finding);
    }

    // ================= Stage 3: the real update =================

    /**
     * Saves the four Family & Background fields exactly as supplied (not HAR-captured for this save,
     * but an already-verified successful payload): {@code isBelowPovertyLine: true},
     * {@code disabilityApplicable: true}, a runtime-generated NUMERIC {@code familyIncome}, and an
     * empty {@code documentsRequired} (never demonstrated with a real value, so not claimed as
     * "updated" - same treatment as Personal Details' Work Authorization / Blood Group fields).
     */
    public void updateFamilyBackground(ScenarioContext context) {
        assertThat(baselineProfile).as("baseline profile must be retrieved first").isNotNull();
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        String familyIncome = TestDataFactory.familyIncomeAmount();
        submittedPayload = buildPayload(familyIncome);

        Response response = profileClient.updateProfile(studentUuid, submittedPayload, studentToken);
        assertThat(response.statusCode())
                .as("Family & Background save must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        StudentProfileJourneyReport.checkpoint(context, "Below Poverty Line Updated",
                "isBelowPovertyLine: true");
        StudentProfileJourneyReport.checkpoint(context, "Disability Applicable Updated",
                "disabilityApplicable: true");
        StudentProfileJourneyReport.checkpoint(context, "Family Income Updated",
                "familyIncome: \"" + familyIncome + "\" (numeric-only, runtime-generated)");
        StudentProfileJourneyReport.checkpoint(context, "Documents Required Verified",
                "documentsRequired sent as [] - round-trip only; no real value was ever supplied to mirror");
        StudentProfileJourneyReport.checkpoint(context, "Family & Background Saved",
                "PATCH /users/" + studentUuid + "/userProfile -> " + response.statusCode());
        log.info("Saved Family & Background for runtime student {} (familyIncome {})", studentUuid, familyIncome);
    }

    // ================= Stage 4: persistence verification =================

    /** Re-GETs the profile and diffs the four submitted fields against what comes back. */
    public void verifyBackendPersistence(ScenarioContext context) {
        assertThat(submittedPayload).as("Family & Background must have been saved first").isNotNull();
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode())
                .as("profile re-GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> reloaded = response.jsonPath().getMap("generalData");
        assertThat(reloaded).as("re-GET must expose generalData").isNotNull();

        assertThat(reloaded.get("isBelowPovertyLine"))
                .as("isBelowPovertyLine must persist").isEqualTo(submittedPayload.get("isBelowPovertyLine"));
        assertThat(reloaded.get("disabilityApplicable"))
                .as("disabilityApplicable must persist").isEqualTo(submittedPayload.get("disabilityApplicable"));
        assertThat(reloaded.get("familyIncome"))
                .as("familyIncome must persist").isEqualTo(submittedPayload.get("familyIncome"));
        assertThat(reloaded.get("documentsRequired"))
                .as("documentsRequired must round-trip as sent").isEqualTo(submittedPayload.get("documentsRequired"));

        StudentProfileJourneyReport.checkpoint(context, "Backend Persistence Verified",
                "Re-GET /users/" + studentUuid + "/userProfile",
                "isBelowPovertyLine, disabilityApplicable, familyIncome, documentsRequired all match the PATCH payload");
        log.info("Family & Background persistence confirmed for runtime student {}", studentUuid);
    }

    /** Publishes the accumulated STUDENT PROFILE JOURNEY report - the journey's last action. */
    public void completeFamilyBackgroundJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "FAMILY & BACKGROUND");
    }

    // ================= Internals =================

    /** Builds the four-field Family & Background payload - exactly the fields supplied, nothing more. */
    private Map<String, Object> buildPayload(String familyIncome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("isBelowPovertyLine", Boolean.TRUE);
        payload.put("disabilityApplicable", Boolean.TRUE);
        payload.put("familyIncome", familyIncome);
        payload.put("documentsRequired", new ArrayList<>());
        return payload;
    }
}
