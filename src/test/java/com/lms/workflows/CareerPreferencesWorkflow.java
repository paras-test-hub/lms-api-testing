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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration for the Student Profile journey's Career Preferences sub-workflow. Uses the SAME
 * {@code PATCH /users/{studentUuid}/userProfile} endpoint that Personal Details and Family Background
 * share — differentiated only by which fields are populated.
 *
 * <p>Verified from hawkeye-apis_2026-07-30_12-57-10.har entries 94 (first save) and 100 (update):
 * <ul>
 *   <li>Fields: portfolioLink, resumeDocLink, professionalAccomplishments, aboutYourself,
 *       primaryPreMbaDomain, primaryPostMbaDomain, secondaryPostMbaDomain, tertiaryPostMbaDomain,
 *       postMbaCityPreference1/2/3, countryInterested, countryVisaName, dreamCompany (array of
 *       {companyName, role}), isInternationalPlacement, isLocationConstraint, isAnotherCountryVisa,
 *       isOptedPlacementProcess, resume (Filestack URL), resumeFileName</li>
 *   <li>Profile GET exposes these in {@code generalData} — same top-level object as personal details</li>
 *   <li>Resume/resumeFileName reference a Filestack upload — this framework has no file-upload
 *       capability, so these fields are sent empty or omitted</li>
 *   <li>The HAR shows MBA domains as free-text strings ("Sales", "Consulting") — no dropdown-lookup
 *       endpoint exists in the capture</li>
 * </ul>
 */
public class CareerPreferencesWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CareerPreferencesWorkflow.class);

    private final StudentProfileClient profileClient;

    private Map<String, Object> baselineProfile;
    private Map<String, Object> firstSavePayload;
    private Map<String, Object> updatePayload;

    public CareerPreferencesWorkflow() {
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

        baselineProfile = response.jsonPath().getMap("generalData");
        assertThat(baselineProfile).as("generalData must be present").isNotNull();
        assertThat(baselineProfile.get("uuid")).as("profile must belong to runtime student")
                .isEqualTo(studentUuid);

        StudentProfileJourneyReport.checkpoint(context, "Runtime Profile Retrieved",
                "Student: " + context.getStudentEmail() + " (" + studentUuid + ")",
                "Baseline primaryPreMbaDomain: " + baselineProfile.get("primaryPreMbaDomain"),
                "Baseline aboutYourself: " + truncate(baselineProfile.get("aboutYourself")));
        log.info("Retrieved baseline for Career Preferences");
    }

    // ================= Stage 2: save career preferences =================

    public void saveCareerPreferences(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        firstSavePayload = buildSavePayload();

        Response response = profileClient.updateProfile(studentUuid, firstSavePayload, studentToken);
        assertThat(response.statusCode())
                .as("career preferences save must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        StudentProfileJourneyReport.checkpoint(context, "Career Preferences Saved",
                "primaryPreMbaDomain: " + firstSavePayload.get("primaryPreMbaDomain"),
                "primaryPostMbaDomain: " + firstSavePayload.get("primaryPostMbaDomain"),
                "secondaryPostMbaDomain: " + firstSavePayload.get("secondaryPostMbaDomain"),
                "dreamCompany entries: " + ((List<?>) firstSavePayload.get("dreamCompany")).size());
        log.info("Saved career preferences for student {}", studentUuid);
    }

    // ================= Stage 3: verify save persisted =================

    public void verifySavePersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        Map<String, Object> gd = response.jsonPath().getMap("generalData");

        assertThat(gd.get("primaryPreMbaDomain")).as("primaryPreMbaDomain must persist")
                .isEqualTo(firstSavePayload.get("primaryPreMbaDomain"));
        assertThat(gd.get("primaryPostMbaDomain")).as("primaryPostMbaDomain must persist")
                .isEqualTo(firstSavePayload.get("primaryPostMbaDomain"));
        assertThat(gd.get("secondaryPostMbaDomain")).as("secondaryPostMbaDomain must persist")
                .isEqualTo(firstSavePayload.get("secondaryPostMbaDomain"));
        assertThat(gd.get("aboutYourself")).as("aboutYourself must persist")
                .isEqualTo(firstSavePayload.get("aboutYourself"));
        assertThat(gd.get("professionalAccomplishments")).as("professionalAccomplishments must persist")
                .isEqualTo(firstSavePayload.get("professionalAccomplishments"));
        assertThat(gd.get("isInternationalPlacement")).as("isInternationalPlacement must persist")
                .isEqualTo(firstSavePayload.get("isInternationalPlacement"));
        assertThat(gd.get("isLocationConstraint")).as("isLocationConstraint must persist")
                .isEqualTo(firstSavePayload.get("isLocationConstraint"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dreamCompany = (List<Map<String, Object>>) gd.get("dreamCompany");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentDreams = (List<Map<String, Object>>) firstSavePayload.get("dreamCompany");
        assertThat(dreamCompany).as("dreamCompany must persist with correct count")
                .hasSameSizeAs(sentDreams);
        for (int i = 0; i < sentDreams.size(); i++) {
            assertThat(dreamCompany.get(i).get("companyName"))
                    .as("dreamCompany[%d].companyName must match", i)
                    .isEqualTo(sentDreams.get(i).get("companyName"));
            assertThat(dreamCompany.get(i).get("role"))
                    .as("dreamCompany[%d].role must match", i)
                    .isEqualTo(sentDreams.get(i).get("role"));
        }

        StudentProfileJourneyReport.checkpoint(context, "Save Persisted in Profile GET",
                "All career preference fields match the PATCH payload",
                "dreamCompany array verified entry-by-entry");
        log.info("Verified career preferences persistence");
    }

    // ================= Stage 4: update career preferences =================

    public void updateCareerPreferences(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        updatePayload = buildUpdatePayload();

        Response response = profileClient.updateProfile(studentUuid, updatePayload, studentToken);
        assertThat(response.statusCode())
                .as("career preferences update must succeed. Response: " + response.getBody().asString())
                .isBetween(200, 299);

        StudentProfileJourneyReport.checkpoint(context, "Career Preferences Updated",
                "primaryPreMbaDomain changed to: " + updatePayload.get("primaryPreMbaDomain"),
                "aboutYourself updated",
                "dreamCompany entries modified");
        log.info("Updated career preferences for student {}", studentUuid);
    }

    // ================= Stage 5: verify update persisted =================

    public void verifyUpdatePersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        Map<String, Object> gd = response.jsonPath().getMap("generalData");

        assertThat(gd.get("primaryPreMbaDomain")).as("updated primaryPreMbaDomain must persist")
                .isEqualTo(updatePayload.get("primaryPreMbaDomain"));
        assertThat(gd.get("aboutYourself")).as("updated aboutYourself must persist")
                .isEqualTo(updatePayload.get("aboutYourself"));
        assertThat(gd.get("professionalAccomplishments")).as("updated professionalAccomplishments must persist")
                .isEqualTo(updatePayload.get("professionalAccomplishments"));
        assertThat(gd.get("resumeDocLink")).as("updated resumeDocLink must persist")
                .isEqualTo(updatePayload.get("resumeDocLink"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dreamCompany = (List<Map<String, Object>>) gd.get("dreamCompany");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentDreams = (List<Map<String, Object>>) updatePayload.get("dreamCompany");
        assertThat(dreamCompany).as("dreamCompany must reflect update").hasSameSizeAs(sentDreams);
        for (int i = 0; i < sentDreams.size(); i++) {
            assertThat(dreamCompany.get(i).get("companyName"))
                    .as("updated dreamCompany[%d].companyName must match", i)
                    .isEqualTo(sentDreams.get(i).get("companyName"));
        }

        StudentProfileJourneyReport.checkpoint(context, "Update Persisted in Profile GET",
                "primaryPreMbaDomain, aboutYourself, professionalAccomplishments, dreamCompany all updated");
        log.info("Verified career preferences update persistence");
    }

    public void completeCareerPreferencesJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "CAREER PREFERENCES");
    }

    // ================= Internals =================

    private Map<String, Object> buildSavePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("portfolioLink", "");
        payload.put("resumeDocLink", "");
        payload.put("professionalAccomplishments", TestDataFactory.professionalAccomplishments());
        payload.put("aboutYourself", TestDataFactory.aboutYourself());
        payload.put("primaryPreMbaDomain", TestDataFactory.mbaDomain());
        payload.put("primaryPostMbaDomain", TestDataFactory.mbaDomain());
        payload.put("tertiaryPostMbaDomain", "");
        payload.put("secondaryPostMbaDomain", TestDataFactory.mbaDomain());
        payload.put("postMbaCityPreference1", "");
        payload.put("postMbaCityPreference2", "");
        payload.put("postMbaCityPreference3", "");
        payload.put("countryInterested", "");
        payload.put("countryVisaName", "");

        List<Map<String, Object>> dreamCompany = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("companyName", TestDataFactory.dreamCompanyName());
            dc.put("role", TestDataFactory.dreamCompanyRole());
            dreamCompany.add(dc);
        }
        payload.put("dreamCompany", dreamCompany);

        payload.put("isInternationalPlacement", false);
        payload.put("isLocationConstraint", false);
        payload.put("isAnotherCountryVisa", false);
        payload.put("isOptedPlacementProcess", false);
        return payload;
    }

    private Map<String, Object> buildUpdatePayload() {
        Map<String, Object> payload = buildSavePayload();
        String differentDomain = TestDataFactory.mbaDomain();
        payload.put("primaryPreMbaDomain", differentDomain);
        payload.put("aboutYourself", TestDataFactory.aboutYourself());
        payload.put("professionalAccomplishments", TestDataFactory.professionalAccomplishments());
        payload.put("resumeDocLink", "https://example.com/runtime-resume-link");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dreams = (List<Map<String, Object>>) payload.get("dreamCompany");
        for (Map<String, Object> dc : dreams) {
            dc.put("companyName", TestDataFactory.dreamCompanyName());
            dc.put("role", TestDataFactory.dreamCompanyRole());
        }

        return payload;
    }

    private static String truncate(Object val) {
        if (val == null) return "null";
        String s = val.toString();
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
