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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Student Profile journey's first sub-workflow: Personal
 * Details. Consumes the runtime student created by {@code BootstrapWorkflow} (uuid + student token) -
 * never a hardcoded student - and drives the SAME single resource, {@code GET}/{@code PATCH
 * /users/{studentUuid}/userProfile}, that every future profile sub-workflow (Guardian, Address, Work
 * Experience, Education, Documents) will also read and write, differentiated only by which fields the
 * caller populates.
 *
 * <p>Endpoints and payload shapes verified from hawkeye-apis_2026-07-28_16-03-31.har (GET, a rejected
 * PATCH, an accepted PATCH, a re-GET) plus the accompanying UI action recording
 * hawkeye-session_2026-07-28_16-03-31.parasite.json, whose {@code *}-marked field labels are the only
 * source for which fields are mandatory (there is no backend schema to read this from).
 *
 * <p><b>No dropdown-lookup endpoint exists in either capture</b> - Gender/Blood Group/Nationality/
 * Country/Work Authorization options are embedded client-side. Country/Nationality/Gender use the
 * exact literal strings the HAR proved the backend accepts ({@link #COUNTRY}, {@link #NATIONALITY},
 * {@link #GENDER}); nothing here invents a value never seen in the capture.
 *
 * <p><b>Fields sent but never demonstrated with a non-empty value in the HAR</b> - Blood Group,
 * Work Authorization, Alternate Mobile Number, Alternate Email - are sent back exactly as GET
 * returned them (empty, in the capture) and verified to round-trip; Phase 1 does NOT claim these were
 * "updated" to a real value, since that was never proven possible.
 *
 * <p><b>Duplicate-parent-mobile negative case (HTTP 406) is deliberately NOT automated here.</b>
 * The HAR proves the rule exists, but reproducing it needs a mobile number already registered to a
 * real parent record - data this framework does not own. Automating it with the literal HAR value
 * would depend on uncontrolled seed data the framework was told never to depend on. It is deferred to
 * the Guardian Details phase, which will create a runtime parent with a KNOWN mobile number this
 * framework owns, making the 406 case deterministic and runtime-isolated. Phase 1's positive path
 * still treats a 406 on its own (random, extremely unlikely) mobile number as a possible outcome and
 * retries with a fresh number rather than either failing outright or assuming it can't happen.
 *
 * <p><b>No browser driver exists in this framework</b> (RestAssured + Cucumber only). The session
 * recording shows two "Save" clicks that fired no network call at all - proof the frontend blocks
 * submission client-side - but which field caused it is not recoverable from the recording. Phase 1's
 * "mandatory field" checkpoint therefore probes the BACKEND directly (send the field blank, observe
 * what actually comes back) rather than attempting to reproduce a UI-only block, and reports the
 * discovered behaviour honestly instead of asserting an assumed outcome.
 */
public class PersonalDetailsWorkflow {

    private static final Logger log = LoggerFactory.getLogger(PersonalDetailsWorkflow.class);

    /** HAR-proven literal - no lookup API exists to source this from. */
    private static final String COUNTRY = "Aland Islands";
    /** HAR-proven literal - no lookup API exists to source this from. */
    private static final String NATIONALITY = "Åland Islander";
    /** HAR-proven literal - the only gender string ever observed in the capture (never changed). */
    private static final String GENDER = "Preferred not to say";
    /** HAR-proven literal - the only LinkedIn URL ever observed, submitted successfully. */
    private static final String LINKEDIN_URL = "https://linkedin.com/in/username";

    /** GET returns ISO instants (e.g. 2019-02-12T00:00:00.000Z); PATCH expects "12 Feb 2019". */
    private static final DateTimeFormatter DOB_OUTPUT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Mandatory fields per the session recording's asterisk-marked labels, EXCLUDING firstName/
     *  lastName - those are immutable, and this framework never attempts to force-change them, even
     *  transiently, to probe validation. */
    private static final List<String> MANDATORY_PROBE_FIELDS = List.of("dateOfBirth", "gender", "mobileNumber");

    private static final int MAX_MOBILE_COLLISION_RETRIES = 3;

    private final StudentProfileClient profileClient;

    /** The GET snapshot captured at the start of the journey - the baseline every later stage reads from. */
    private Map<String, Object> baselineProfile;

    /** Captured once from the baseline so immutable-field checks never re-derive it mid-journey. */
    private String originalFirstName;
    private String originalLastName;

    /** The exact payload sent by {@link #updatePersonalDetails} - later stages diff the re-GET against this. */
    private Map<String, Object> submittedPayload;

    public PersonalDetailsWorkflow() {
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
        log.info("Retrieved baseline profile for runtime student {}", studentUuid);
    }

    // ================= Stage 2: immutable fields =================

    /**
     * Captures firstName/lastName from the baseline and asserts they are present - the runtime
     * student must have both set at creation for this check to be meaningful. The actual "protected"
     * proof (unchanged after a save) is completed later by {@link #verifyBackendPersistence}, which
     * diffs the post-update GET against these captured values.
     */
    public void verifyImmutableFieldsProtected(ScenarioContext context) {
        assertThat(baselineProfile).as("baseline profile must be retrieved first").isNotNull();

        originalFirstName = stringOrNull(baselineProfile, "firstName");
        originalLastName = stringOrNull(baselineProfile, "lastName");

        assertThat(originalFirstName).as("runtime student must have a firstName set at creation").isNotBlank();
        assertThat(originalLastName).as("runtime student must have a lastName set at creation").isNotBlank();

        StudentProfileJourneyReport.checkpoint(context, "Immutable Fields Verified",
                "firstName=\"" + originalFirstName + "\", lastName=\"" + originalLastName + "\"",
                "Captured as baseline - re-checked unchanged after save by the persistence stage");
    }

    // ================= Stage 3: mandatory-field probe =================

    /**
     * Probes the BACKEND directly for each mandatory field that is not also immutable
     * ({@link #MANDATORY_PROBE_FIELDS}): sends an otherwise-valid payload with just that one field
     * blanked and records what actually comes back. This does not assert a specific expected outcome
     * - the HAR only proves the FRONTEND blocks submission (no network call fired), not what the
     * backend itself would do, so asserting a guessed status code here would be inventing a
     * validation rule. A 5xx is still treated as a real defect and fails the journey; any 2xx/4xx is
     * recorded as the discovered, factual backend behaviour for that field.
     */
    public void verifyMandatoryFieldEnforcement(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        List<String> findings = new ArrayList<>();
        for (String field : MANDATORY_PROBE_FIELDS) {
            Map<String, Object> probePayload = buildValidPayload(TestDataFactory.mobileNumber());
            probePayload.put(field, "");

            Response response = profileClient.updateProfile(studentUuid, probePayload, studentToken);
            assertThat(response.statusCode())
                    .as("mandatory-field probe for '%s' must not be a server error. Response: %s",
                            field, response.getBody().asString())
                    .isLessThan(500);

            String finding = field + " blank -> HTTP " + response.statusCode()
                    + (response.statusCode() >= 200 && response.statusCode() < 300
                        ? " (backend accepted it - only the frontend enforces this field)"
                        : " (backend independently rejects it)");
            findings.add(finding);
            log.info("Mandatory-field probe: {}", finding);
        }

        StudentProfileJourneyReport.checkpoint(context, "Mandatory Field Validation Verified",
                findings.toArray(new String[0]));
        StudentProfileJourneyReport.checkpoint(context, "Immutable Fields Excluded From Probe",
                "firstName/lastName never blanked, even transiently - the HAR never shows the frontend "
                        + "attempting to change them, so this framework doesn't either");
    }

    // ================= Stage 4: the real update =================

    /**
     * Builds the HAR-shaped Personal Details payload and saves it. The mobile number is a fresh
     * runtime-unique value ({@link TestDataFactory#mobileNumber()}), never the HAR's own captured
     * number - if the backend reports a 406 duplicate-parent collision (the same real rule the HAR
     * demonstrated, just possible on any random number), this retries with a new number up to
     * {@value #MAX_MOBILE_COLLISION_RETRIES} times rather than failing on a false negative.
     */
    public void updatePersonalDetails(ScenarioContext context) {
        assertThat(baselineProfile).as("baseline profile must be retrieved first").isNotNull();
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = null;
        String mobileNumber = null;
        int attempt = 0;
        while (attempt < MAX_MOBILE_COLLISION_RETRIES) {
            attempt++;
            mobileNumber = TestDataFactory.mobileNumber();
            submittedPayload = buildValidPayload(mobileNumber);

            response = profileClient.updateProfile(studentUuid, submittedPayload, studentToken);
            if (response.statusCode() == 406) {
                log.warn("Runtime mobile number {} collided with an existing parent record on attempt {}/{} - "
                        + "generating a fresh number and retrying (the same real 406 rule the HAR demonstrated; "
                        + "a random collision here is expected to be rare, not a defect).",
                        mobileNumber, attempt, MAX_MOBILE_COLLISION_RETRIES);
                continue;
            }
            break;
        }

        assertThat(response).as("update call must have run at least once").isNotNull();
        assertThat(response.statusCode())
                .as("Personal Details save must succeed after up to %d attempts (last mobile number tried: %s). "
                        + "Response: %s", MAX_MOBILE_COLLISION_RETRIES, mobileNumber, response.getBody().asString())
                .isBetween(200, 299);

        StudentProfileJourneyReport.checkpoint(context, "Mobile Number Uniqueness Handled",
                "Runtime mobile number: " + mobileNumber + " (attempt " + attempt + "/" + MAX_MOBILE_COLLISION_RETRIES + ")",
                "Duplicate-parent-mobile negative case (HTTP 406, HAR-proven) deferred to the Guardian "
                        + "Details phase, which will own a runtime parent record with a known mobile number");
        StudentProfileJourneyReport.checkpoint(context, "LinkedIn Updated",
                "linkedInUrl: \"" + LINKEDIN_URL + "\"");
        StudentProfileJourneyReport.checkpoint(context, "Country Updated",
                "country: \"" + COUNTRY + "\"");
        StudentProfileJourneyReport.checkpoint(context, "Nationality Updated",
                "nationality: \"" + NATIONALITY + "\"");
        StudentProfileJourneyReport.checkpoint(context, "Work Authorization Verified",
                "workAuthorizationValues sent as {selected:[], homeCountries:[]} - round-trip only; "
                        + "the HAR never demonstrated a non-empty value being accepted");
        StudentProfileJourneyReport.checkpoint(context, "Personal Details Saved",
                "PATCH /users/" + studentUuid + "/userProfile -> " + response.statusCode(),
                "gender: \"" + GENDER + "\", dateOfBirth: \"" + submittedPayload.get("dateOfBirth") + "\"");
        log.info("Saved Personal Details for runtime student {} (mobile {})", studentUuid, mobileNumber);
    }

    // ================= Stage 5: persistence verification =================

    /** Re-GETs the profile and diffs every field this journey sent against what comes back. */
    @SuppressWarnings("unchecked")
    public void verifyBackendPersistence(ScenarioContext context) {
        assertThat(submittedPayload).as("Personal Details must have been saved first").isNotNull();
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode())
                .as("profile re-GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> reloaded = response.jsonPath().getMap("generalData");
        assertThat(reloaded).as("re-GET must expose generalData").isNotNull();

        assertThat(reloaded.get("firstName")).as("firstName must remain unchanged").isEqualTo(originalFirstName);
        assertThat(reloaded.get("lastName")).as("lastName must remain unchanged").isEqualTo(originalLastName);

        assertThat(reloaded.get("mobileNumber")).as("mobileNumber must persist").isEqualTo(submittedPayload.get("mobileNumber"));
        assertThat(reloaded.get("country")).as("country must persist").isEqualTo(submittedPayload.get("country"));
        assertThat(reloaded.get("nationality")).as("nationality must persist").isEqualTo(submittedPayload.get("nationality"));
        assertThat(reloaded.get("gender")).as("gender must persist").isEqualTo(submittedPayload.get("gender"));
        assertThat(reloaded.get("linkedInUrl")).as("linkedInUrl must persist").isEqualTo(submittedPayload.get("linkedInUrl"));
        assertThat(reloaded.get("bloodGroup")).as("bloodGroup must round-trip unchanged").isEqualTo(submittedPayload.get("bloodGroup"));
        assertThat(reloaded.get("alternateMobileNumber")).as("alternateMobileNumber must round-trip unchanged")
                .isEqualTo(submittedPayload.get("alternateMobileNumber"));
        assertThat(reloaded.get("alternateEmail")).as("alternateEmail must round-trip unchanged")
                .isEqualTo(submittedPayload.get("alternateEmail"));

        Object reloadedWorkAuth = reloaded.get("workAuthorizationValues");
        assertThat(reloadedWorkAuth).as("workAuthorizationValues must be present after reload").isInstanceOf(Map.class);
        Map<String, Object> workAuthMap = (Map<String, Object>) reloadedWorkAuth;
        assertThat(workAuthMap.get("selected")).as("workAuthorizationValues.selected must round-trip empty").isEqualTo(List.of());
        assertThat(workAuthMap.get("homeCountries")).as("workAuthorizationValues.homeCountries must round-trip empty").isEqualTo(List.of());

        LocalDate sentDob = LocalDate.parse((String) submittedPayload.get("dateOfBirth"), DOB_OUTPUT);
        LocalDate reloadedDob = DateUtils.toCalendarDate(reloaded.get("dateOfBirth"));
        assertThat(reloadedDob)
                .as("dateOfBirth must persist as the same calendar date sent (" + submittedPayload.get("dateOfBirth") + ")")
                .isEqualTo(sentDob);

        StudentProfileJourneyReport.checkpoint(context, "Backend Persistence Verified",
                "Re-GET /users/" + studentUuid + "/userProfile",
                "Every submitted field (mobile, country, nationality, gender, LinkedIn, DOB) matches the PATCH payload",
                "Immutable fields (firstName, lastName) confirmed unchanged");
        log.info("Backend persistence confirmed for runtime student {}", studentUuid);
    }

    /**
     * A SECOND, independent GET - the closest honest proxy available to this API-only framework
     * (RestAssured + Cucumber, no browser driver) for "the UI displays the updated values." It cannot
     * verify screen rendering; what it CAN verify is that the backend consistently returns the same
     * values on a fresh, separate read, not just immediately after the save.
     */
    public void verifySecondIndependentRead(ScenarioContext context) {
        assertThat(submittedPayload).as("Personal Details must have been saved first").isNotNull();
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode())
                .as("second independent GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        Map<String, Object> reloaded = response.jsonPath().getMap("generalData");
        assertThat(reloaded.get("mobileNumber")).isEqualTo(submittedPayload.get("mobileNumber"));
        assertThat(reloaded.get("country")).isEqualTo(submittedPayload.get("country"));
        assertThat(reloaded.get("nationality")).isEqualTo(submittedPayload.get("nationality"));

        StudentProfileJourneyReport.checkpoint(context, "Second Independent Read Verified",
                "A separate GET call (not the same response object used for the first check) still "
                        + "returns identical values",
                "This framework has no browser driver - literal UI rendering is out of scope; this is "
                        + "the closest API-level proof available");
    }

    /** Publishes the accumulated STUDENT PROFILE JOURNEY report - the journey's last action. */
    public void completePersonalDetailsJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "PERSONAL DETAILS");
    }

    // ================= Internals =================

    /**
     * Builds the full Personal Details PATCH payload, mirroring the captured shape field-for-field.
     * {@code mobileNumber} is the only field the caller varies per call (mandatory-field probes reuse
     * this with one field then blanked; the real save uses it as-is).
     */
    private Map<String, Object> buildValidPayload(String mobileNumber) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("initialAdditions", stringOrEmpty(baselineProfile, "initialAdditions"));
        payload.put("firstName", originalFirstName);
        payload.put("lastName", originalLastName);
        payload.put("dateOfBirth", resolveDateOfBirth());
        payload.put("alternateEmail", stringOrEmpty(baselineProfile, "alternateEmail"));
        payload.put("gender", GENDER);
        payload.put("bloodGroup", stringOrEmpty(baselineProfile, "bloodGroup"));
        payload.put("nationality", NATIONALITY);
        payload.put("country", COUNTRY);
        payload.put("countryCode", defaultIfBlank(stringOrEmpty(baselineProfile, "countryCode"), "+91"));
        payload.put("mobileNumber", mobileNumber);
        payload.put("studentCountryCode", stringOrEmpty(baselineProfile, "studentCountryCode"));
        payload.put("alternateMobileNumber", stringOrEmpty(baselineProfile, "alternateMobileNumber"));
        payload.put("linkedInUrl", LINKEDIN_URL);

        Map<String, Object> workAuth = new LinkedHashMap<>();
        workAuth.put("selected", new ArrayList<>());
        workAuth.put("homeCountries", new ArrayList<>());
        payload.put("workAuthorizationValues", workAuth);

        return payload;
    }

    /**
     * The baseline's existing dateOfBirth (reformatted from ISO to "d MMM yyyy"), or - for a fresh
     * runtime student who has none yet - a generated past date. No age or future-date rule is
     * invented; the HAR never demonstrated one, so none is enforced here.
     */
    private String resolveDateOfBirth() {
        Object raw = baselineProfile.get("dateOfBirth");
        LocalDate dob;
        if (raw == null) {
            dob = LocalDate.now().minusYears(20);
        } else {
            dob = DateUtils.toCalendarDate(raw);
        }
        return DOB_OUTPUT.format(dob);
    }

    private String stringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String stringOrEmpty(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
