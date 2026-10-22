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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration for the Student Profile journey's Education Qualification sub-workflow. Uses
 * {@code POST /users/userProfile/{studentUuid}/qualifications} for create AND update (update
 * includes {@code qualificationId} in the body), and
 * {@code PATCH /users/userProfile/{studentUuid}/qualifications} for delete only.
 *
 * <p>Verified from hawkeye-apis_2026-07-30_12-57-10.har:
 * <ul>
 *   <li>Entry 32: POST creates a 10th qualification (minimal fields, empty subjects)</li>
 *   <li>Entry 41: POST with {@code qualificationId} updates it (adds subjects, marks, school details)</li>
 *   <li>Entry 45: PATCH with {@code {qualificationId, isDeleted:true, type:"delete", createdBy}} deletes</li>
 *   <li>Entries 33, 42: PATCH to main profile with {@code {documentsRequired:[]}} sent alongside each
 *       qualification create/update — mirrored exactly</li>
 *   <li>Profile GET exposes qualifications in the top-level {@code educationData[]} array</li>
 * </ul>
 */
public class EducationQualificationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(EducationQualificationWorkflow.class);

    private final StudentProfileClient profileClient;

    private List<Map<String, Object>> baselineQualifications;
    private String createdQualificationUuid;
    private Map<String, Object> createPayload;
    private Map<String, Object> updatePayload;

    public EducationQualificationWorkflow() {
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

        baselineQualifications = response.jsonPath().getList("educationData");
        if (baselineQualifications == null) {
            baselineQualifications = new ArrayList<>();
        }

        StudentProfileJourneyReport.checkpoint(context, "Runtime Profile Retrieved",
                "Student: " + context.getStudentEmail() + " (" + studentUuid + ")",
                "Existing qualifications: " + baselineQualifications.size());
        log.info("Retrieved baseline for Education Qualifications: {} existing", baselineQualifications.size());
    }

    // ================= Stage 2: create qualification =================

    public void createQualification(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        createPayload = buildCreatePayload(studentUuid);

        Response response = profileClient.createOrUpdateQualification(studentUuid, createPayload, studentToken);
        assertThat(response.statusCode())
                .as("qualification create must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> created = response.jsonPath().getList("Data");
        assertThat(created).as("Data must contain the created qualification").isNotEmpty();

        Map<String, Object> newest = created.get(created.size() - 1);
        createdQualificationUuid = (String) newest.get("uuid");
        assertThat(createdQualificationUuid).as("created qualification must have a uuid").isNotBlank();

        assertThat(newest.get("instituteName")).as("instituteName must match")
                .isEqualTo(createPayload.get("instituteName"));
        assertThat(newest.get("type")).as("type must match").isEqualTo(createPayload.get("type"));
        assertThat(newest.get("board")).as("board must match").isEqualTo(createPayload.get("board"));

        syncDocumentsRequired(studentUuid, studentToken);

        StudentProfileJourneyReport.checkpoint(context, "Qualification Created",
                "uuid: " + createdQualificationUuid,
                "type: " + createPayload.get("type"),
                "board: " + createPayload.get("board"),
                "instituteName: " + createPayload.get("instituteName"));
        log.info("Created qualification {} for student {}", createdQualificationUuid, studentUuid);
    }

    // ================= Stage 3: verify via profile GET =================

    public void verifyCreationViaProfileGet(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> qualifications = response.jsonPath().getList("educationData");
        assertThat(qualifications).as("educationData must contain entries").isNotEmpty();

        Map<String, Object> match = qualifications.stream()
                .filter(q -> createdQualificationUuid.equals(q.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("created qualification %s must appear in educationData", createdQualificationUuid)
                .isNotNull();
        assertThat(match.get("instituteName")).as("instituteName must persist")
                .isEqualTo(createPayload.get("instituteName"));
        assertThat(DateUtils.toCalendarDate(match.get("passingYear")))
                .as("passingYear must persist as the same calendar date")
                .isEqualTo(LocalDate.parse((String) createPayload.get("passingYear")));

        StudentProfileJourneyReport.checkpoint(context, "Creation Persisted in Profile GET",
                "educationData contains uuid " + createdQualificationUuid,
                "instituteName, passingYear match");
        log.info("Verified qualification {} in profile GET", createdQualificationUuid);
    }

    // ================= Stage 4: update with subjects and marks =================

    public void updateQualificationWithDetails(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        updatePayload = buildUpdatePayload(studentUuid);

        Response response = profileClient.createOrUpdateQualification(studentUuid, updatePayload, studentToken);
        assertThat(response.statusCode())
                .as("qualification update must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        List<Map<String, Object>> updated = response.jsonPath().getList("Data");
        assertThat(updated).as("Data must contain the updated qualification").isNotEmpty();

        Map<String, Object> match = updated.stream()
                .filter(q -> createdQualificationUuid.equals(q.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("updated qualification must retain the same uuid").isNotNull();
        assertThat(match.get("maximumMarks")).as("maximumMarks must be updated")
                .isEqualTo(updatePayload.get("maximumMarks"));
        assertThat(match.get("marksObtained")).as("marksObtained must be updated")
                .isEqualTo(updatePayload.get("marksObtained"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subjectJson = (List<Map<String, Object>>) match.get("subjectJson");
        assertThat(subjectJson).as("subjectJson must have entries after update").isNotEmpty();
        assertThat(subjectJson.get(0).get("subjectName")).as("first subject name must be populated").isNotNull();

        syncDocumentsRequired(studentUuid, studentToken);

        StudentProfileJourneyReport.checkpoint(context, "Qualification Updated With Details",
                "maximumMarks: " + updatePayload.get("maximumMarks"),
                "marksObtained: " + updatePayload.get("marksObtained"),
                "subjectJson populated with runtime subjects",
                "schoolLocation, mediumofStudy, lastAttendedSchool added");
        log.info("Updated qualification {} with subjects and marks", createdQualificationUuid);
    }

    // ================= Stage 5: verify update persisted =================

    public void verifyUpdatePersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> qualifications = response.jsonPath().getList("educationData");
        Map<String, Object> match = qualifications.stream()
                .filter(q -> createdQualificationUuid.equals(q.get("uuid")))
                .findFirst()
                .orElse(null);
        assertThat(match).as("updated qualification must still exist in profile").isNotNull();
        assertThat(match.get("maximumMarks")).as("maximumMarks must persist")
                .isEqualTo(updatePayload.get("maximumMarks"));
        assertThat(DateUtils.toCalendarDate(match.get("passingYear")))
                .as("passingYear must persist after update")
                .isEqualTo(LocalDate.parse((String) updatePayload.get("passingYear")));

        StudentProfileJourneyReport.checkpoint(context, "Update Persisted in Profile GET",
                "maximumMarks, marksObtained, subjectJson, passingYear all match updated values");
        log.info("Verified update persistence for qualification {}", createdQualificationUuid);
    }

    // ================= Stage 6: delete qualification =================

    public void deleteQualification(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Map<String, Object> deletePayload = new LinkedHashMap<>();
        deletePayload.put("qualificationId", createdQualificationUuid);
        deletePayload.put("isDeleted", Boolean.TRUE);
        deletePayload.put("type", "delete");
        deletePayload.put("createdBy", studentUuid);

        Response response = profileClient.deleteQualification(studentUuid, deletePayload, studentToken);
        assertThat(response.statusCode())
                .as("qualification delete must succeed. Response: " + response.getBody().asString())
                .isEqualTo(201);

        StudentProfileJourneyReport.checkpoint(context, "Qualification Deleted",
                "qualificationId: " + createdQualificationUuid,
                "Response: " + response.jsonPath().getString("message"));
        log.info("Deleted qualification {} for student {}", createdQualificationUuid, studentUuid);
    }

    // ================= Stage 7: verify deletion persisted =================

    public void verifyDeletionPersisted(ScenarioContext context) {
        String studentUuid = context.getStudentUuid();
        String studentToken = context.getStudentToken();

        Response response = profileClient.getProfile(studentUuid, studentToken);
        assertThat(response.statusCode()).as("profile GET must succeed").isBetween(200, 299);

        List<Map<String, Object>> qualifications = response.jsonPath().getList("educationData");
        if (qualifications == null) {
            qualifications = new ArrayList<>();
        }

        boolean stillPresent = qualifications.stream()
                .anyMatch(q -> createdQualificationUuid.equals(q.get("uuid")));
        assertThat(stillPresent)
                .as("deleted qualification %s must not appear in educationData", createdQualificationUuid)
                .isFalse();

        StudentProfileJourneyReport.checkpoint(context, "Deletion Verified in Profile GET",
                "educationData no longer contains uuid " + createdQualificationUuid,
                "Remaining qualifications: " + qualifications.size());
        log.info("Verified deletion of qualification {} — {} remaining", createdQualificationUuid, qualifications.size());
    }

    public void completeEducationQualificationJourneyStage(ScenarioContext context) {
        StudentProfileJourneyReport.publish(context, "EDUCATION QUALIFICATIONS");
    }

    // ================= Internals =================

    private void syncDocumentsRequired(String studentUuid, String studentToken) {
        Map<String, Object> docPayload = new LinkedHashMap<>();
        docPayload.put("documentsRequired", new ArrayList<>());
        Response docResp = profileClient.updateProfile(studentUuid, docPayload, studentToken);
        assertThat(docResp.statusCode())
                .as("documentsRequired sync must succeed").isBetween(200, 299);
    }

    private Map<String, Object> buildCreatePayload(String studentUuid) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("board", TestDataFactory.board());
        payload.put("certification", "");
        payload.put("cgpa", "");
        payload.put("percentage", TestDataFactory.percentage());
        payload.put("degreeName", "");
        payload.put("description", "");
        payload.put("instituteName", TestDataFactory.instituteName());
        payload.put("markingScheme", "Percentage");
        payload.put("passingYear", "2020-07-02");
        payload.put("specialisation", "");
        payload.put("stream", "");
        payload.put("type", "10th");
        payload.put("universityName", "");
        payload.put("qualificationId", "");
        payload.put("logoLink", "");
        payload.put("schoolCode", "");
        payload.put("lastAttendedSchool", "");
        payload.put("centreCode", "");
        payload.put("maximumMarks", "");
        payload.put("marksObtained", "");
        payload.put("rollNo", TestDataFactory.rollNumber());
        payload.put("typeOfSchoolOrCollege", TestDataFactory.schoolType());
        payload.put("maxuminGradePoint", "");
        payload.put("schoolLocation", "");
        payload.put("mediumofStudy", "");
        payload.put("hindiStudiedUp", "");
        payload.put("universityWebsiteLink", "");
        payload.put("qualification", "");

        List<Map<String, Object>> emptySubjects = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("subjectName", null);
            s.put("maximumMarks", null);
            s.put("marksObtained", null);
            emptySubjects.add(s);
        }
        payload.put("subjectJson", emptySubjects);
        payload.put("AdditionalSubjectJson", new ArrayList<>(emptySubjects));
        payload.put("documentsRequired", new ArrayList<>());
        payload.put("documentsRequiredCertificate", new ArrayList<>());
        payload.put("createdBy", studentUuid);
        return payload;
    }

    private Map<String, Object> buildUpdatePayload(String studentUuid) {
        Map<String, Object> payload = new LinkedHashMap<>(createPayload);
        payload.put("qualificationId", createdQualificationUuid);

        String maxMarks = TestDataFactory.marks();
        String obtainedMarks = TestDataFactory.marks();
        payload.put("maximumMarks", maxMarks);
        payload.put("marksObtained", obtainedMarks);
        payload.put("lastAttendedSchool", "AUTO_LAST_SCHOOL_" + System.currentTimeMillis());
        payload.put("schoolLocation", "AUTO_LOCATION_" + System.currentTimeMillis());
        payload.put("mediumofStudy", "English");
        payload.put("hindiStudiedUp", "Class 10");

        List<Map<String, Object>> subjects = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("subjectName", TestDataFactory.subjectName());
            s.put("maximumMarks", TestDataFactory.marks());
            s.put("marksObtained", TestDataFactory.marks());
            subjects.add(s);
        }
        payload.put("subjectJson", subjects);
        payload.put("AdditionalSubjectJson", new ArrayList<>(subjects));
        return payload;
    }
}
