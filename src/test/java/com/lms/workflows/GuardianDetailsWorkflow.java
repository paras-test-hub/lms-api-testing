package com.lms.workflows;

import com.lms.clients.StudentProfileClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.StudentProfileJourneyReport;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Student Profile journey's Guardian Details sub-workflow - a
 * sibling of {@link PersonalDetailsWorkflow} / {@link FamilyBackgroundWorkflow}, all reading and
 * writing the SAME single resource, {@code GET}/{@code PATCH /users/{studentUuid}/userProfile}.
 * Reuses {@link StudentProfileClient} directly - no new client, no new endpoint.
 *
 * <p><b>The guardian model is fixed at exactly two slots - Father and Mother</b> - flat fields on the
 * same profile object ({@code fatherName}, {@code fatherNameEmail}, {@code fatherNameNumber},
 * {@code fatherNameOccupation}, {@code fatherNameDesignation}, {@code fatherNameCompany},
 * {@code fatherCountryCode}, and the {@code motherName*} equivalents), NOT an arbitrary N-guardian
 * list - confirmed both from the GET response's field shape and from the captured PATCH bodies, which
 * never send an array. "Multiple guardians" in this journey means adding both fixed slots, one PATCH
 * each, exactly mirroring the capture's own sequence.
 *
 * <p>Endpoints and payload shapes verified from hawkeye-apis_2026-07-28_17-56-40.har - THREE
 * consecutive {@code PATCH}es against the same student, each followed by a {@code GET}:
 * <ol>
 *   <li>Add Guardian 1 (Father) - mother fields sent empty</li>
 *   <li>Add Guardian 2 (Mother) - father fields re-sent UNCHANGED; mother's mobile number sent as a
 *       single digit ("8"), which the backend accepted (200) - proof this endpoint does not
 *       independently validate guardian mobile-number format, so this workflow probes that discovery
 *       rather than asserting a rejection the capture never shows</li>
 *   <li>Update Guardian Info - the SAME endpoint again, only {@code motherNameNumber} changed from
 *       the malformed value to a valid one; everything else re-sent identically. This IS the "update"
 *       API the same {@code PATCH} the create calls use, distinguished only by the guardian already
 *       having data</li>
 * </ol>
 *
 * <p><b>{@code fatherCountryCode}/{@code motherCountryCode} are never demonstrated with a real
 * value</b> - the capture sends {@code ""} in all three calls - so this workflow does the same and
 * verifies only that they round-trip, per the same "not demonstrated by HAR" treatment used for
 * Personal Details' Work Authorization / Blood Group.
 *
 * <p><b>This is a DIFFERENT field family from Personal Details' top-level {@code mobileNumber}</b> -
 * the GET response's {@code parentsMobileNumber}/{@code parentsEmail}/{@code parentsCountryCode}
 * (the fields behind the "Parent with this mobile number already exists" 406 seen there) stay
 * {@code null} throughout this entire capture, confirming the Guardian Details {@code fatherName*}/
 * {@code motherName*} fields are unrelated display data, not the same uniqueness-checked parent
 * account record. No 406 collision handling is needed here.
 */
public class GuardianDetailsWorkflow {

    private static final Logger log = LoggerFactory.getLogger(GuardianDetailsWorkflow.class);

    /**
     * A deliberately too-short mobile number, mirroring the HAR's own captured value ("8") - safe to
     * hardcode: a single digit cannot realistically collide with a real record, and this field family
     * (unlike Personal Details' mobileNumber) was never shown enforcing a uniqueness or format rule.
     */
    private static final String INTENTIONALLY_SHORT_MOTHER_NUMBER = "8";

    private final StudentProfileClient profileClient;

    private Map<String, Object> baselineProfile;

    // Father fields - generated once, then re-sent unchanged across every subsequent PATCH, exactly
    // as the capture does.
    private String fatherName;
    private String fatherEmail;
    private String fatherNumber;
    private String fatherOccupation;
    private String fatherDesignation;
    private String fatherCompany;

    // Mother fields - name/email/occupation/designation/company generated once; the mobile number
    // varies across the "add" and "update" stages.
    private String motherName;
    private String motherEmail;
    private String motherOccupation;
    private String motherDesignation;
    private String motherCompany;
    private String motherValidNumber;

    /** The exact payload most recently sent - later stages diff the re-GET against this. */
    private Map<String, Object> submittedPayload;

    public GuardianDetailsWorkflow() {
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
                "GET /users/" + studentUuid + "/userProfile");
        log.info("Retrieved baseline Guardian Details for runtime student {}", studentUuid);
    }

    // ================= Stage 2: Guardian 1 (Father) =================

    /** Adds Guardian 1 (Father) - mother fields explicitly sent empty, mirroring the capture. */
    public void addFirstGuardianFather(ScenarioContext context) {
        assertThat(baselineProfile).as("baseline profile must be retrieved first").isNotNull();

        fatherName = TestDataFactory.guardianName();
        fatherEmail = TestDataFactory.guardianEmail();
        fatherNumber = TestDataFactory.mobileNumber();
        fatherOccupation = TestDataFactory.guardianOccupation();
        fatherDesignation = TestDataFactory.guardianDesignation();
        fatherCompany = TestDataFactory.guardianCompany();

        submittedPayload = buildPayload(false, null);
        Response response = save(context, submittedPayload);
        assertThat(response.statusCode())
                .as("Guardian 1 (Father) save must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> reloaded = reload(context);
        assertGuardianMatches(reloaded, submittedPayload);

        StudentProfileJourneyReport.checkpoint(context, "Guardian 1 (Father) Added",
                "Name: " + fatherName, "Email: " + fatherEmail, "Mobile: " + fatherNumber,
                "Mother fields confirmed still empty");
        log.info("Added Guardian 1 (Father) {} for runtime student {}", fatherName, context.getStudentUuid());
    }

    // ================= Stage 3: Guardian 2 (Mother) =================

    /**
     * Adds Guardian 2 (Mother) with a deliberately malformed mobile number, mirroring the capture.
     * Father's fields are re-sent unchanged - the save must not corrupt Guardian 1. Reports the
     * discovered fact that the backend accepts the malformed number (a 5xx would still fail as a
     * real defect); no HAR evidence supports asserting it should be rejected.
     */
    public void addSecondGuardianMother(ScenarioContext context) {
        assertThat(fatherName).as("Guardian 1 (Father) must be added first").isNotNull();

        motherName = TestDataFactory.guardianName();
        motherEmail = TestDataFactory.guardianEmail();
        motherOccupation = TestDataFactory.guardianOccupation();
        motherDesignation = TestDataFactory.guardianDesignation();
        motherCompany = TestDataFactory.guardianCompany();

        submittedPayload = buildPayload(true, INTENTIONALLY_SHORT_MOTHER_NUMBER);
        Response response = save(context, submittedPayload);
        assertThat(response.statusCode())
                .as("Guardian 2 (Mother) save must not be a server error. Response: " + response.getBody().asString())
                .isLessThan(500);

        boolean accepted = response.statusCode() >= 200 && response.statusCode() < 300;
        assertThat(accepted)
                .as("Guardian 2 (Mother) save with a malformed mobile number returned %s - if this ever starts "
                        + "being rejected, the workflow's own understanding (backend does not format-validate "
                        + "guardian mobile numbers) needs updating, not just this assertion", response.statusCode())
                .isTrue();

        Map<String, Object> reloaded = reload(context);
        assertGuardianMatches(reloaded, submittedPayload);
        assertFatherUnchanged(reloaded);

        StudentProfileJourneyReport.checkpoint(context, "Guardian 2 (Mother) Added",
                "Name: " + motherName, "Email: " + motherEmail,
                "Mobile sent as \"" + INTENTIONALLY_SHORT_MOTHER_NUMBER + "\" (intentionally malformed) -> HTTP "
                        + response.statusCode() + " (backend does not independently format-validate this field)",
                "Guardian 1 (Father) confirmed unchanged by this save");
        log.info("Added Guardian 2 (Mother) {} for runtime student {} (malformed mobile accepted)",
                motherName, context.getStudentUuid());
    }

    // ================= Stage 4: update info =================

    /**
     * Updates Guardian 2's mobile number to a valid runtime-unique value via the SAME {@code PATCH}
     * endpoint used to create - the "update info" API is not a separate endpoint. Both guardians'
     * other fields are re-sent unchanged and re-verified.
     */
    public void updateGuardianMobileNumber(ScenarioContext context) {
        assertThat(motherName).as("Guardian 2 (Mother) must be added first").isNotNull();

        motherValidNumber = TestDataFactory.mobileNumber();
        submittedPayload = buildPayload(true, motherValidNumber);
        Response response = save(context, submittedPayload);
        assertThat(response.statusCode())
                .as("Guardian info update must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        StudentProfileJourneyReport.checkpoint(context, "Guardian Info Updated",
                "PATCH /users/" + context.getStudentUuid() + "/userProfile -> " + response.statusCode(),
                "motherNameNumber corrected: \"" + INTENTIONALLY_SHORT_MOTHER_NUMBER + "\" -> \"" + motherValidNumber + "\"",
                "Same endpoint used to create - no separate update API exists");
        log.info("Updated Guardian 2 (Mother) mobile number to {} for runtime student {}",
                motherValidNumber, context.getStudentUuid());
    }

    // ================= Stage 5: final persistence verification =================

    /**
     * Re-GETs the profile and diffs every guardian field against the last submitted payload,
     * confirming the update landed AND that both guardians' unrelated fields are still intact.
     */
    public void verifyBackendPersistence(ScenarioContext context) {
        assertThat(submittedPayload).as("Guardian info must have been updated first").isNotNull();

        Map<String, Object> reloaded = reload(context);
        assertGuardianMatches(reloaded, submittedPayload);
        assertFatherUnchanged(reloaded);

        assertThat(reloaded.get("motherNameEmail")).as("motherNameEmail must remain unchanged by the number update")
                .isEqualTo(motherEmail);
        assertThat(reloaded.get("motherNameOccupation")).as("motherNameOccupation must remain unchanged")
                .isEqualTo(motherOccupation);

        StudentProfileJourneyReport.checkpoint(context, "Backend Persistence Verified",
                "Re-GET /users/" + context.getStudentUuid() + "/userProfile",
                "Both Guardian 1 (Father) and Guardian 2 (Mother) match what was submitted",
                "Mother's updated mobile number persisted; her other fields untouched by the update");
        log.info("Guardian Details persistence confirmed for runtime student {}", context.getStudentUuid());
    }

    /** Publishes the accumulated STUDENT PROFILE JOURNEY report - the journey's last action. */
    public void completeGuardianDetailsJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "GUARDIAN DETAILS");
    }

    // ================= Internals =================

    private Response save(ScenarioContext context, Map<String, Object> payload) {
        return profileClient.updateProfile(context.getStudentUuid(), payload, context.getStudentToken());
    }

    private Map<String, Object> reload(ScenarioContext context) {
        Response response = profileClient.getProfile(context.getStudentUuid(), context.getStudentToken());
        assertThat(response.statusCode())
                .as("profile re-GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        Map<String, Object> generalData = response.jsonPath().getMap("generalData");
        assertThat(generalData).as("re-GET must expose generalData").isNotNull();
        return generalData;
    }

    /** Diffs every field this workflow controls against what the backend now reports. */
    private void assertGuardianMatches(Map<String, Object> reloaded, Map<String, Object> sent) {
        for (String field : sent.keySet()) {
            assertThat(reloaded.get(field))
                    .as("%s must persist as sent", field)
                    .isEqualTo(sent.get(field));
        }
    }

    /** Guardian 1 (Father) must never change once added, regardless of what happens to Guardian 2. */
    private void assertFatherUnchanged(Map<String, Object> reloaded) {
        assertThat(reloaded.get("fatherName")).as("fatherName must remain unchanged").isEqualTo(fatherName);
        assertThat(reloaded.get("fatherNameEmail")).as("fatherNameEmail must remain unchanged").isEqualTo(fatherEmail);
        assertThat(reloaded.get("fatherNameNumber")).as("fatherNameNumber must remain unchanged").isEqualTo(fatherNumber);
        assertThat(reloaded.get("fatherNameOccupation")).as("fatherNameOccupation must remain unchanged").isEqualTo(fatherOccupation);
        assertThat(reloaded.get("fatherNameDesignation")).as("fatherNameDesignation must remain unchanged").isEqualTo(fatherDesignation);
        assertThat(reloaded.get("fatherNameCompany")).as("fatherNameCompany must remain unchanged").isEqualTo(fatherCompany);
    }

    /**
     * Builds the fourteen-field Guardian Details payload, mirroring the captured shape field-for-
     * field. Father fields are always the values generated in {@link #addFirstGuardianFather} - this
     * method never regenerates them, so every call after that one re-sends Guardian 1 unchanged,
     * exactly as the capture does.
     */
    private Map<String, Object> buildPayload(boolean includeMother, String motherNumber) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fatherName", fatherName);
        payload.put("fatherNameEmail", fatherEmail);
        payload.put("fatherNameNumber", fatherNumber);
        payload.put("fatherNameOccupation", fatherOccupation);
        payload.put("fatherNameDesignation", fatherDesignation);
        payload.put("fatherNameCompany", fatherCompany);
        payload.put("fatherCountryCode", "");
        payload.put("motherName", includeMother ? motherName : "");
        payload.put("motherNameEmail", includeMother ? motherEmail : "");
        payload.put("motherNameNumber", includeMother ? motherNumber : "");
        payload.put("motherNameOccupation", includeMother ? motherOccupation : "");
        payload.put("motherNameDesignation", includeMother ? motherDesignation : "");
        payload.put("motherNameCompany", includeMother ? motherCompany : "");
        payload.put("motherCountryCode", "");
        return payload;
    }
}
