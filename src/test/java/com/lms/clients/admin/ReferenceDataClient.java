package com.lms.clients.admin;

import com.lms.config.TestConfig;
import com.lms.utils.RestClient;
import io.restassured.response.Response;

/**
 * Admin reference-data reads (categories, course types, grading templates, course domains).
 * These are shared lookups whose ids are required fields on the create calls in the Environment
 * Setup chain (e.g. a program needs a categoryId, a course needs a courseTypeId).
 *
 * Per the Environment Setup design decision, this client only READS existing reference data and the
 * caller extracts an id from the response (via JsonSearch.findFirstValue). It does NOT create
 * reference data - if a lookup is empty the workflow fails loudly telling the operator to seed
 * that reference data for the automation org first.
 *
 * Paths from admin_openapi.json / verified frontend thunks (programSlice.js, gradingSlice.js).
 * Returns raw Response; caller decides how to parse.
 */
public class ReferenceDataClient {

    private final RestClient restClient;

    public ReferenceDataClient() {
        this.restClient = new RestClient();
    }

    private static String orgBase(String orgId) {
        return TestConfig.API_V1 + "/organization/" + orgId;
    }

    /** GET /api/v1/organization/{orgId}/category - source of categoryId (required to create a program master). */
    public Response listCategories(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/category", bearerToken);
    }

    /** GET /api/v1/organization/{orgId}/getAllCourseTypes - source of courseTypeId (course) and typeFormatuuid (cohort courseTypeArray). */
    public Response listCourseTypes(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/getAllCourseTypes", bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgId}/getAllGradingTemplates - source of gradingTemplate /
     * templateMasterId (cohort + sectionCourse). Extract the {@code uuid} field of an item.
     *
     * NOT to be confused with GET /api/v1/program/termCourse/gradingTemplate: that returns
     * per-instance APPLIED template records (joined to a course/section), whose uuids are
     * rejected (404) when posted back as a cohort's gradingTemplate. This org-level master
     * list is what the cohort-creation form's dropdown actually calls (verified in
     * lms-admin-frontend CreateCohort.js -> allTemplateData thunk).
     */
    public Response listGradingTemplates(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/getAllGradingTemplates", bearerToken);
    }

    /** GET /api/v1/organization/courseDomain/get-course-domain - source of courseDomain (optional on course). */
    public Response listCourseDomains(String bearerToken) {
        return restClient.get(TestConfig.API_V1 + "/organization/courseDomain/get-course-domain", bearerToken);
    }

    /**
     * GET /api/v1/nps-department - source of departmentId (course). Flat path, NOT org-scoped
     * (verified via HAR capture hawkeye-apis_2026-07-15_06-48-11.har). Items: {uuid, name}.
     */
    public Response listDepartments(String bearerToken) {
        return restClient.get(TestConfig.API_V1 + "/nps-department", bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgId}/getCourseModes - source of courseModeId (course modality).
     * Verified via HAR capture. NOTE: the label field on each item is "mode", NOT "name".
     */
    public Response listCourseModes(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/getCourseModes", bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgId}/getCourseClass - source of courseClassId (course class type).
     * Verified via HAR capture. Items: {uuid, name, themeColor}.
     */
    public Response listCourseClasses(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/getCourseClass", bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgId}/getAllCourseTags - source of courseTagId. Verified via HAR
     * capture: a course is created with courseTagId="", then set to a real uuid on the first
     * PATCH - this lookup is only needed for that PATCH, not the initial create. Items: {uuid, name}.
     */
    public Response listCourseTags(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/getAllCourseTags", bearerToken);
    }

    /**
     * GET /api/v1/organization/{orgId}/getAssignmentResources - source of assignmentResourceId, a
     * REQUIRED field on createAssignmentDetails. Returns the org's assignment "resource" types
     * (a small fixed reference list: "Create Assignments", "Create Quiz", "Create Group Assignment",
     * "Create Offline Exam", "Form", "Create Milestone"). Verified via HAR capture
     * (hawkeye-apis_2026-07-21_22-22-20.har). Items: {uuid, name, description, isDeleted}. The
     * Assignment journey picks the item named "Create Assignments" (a standard individual
     * assignment) by name rather than hardcoding its uuid.
     */
    public Response listAssignmentResources(String orgId, String bearerToken) {
        return restClient.get(orgBase(orgId) + "/getAssignmentResources", bearerToken);
    }
}
