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
 * Orchestration for the Student Profile journey's Work Experience sub-workflow. Uses
 * dedicated endpoints {@code POST/PATCH /users/userProfile/{studentUuid}/experience} —
 * separate from the main profile PATCH that Personal Details/Family/Guardian share.
 *
 * <p>Verified from hawkeye-apis_2026-07-30_12-57-10.har entries 1 (create), 13 (update),
 * 21 (delete). Key findings:
 * <ul>
 *   <li>Create returns the full experience list in {@code Data[]}, each with a {@code uuid}</li>
 *   <li>Update uses PATCH with {@code experinceId} (backend's spelling) in the body</li>
 *   <li>Delete uses PATCH with {@code {experinceId, isDeleted: true}}</li>
 *   <li>Profile GET exposes experiences in the top-level {@code experienceData[]} array</li>
 *   <li>No file upload is attempted — the HAR shows a Filestack upload for appraisal letter
 *       but this framework has no file-upload capability; we skip file fields</li>
 * </ul>
 */
public class WorkExperienceWorkflow {

    private static final Logger log = LoggerFactory.getLogger(WorkExperienceWorkflow.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StudentProfileClient profileClient;

    private List<Map<String, Object>> baselineExperiences;
    private String createdExperienceUuid;
    private Map<String, Object> createPayload;
    private Map<String, Object> updatePayload;

    public WorkExperienceWorkflow() {
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

        baselineExperiences = response.jsonPath().getList("experienceData");
        if (baselineExperiences == null) {
            baselineExperiences = new ArrayList<>();
        }

        StudentProfileJourneyReport.checkpoint(context, "Runtime Profile Retrieved",
                "Student: " + context.getStudentEmail() + " (" + studentUuid + ")",
                "Existing experiences: " + baselineExperiences.size());
        log.info("Retrieved baseline for Work Experience: {} existing entries", baselineExperiences.size());
    }

    // ================= Stage 2: create experience =================

    public void createExperience(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        createPayload = buildCreatePayload(studentUuid);

        Response response = profileClient.createExperience(studentUuid, createPayload, studentToken);
        assertThat(response.statusCode())
                .as("experience create must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> created = response.jsonPath().getList("Data");
        assertThat(created).as("Data must contain the created experience").isNotEmpty();

        Map<String, Object> newest = created.get(created.size() - 1);
        createdExperienceUuid = (String) newest.get("uuid");
        assertThat(createdExperienceUuid).as("created experience must have a uuid").isNotBlank();

        assertThat(newest.get("company")).as("company must match").isEqualTo(createPayload.get("company"));
        assertThat(newest.get("designation")).as("designation must match").isEqualTo(createPayload.get("designation"));
        assertThat(newest.get("employmentType")).as("employmentType must match").isEqualTo(createPayload.get("employmentType"));

        StudentProfileJourneyReport.checkpoint(context, "Work Experience Created",
                "uuid: " + createdExperienceUuid,
                "company: " + createPayload.get("company"),
                "designation: " + createPayload.get("designation"),
                "employmentType: " + createPayload.get("employmentType"));
        log.info("Created experience {} for student {}", createdExperienceUuid, studentUuid);
    }

    // ================= Stage 3: verify via profile GET =================

    public void verifyCreationViProfileGet(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> experiences = response.jsonPath().getList("experienceData");
        assertThat(experiences).as("experienceData must contain entries").isNotEmpty();

        Map<String, Object> match = experiences.stream()
                .filter(e -> createdExperienceUuid.equals(e.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("created experience %s must appear in experienceData", createdExperienceUuid).isNotNull();
        assertThat(match.get("company")).as("company must persist").isEqualTo(createPayload.get("company"));
        assertThat(match.get("jobProfile")).as("jobProfile must persist").isEqualTo(createPayload.get("jobProfile"));
        assertThat(DateUtils.toCalendarDate(match.get("startDate")))
                .as("startDate must persist as the same calendar date")
                .isEqualTo(LocalDate.parse((String) createPayload.get("startDate")));
        assertThat(DateUtils.toCalendarDate(match.get("endDate")))
                .as("endDate must persist as the same calendar date")
                .isEqualTo(LocalDate.parse((String) createPayload.get("endDate")));

        StudentProfileJourneyReport.checkpoint(context, "Creation Persisted in Profile GET",
                "experienceData contains uuid " + createdExperienceUuid,
                "company, designation, jobProfile, startDate, endDate all match");
        log.info("Verified experience {} in profile GET", createdExperienceUuid);
    }

    // ================= Stage 4: update experience =================

    public void updateExperience(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        updatePayload = buildUpdatePayload(studentUuid);

        Response response = profileClient.updateExperience(studentUuid, updatePayload, studentToken);
        assertThat(response.statusCode())
                .as("experience update must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> updated = response.jsonPath().getList("Data");
        assertThat(updated).as("Data must contain the updated experience").isNotEmpty();

        Map<String, Object> match = updated.stream()
                .filter(e -> createdExperienceUuid.equals(e.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("updated experience must retain the same uuid").isNotNull();
        assertThat(match.get("company")).as("company must be updated").isEqualTo(updatePayload.get("company"));
        assertThat(match.get("jobProfile")).as("jobProfile must be updated").isEqualTo(updatePayload.get("jobProfile"));

        StudentProfileJourneyReport.checkpoint(context, "Work Experience Updated",
                "uuid unchanged: " + createdExperienceUuid,
                "company: " + updatePayload.get("company"),
                "jobProfile: " + updatePayload.get("jobProfile"));
        log.info("Updated experience {} for student {}", createdExperienceUuid, studentUuid);
    }

    // ================= Stage 5: verify update persisted =================

    public void verifyUpdatePersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> experiences = response.jsonPath().getList("experienceData");
        Map<String, Object> match = experiences.stream()
                .filter(e -> createdExperienceUuid.equals(e.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("updated experience must still exist in profile").isNotNull();
        assertThat(match.get("company")).as("updated company must persist").isEqualTo(updatePayload.get("company"));
        assertThat(match.get("jobProfile")).as("updated jobProfile must persist").isEqualTo(updatePayload.get("jobProfile"));
        assertThat(DateUtils.toCalendarDate(match.get("startDate")))
                .as("startDate must persist after update")
                .isEqualTo(LocalDate.parse((String) updatePayload.get("startDate")));
        assertThat(DateUtils.toCalendarDate(match.get("endDate")))
                .as("endDate must persist after update")
                .isEqualTo(LocalDate.parse((String) updatePayload.get("endDate")));

        StudentProfileJourneyReport.checkpoint(context, "Update Persisted in Profile GET",
                "company, jobProfile, startDate, endDate match updated values");
        log.info("Verified update persistence for experience {}", createdExperienceUuid);
    }

    // ================= Stage 6: delete experience =================

    public void deleteExperience(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Map<String, Object> deletePayload = new LinkedHashMap<>();
        deletePayload.put("experinceId", createdExperienceUuid);
        deletePayload.put("isDeleted", Boolean.TRUE);

        Response response = profileClient.updateExperience(studentUuid, deletePayload, studentToken);
        assertThat(response.statusCode())
                .as("experience delete must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        String message = response.jsonPath().getString("message");
        assertThat(message).as("delete response message").containsIgnoringCase("Updated");

        StudentProfileJourneyReport.checkpoint(context, "Work Experience Deleted",
                "experinceId: " + createdExperienceUuid,
                "Response: " + message);
        log.info("Deleted experience {} for student {}", createdExperienceUuid, studentUuid);
    }

    // ================= Stage 7: verify deletion persisted =================

    public void verifyDeletionPersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> experiences = response.jsonPath().getList("experienceData");
        if (experiences == null) {
            experiences = new ArrayList<>();
        }

        boolean stillPresent = experiences.stream()
                .anyMatch(e -> createdExperienceUuid.equals(e.get("uuid")));
        assertThat(stillPresent).as("deleted experience %s must not appear in experienceData", createdExperienceUuid)
                .isFalse();

        StudentProfileJourneyReport.checkpoint(context, "Deletion Verified in Profile GET",
                "experienceData no longer contains uuid " + createdExperienceUuid,
                "Remaining experiences: " + experiences.size());
        log.info("Verified deletion of experience {} — {} remaining", createdExperienceUuid, experiences.size());
    }

    public void completeWorkExperienceJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "WORK EXPERIENCE");
    }

    // ================= Internals =================

    private Map<String, Object> buildCreatePayload(String studentUuid) {
        String company = TestDataFactory.companyName();
        String designation = TestDataFactory.experienceDesignation();
        String jobProfileVal = TestDataFactory.jobProfile();
        String employmentType = TestDataFactory.employmentType();
        String locationType = TestDataFactory.locationType();
        String industryVal = TestDataFactory.industry();
        String experience = TestDataFactory.totalExperience();
        LocalDate startDate = LocalDate.now().minusMonths(6);
        LocalDate endDate = LocalDate.now().minusDays(1);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("company", company);
        payload.put("highlightDescription", "");
        payload.put("Industry", industryVal);
        payload.put("employmentType", employmentType);
        payload.put("startDate", startDate.format(DATE_FMT));
        payload.put("endDate", endDate.format(DATE_FMT));
        payload.put("jobProfile", jobProfileVal);
        payload.put("grossAnnualCtc", "");
        payload.put("location", "Runtime City, Runtime State, Runtime Country");
        payload.put("locationType", locationType);
        payload.put("designation", designation);
        payload.put("companyWebsiteLink", "");
        payload.put("totalExperience", experience);
        payload.put("workCategory", "");
        payload.put("companyCity", "Runtime City");
        payload.put("companyState", "Runtime State");
        payload.put("companyCountry", "Runtime Country");
        payload.put("companyCtc", "");
        payload.put("isCurrentlyWorking", false);
        payload.put("skills", new ArrayList<>());
        payload.put("Description", "");
        payload.put("logoLink", "");
        payload.put("companyExperienceLetter", new ArrayList<>());
        payload.put("companyAppraisalLetter", new ArrayList<>());
        payload.put("createdBy", studentUuid);
        return payload;
    }

    private Map<String, Object> buildUpdatePayload(String studentUuid) {
        String updatedCompany = TestDataFactory.companyName();
        String updatedJobProfile = TestDataFactory.jobProfile();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("company", updatedCompany);
        payload.put("highlightDescription", "");
        payload.put("Industry", createPayload.get("Industry"));
        payload.put("employmentType", createPayload.get("employmentType"));
        payload.put("endDate", createPayload.get("endDate"));
        payload.put("startDate", createPayload.get("startDate"));
        payload.put("jobProfile", updatedJobProfile);
        payload.put("logoLink", "");
        payload.put("grossAnnualCtc", "");
        payload.put("location", createPayload.get("location"));
        payload.put("locationType", createPayload.get("locationType"));
        payload.put("designation", createPayload.get("designation"));
        payload.put("companyWebsiteLink", "");
        payload.put("totalExperience", createPayload.get("totalExperience"));
        payload.put("workCategory", "");
        payload.put("companyCity", "Runtime City");
        payload.put("companyState", "Runtime State");
        payload.put("companyCountry", "Runtime Country");
        payload.put("companyCtc", "");
        payload.put("isCurrentlyWorking", false);
        payload.put("skills", new ArrayList<>());
        payload.put("Description", "");
        payload.put("companyExperienceLetter", new ArrayList<>());
        payload.put("companyAppraisalLetter", new ArrayList<>());
        payload.put("experinceId", createdExperienceUuid);
        return payload;
    }
}
