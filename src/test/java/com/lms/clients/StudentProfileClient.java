package com.lms.clients;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

import java.util.Map;

/**
 * The single client for the entire Student Profile domain - one resource
 * ({@code /users/{studentUuid}/userProfile}), read via GET and written via PATCH, covering EVERY
 * profile section (Personal Details now; Guardian/Address/Work Experience/Education/Documents in
 * later phases all read and write through this same endpoint, differentiated only by which fields the
 * caller populates in the PATCH body). One client per business domain, mirroring
 * {@link SessionClient} / {@link AttendanceClient} / {@link AssignmentClient}.
 *
 * <p>Endpoints verified from real HAR capture hawkeye-apis_2026-07-28_16-03-31.har (GET, then a
 * rejected PATCH, then an accepted PATCH, then a re-GET) plus the accompanying UI action recording
 * hawkeye-session_2026-07-28_16-03-31.parasite.json. No separate dropdown-lookup endpoint exists in
 * either capture - Gender/Blood Group/Nationality/Country/Work Authorization options are embedded
 * client-side, not fetched from an API.
 *
 * <p>{@code StudentDashboardClient.getUserProfile} already wraps this same GET, but was scaffolded
 * for a separate, dormant per-OpenAPI-tag initiative with no concrete flow behind it. This client is
 * self-contained instead, matching how the other three business journeys each own their domain.
 */
public class StudentProfileClient {

    private static final String USERS_BASE = TestConfig.API_V1 + "/users";

    private final RestClient restClient;

    public StudentProfileClient() {
        this.restClient = new RestClient();
    }

    /**
     * GET /api/v1/users/{studentUuid}/userProfile - the full profile document. Verified response:
     * {@code { IsSuccess, Data: null, generalData: {firstName, lastName, dateOfBirth, gender,
     * bloodGroup, nationality, country, mobileNumber, countryCode, alternateEmail,
     * alternateMobileNumber, linkedInUrl, workAuthorizationValues, ...}, experienceData, educationData,
     * portfolioData, validLinkPortfolio, skillData, domainData, message }}. Note the top-level
     * {@code Data} field is {@code null} - the fields the profile screens use live in
     * {@code generalData} (Personal/Guardian details) and the other named top-level objects.
     */
    public Response getProfile(String studentUuid, String studentToken) {
        return restClient.get(USERS_BASE + "/" + studentUuid + "/userProfile", studentToken);
    }

    /**
     * PATCH /api/v1/users/{studentUuid}/userProfile - saves Personal Details (and, in later phases,
     * other profile sections through the same endpoint). Verified request shape (Personal Details):
     * {@code { initialAdditions, firstName, lastName, dateOfBirth ("d MMM yyyy"), alternateEmail,
     * gender, bloodGroup, nationality, country, countryCode, mobileNumber, studentCountryCode,
     * alternateMobileNumber, linkedInUrl, workAuthorizationValues:{selected, homeCountries} }}.
     * Verified responses: 200 with {@code Data} = the updated profile on success; 406 with
     * {@code {code:406, message:"Parent with this mobile number already exists"}} when
     * {@code mobileNumber} collides with an existing parent record elsewhere in the system.
     */
    public Response updateProfile(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.patch(USERS_BASE + "/" + studentUuid + "/userProfile", payload, studentToken);
    }

    /**
     * POST /api/v1/users/userProfile/{studentUuid}/address - a SEPARATE endpoint from
     * {@link #updateProfile}, verified from hawkeye-apis_2026-07-29_10-10-03.har. FULL-REPLACE
     * semantics, not a per-item CRUD API: the request body's {@code userAddress} array is the entire
     * desired address list - add an address by appending an entry and posting the whole array; delete
     * one by omitting it; toggle "permanent" by flipping {@code isPermanent} on the target entry and
     * re-posting everything. There is no per-address id/uuid anywhere in the request or response -
     * addresses are identified purely by their position/content in the array. Verified request:
     * {@code { userAddress: [ {area, houseNo, pincode, landmark, residentialAddress, city, state,
     * country, isPermanent} ], userId }}. Verified response: 201 with
     * {@code { IsSuccess, Data: { userAddress: "<JSON-encoded string of the same array>" }, message:
     * "User Address Updated" }} - note {@code Data.userAddress} is a JSON string, not a native array,
     * matching the same shape {@code generalData.userAddress} has on GET.
     */
    public Response updateAddresses(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.post(USERS_BASE + "/userProfile/" + studentUuid + "/address", payload, studentToken);
    }

    // ---- Work Experience ----

    /**
     * POST /api/v1/users/userProfile/{studentUuid}/experience — creates a new work experience entry.
     * Verified from hawkeye-apis_2026-07-30_12-57-10.har entry 1: returns 201 with
     * {@code {IsSuccess, Data:[{uuid, company, designation, ...}], message:"User experience created"}}.
     */
    public Response createExperience(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.post(USERS_BASE + "/userProfile/" + studentUuid + "/experience", payload, studentToken);
    }

    /**
     * PATCH /api/v1/users/userProfile/{studentUuid}/experience — updates or deletes an experience entry.
     * The payload must include {@code experinceId} (note the backend's misspelling) to identify which
     * entry to modify. For deletion, send {@code {experinceId, isDeleted: true}}.
     * Verified from HAR entries 13 (update) and 21 (delete).
     */
    public Response updateExperience(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.patch(USERS_BASE + "/userProfile/" + studentUuid + "/experience", payload, studentToken);
    }

    // ---- Education Qualifications ----

    /**
     * POST /api/v1/users/userProfile/{studentUuid}/qualifications — creates or updates a qualification.
     * For create: omit {@code qualificationId} or send empty. For update: include the existing
     * {@code qualificationId}. Both return 201 with {@code {isSuccess, Data:[...], message}}.
     * Verified from HAR entries 32 (create) and 41 (update with qualificationId).
     */
    public Response createOrUpdateQualification(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.post(USERS_BASE + "/userProfile/" + studentUuid + "/qualifications", payload, studentToken);
    }

    /**
     * PATCH /api/v1/users/userProfile/{studentUuid}/qualifications — deletes a qualification.
     * Payload: {@code {qualificationId, isDeleted: true, type: "delete", createdBy: studentUuid}}.
     * Verified from HAR entry 45: returns 201 with {@code {IsSuccess, Data:[], message}}.
     */
    public Response deleteQualification(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.patch(USERS_BASE + "/userProfile/" + studentUuid + "/qualifications", payload, studentToken);
    }

    // ---- Accomplishments ----

    /**
     * POST /api/v1/users/userProfile/{studentUuid}/accomplishment — creates a new accomplishment.
     * Returns 201 with the full accomplishment list in {@code Data[]}.
     * Verified from HAR entries 57 and 71.
     */
    public Response createAccomplishment(String studentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.post(USERS_BASE + "/userProfile/" + studentUuid + "/accomplishment", payload, studentToken);
    }

    /**
     * PATCH /api/v1/users/userProfile/{accomplishmentUuid}/accomplishment — updates or deletes an
     * accomplishment. NOTE: the path UUID is the ACCOMPLISHMENT's uuid, not the student's — verified
     * from HAR entries 77 and 81 where the path UUIDs match the accomplishment UUIDs, not the student.
     * For deletion, send {@code {isDeleted: true}}.
     */
    public Response updateAccomplishment(String accomplishmentUuid, Map<String, Object> payload, String studentToken) {
        return restClient.patch(USERS_BASE + "/userProfile/" + accomplishmentUuid + "/accomplishment", payload, studentToken);
    }
}
