package com.lms.workflows;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lms.clients.StudentProfileClient;
import com.lms.context.ScenarioContext;
import com.lms.reporting.StudentProfileJourneyReport;
import com.lms.utils.TestDataFactory;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration ("service" layer) for the Student Profile journey's Address Details sub-workflow - a
 * sibling of {@link PersonalDetailsWorkflow} / {@link FamilyBackgroundWorkflow} /
 * {@link GuardianDetailsWorkflow}, but on a DIFFERENT endpoint from all three: {@code POST
 * /users/userProfile/{studentUuid}/address}, not the {@code PATCH /users/{studentUuid}/userProfile}
 * the others share. Verified from hawkeye-apis_2026-07-29_10-10-03.har.
 *
 * <p><b>FULL-REPLACE semantics, not per-item CRUD.</b> The request body's {@code userAddress} array
 * IS the entire desired address list - confirmed directly from the capture's own sequence: adding a
 * second address re-sent the first address unchanged alongside it; deleting an address meant
 * re-posting the array WITHOUT that entry. There is no per-address id/uuid anywhere in the request or
 * response - addresses are identified purely by position/content, which is why every mutation in this
 * workflow re-sends the FULL current array rather than a partial patch.
 *
 * <p><b>No dropdown-lookup evidence exists for any address field</b> (unlike Personal Details' Country/
 * Nationality/Gender, which a UI action recording proved were literal dropdown clicks). The capture
 * has no accompanying session recording, and it happily accepted garbage values (pincode "fdgsg",
 * city/state "34") with 201. Address field values here are therefore generated as plain runtime-
 * tagged free text, not mirrored HAR literals - there is no evidence any of them are a closed set.
 *
 * <p><b>Mandatory-field validation is NOT demonstrated by the HAR</b> - every captured payload had all
 * fields populated (or, for pincode/city/state/area, garbage but non-empty). This workflow probes the
 * live backend directly, one field blank at a time, and reports the ACTUAL discovered behaviour -
 * same honest-discovery approach as {@link PersonalDetailsWorkflow}'s mandatory-field probe. A 5xx is
 * still treated as a real defect.
 *
 * <p><b>"The first address cannot be deleted" is NOT demonstrated by the HAR either</b> - the capture
 * only shows the SECOND address being removed successfully; nobody attempted removing the first while
 * a second existed. A screenshot showed the frontend hides the delete icon for the first address, but
 * that only proves client-side enforcement. This workflow probes the backend directly (attempt to
 * post an array missing the first address) and reports the discovered outcome, then unconditionally
 * restores the correct two-address state before continuing - regardless of what the probe discovers.
 */
public class AddressDetailsWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AddressDetailsWorkflow.class);

    private static final List<String> ADDRESS_FIELDS =
            List.of("area", "houseNo", "pincode", "landmark", "residentialAddress", "city", "state", "country");

    private static final Type ADDRESS_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() { }.getType();
    private static final Gson GSON = new Gson();

    private final StudentProfileClient profileClient;

    private Map<String, Object> firstAddress;
    private Map<String, Object> secondAddress;

    public AddressDetailsWorkflow() {
        this.profileClient = new StudentProfileClient();
    }

    // ================= Stage 1: baseline =================

    /** GETs the runtime student's current profile, confirming it belongs to the runtime student. */
    public void retrieveRuntimeProfile(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();
        assertThat(studentUuid).as("runtime studentUuid must be available from Environment Setup").isNotBlank();
        assertThat(studentToken).as("runtime student session must be available from Environment Setup").isNotBlank();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode())
                .as("profile GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        assertThat(response.jsonPath().getString("generalData.uuid"))
                .as("returned profile must belong to the runtime student")
                .isEqualTo(studentUuid);

        StudentProfileJourneyReport.checkpoint(context, "Runtime Profile Retrieved",
                "Student: " + context.getStudentEmail() + " (" + studentUuid + ")",
                "GET /users/" + studentUuid + "/userProfile");
        log.info("Retrieved baseline profile for Address Details, runtime student {}", studentUuid);
    }

    // ================= Stage 2: first address =================

    /** Adds the FIRST address - the one later protected from deletion. */
    public void addFirstAddress(ScenarioContext context) {
        firstAddress = buildAddress("Address 1", false);

        Response response = save(context, List.of(firstAddress));
        assertThat(response.statusCode())
                .as("first address save must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress));

        StudentProfileJourneyReport.checkpoint(context, "First Address Added",
                "residentialAddress: " + firstAddress.get("residentialAddress"),
                "isPermanent: false");
        log.info("Added first address for runtime student {}", context.getStudentUuid());
    }

    /** Marks the first address as permanent - an "update" via the same full-replace POST. */
    public void markFirstAddressPermanent(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();
        firstAddress.put("isPermanent", Boolean.TRUE);

        Response response = save(context, List.of(firstAddress));
        assertThat(response.statusCode())
                .as("marking the first address permanent must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress));

        StudentProfileJourneyReport.checkpoint(context, "First Address Marked Permanent", "isPermanent: true");
        log.info("Marked first address permanent for runtime student {}", context.getStudentUuid());
    }

    /** Unmarks the first address as permanent - proving the toggle works in both directions. */
    public void unmarkFirstAddressPermanent(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();
        firstAddress.put("isPermanent", Boolean.FALSE);

        Response response = save(context, List.of(firstAddress));
        assertThat(response.statusCode())
                .as("unmarking the first address permanent must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress));

        StudentProfileJourneyReport.checkpoint(context, "First Address Unmarked Permanent", "isPermanent: false");
        log.info("Unmarked first address permanent for runtime student {}", context.getStudentUuid());
    }

    // ================= Stage 3: second address (multiple addresses) =================

    /** Adds the SECOND address - the first is re-sent unchanged alongside it, mirroring the capture. */
    public void addSecondAddress(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();
        secondAddress = buildAddress("Address 2", false);

        Response response = save(context, List.of(firstAddress, secondAddress));
        assertThat(response.statusCode())
                .as("second address save must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress, secondAddress));

        StudentProfileJourneyReport.checkpoint(context, "Second Address Added",
                "residentialAddress: " + secondAddress.get("residentialAddress"),
                "First address confirmed unaffected");
        log.info("Added second address for runtime student {}", context.getStudentUuid());
    }

    /** Marks the second address permanent - the first must stay at its own current value (false), untouched. */
    public void markSecondAddressPermanent(ScenarioContext context) {
        assertThat(secondAddress).as("second address must be added first").isNotNull();
        secondAddress.put("isPermanent", Boolean.TRUE);

        Response response = save(context, List.of(firstAddress, secondAddress));
        assertThat(response.statusCode())
                .as("marking the second address permanent must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress, secondAddress));

        StudentProfileJourneyReport.checkpoint(context, "Second Address Marked Permanent",
                "isPermanent: true", "First address's own isPermanent (false) confirmed unaffected");
        log.info("Marked second address permanent for runtime student {}", context.getStudentUuid());
    }

    // ================= Stage 4: mandatory-field probe =================

    /**
     * Probes the BACKEND directly for each address field: saves a SINGLE-item array built from the
     * first address with just that one field blanked, and records what actually comes back. No
     * capture demonstrates a rejection for any address field, so this does not assert a specific
     * expected outcome - only that the backend never 5xxs. The real two-address state is restored
     * (and re-verified) once every field has been probed.
     */
    public void probeMandatoryFieldEnforcement(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();
        assertThat(secondAddress).as("second address must be added first").isNotNull();

        List<String> findings = new ArrayList<>();
        for (String field : ADDRESS_FIELDS) {
            Map<String, Object> probeAddress = new LinkedHashMap<>(firstAddress);
            probeAddress.put(field, "");

            Response response = save(context, List.of(probeAddress));
            assertThat(response.statusCode())
                    .as("mandatory-field probe for '%s' must not be a server error. Response: %s",
                            field, response.getBody().asString())
                    .isLessThan(500);

            String finding = field + " blank -> HTTP " + response.statusCode()
                    + (response.statusCode() >= 200 && response.statusCode() < 300
                        ? " (backend accepted it - no independent backend validation for this field)"
                        : " (backend independently rejects it)");
            findings.add(finding);
            log.info("Address mandatory-field probe: {}", finding);
        }

        // Restore the real two-address state - the probes above each replaced the whole array.
        Response restore = save(context, List.of(firstAddress, secondAddress));
        assertThat(restore.statusCode())
                .as("restoring the real address state after probing must succeed. Response: "
                        + restore.getBody().asString())
                .isBetween(200, 299);
        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress, secondAddress));

        StudentProfileJourneyReport.checkpoint(context, "Mandatory Field Validation Probed",
                findings.toArray(new String[0]));
        StudentProfileJourneyReport.checkpoint(context, "Address State Restored After Probing",
                "Both addresses confirmed intact after the mandatory-field probe loop");
    }

    // ================= Stage 5: first-address deletion protection =================

    /**
     * Attempts to delete the FIRST address by posting an array that omits it (keeping only the
     * second) - the specific scenario no capture demonstrates. Records the discovered outcome
     * honestly (accepted = only the frontend enforces the "can't delete the first address" rule;
     * rejected = the backend independently enforces it) rather than asserting an assumed result. The
     * real two-address state is unconditionally restored afterward, regardless of the outcome.
     */
    public void probeFirstAddressDeletionProtection(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();
        assertThat(secondAddress).as("second address must be added first").isNotNull();

        Response response = save(context, List.of(secondAddress));
        assertThat(response.statusCode())
                .as("first-address-deletion probe must not be a server error. Response: "
                        + response.getBody().asString())
                .isLessThan(500);

        boolean accepted = response.statusCode() >= 200 && response.statusCode() < 300;
        String finding = "Removing the first address (keeping only the second) -> HTTP " + response.statusCode()
                + (accepted
                    ? " (backend ACCEPTED it - this rule is enforced by the frontend only, e.g. hiding the delete "
                        + "icon; it is not independently backend-validated)"
                    : " (backend independently REJECTS deleting the first address)");
        log.info("First-address deletion protection probe: {}", finding);

        // Unconditionally restore, regardless of what the probe discovered.
        Response restore = save(context, List.of(firstAddress, secondAddress));
        assertThat(restore.statusCode())
                .as("restoring the real address state after the deletion probe must succeed. Response: "
                        + restore.getBody().asString())
                .isBetween(200, 299);
        assertAddressesMatch(fetchAddresses(context), List.of(firstAddress, secondAddress));

        StudentProfileJourneyReport.checkpoint(context, "First Address Deletion Protection Probed", finding);
        StudentProfileJourneyReport.checkpoint(context, "Address State Restored After Probing",
                "Both addresses confirmed intact after the deletion-protection probe");
    }

    // ================= Stage 6: the real, HAR-proven delete =================

    /** Deletes the second address - the exact operation the HAR captures succeeding. */
    public void deleteSecondAddress(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();
        assertThat(secondAddress).as("second address must be added first").isNotNull();

        Response response = save(context, List.of(firstAddress));
        assertThat(response.statusCode())
                .as("deleting the second address must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        StudentProfileJourneyReport.checkpoint(context, "Second Address Deleted",
                "POST /users/userProfile/" + context.getStudentUuid() + "/address -> " + response.statusCode(),
                "Only the first address remains in the submitted array");
        log.info("Deleted second address for runtime student {}", context.getStudentUuid());
    }

    // ================= Stage 7: final persistence verification =================

    /** Re-GETs the profile and confirms exactly the first address remains, matching its current fields. */
    public void verifyBackendPersistence(ScenarioContext context) {
        assertThat(firstAddress).as("first address must be added first").isNotNull();

        List<Map<String, Object>> reloaded = fetchAddresses(context);
        assertAddressesMatch(reloaded, List.of(firstAddress));

        StudentProfileJourneyReport.checkpoint(context, "Backend Persistence Verified",
                "Re-GET /users/" + context.getStudentUuid() + "/userProfile",
                "Exactly one address remains and matches the first address's current fields",
                "isPermanent: " + firstAddress.get("isPermanent"));
        log.info("Address Details persistence confirmed for runtime student {}", context.getStudentUuid());
    }

    /** Publishes the accumulated STUDENT PROFILE JOURNEY report - the journey's last action. */
    public void completeAddressDetailsJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "ADDRESS DETAILS");
    }

    // ================= Internals =================

    private Response save(ScenarioContext context, List<Map<String, Object>> addresses) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userAddress", addresses);
        payload.put("userId", context.getStudentUuid());
        return profileClient.updateAddresses(context.getStudentUuid(), payload, context.getStudentToken());
    }

    /** Re-GETs the profile and parses {@code generalData.userAddress} - a JSON-encoded STRING, not a native array. */
    private List<Map<String, Object>> fetchAddresses(ScenarioContext context) {
        Response response = profileClient.getProfile(context.getStudentUuid(), context.getStudentToken());
        assertThat(response.statusCode())
                .as("profile re-GET must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);
        String raw = response.jsonPath().getString("generalData.userAddress");
        return parseAddresses(raw);
    }

    private List<Map<String, Object>> parseAddresses(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> parsed = GSON.fromJson(raw, ADDRESS_LIST_TYPE);
        return parsed == null ? new ArrayList<>() : parsed;
    }

    /** Diffs the actual address list against the expected one, field by field, in order. */
    private void assertAddressesMatch(List<Map<String, Object>> actual, List<Map<String, Object>> expected) {
        assertThat(actual).as("address count must match").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> exp = expected.get(i);
            Map<String, Object> act = actual.get(i);
            for (String key : exp.keySet()) {
                assertThat(act.get(key)).as("address[%d].%s must match what was submitted", i, key)
                        .isEqualTo(exp.get(key));
            }
        }
    }

    /** Builds a runtime-tagged address - free text, since no evidence proves any address field is a closed set. */
    private Map<String, Object> buildAddress(String label, boolean isPermanent) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("area", "Runtime Area " + tag);
        address.put("houseNo", "H-" + tag);
        address.put("pincode", TestDataFactory.addressPincode());
        address.put("landmark", "Runtime Landmark " + tag);
        address.put("residentialAddress", "Runtime Residential Address " + tag + " (" + label + ")");
        address.put("city", "Runtime City " + tag);
        address.put("state", "Runtime State " + tag);
        address.put("country", "Runtime Country " + tag);
        address.put("isPermanent", isPermanent);
        return address;
    }
}
