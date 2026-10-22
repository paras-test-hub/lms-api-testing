package com.lms.cleanup;

import com.lms.clients.admin.CohortClient;
import com.lms.clients.admin.ProgramMasterClient;
import com.lms.context.ExecutionContext;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Tears down ONLY the resources this execution registered in {@link ScenarioContext} (via
 * {@code registerCreated}), in reverse creation order (children before parents). It never
 * queries "all programs/cohorts", never searches by name/timestamp, and only ever acts on
 * stored UUIDs - so it can never touch manually-created or pre-existing LMS data.
 *
 * Cleanup is best-effort and resilient: a missing id is skipped, a failed deletion is
 * recorded and the next resource is still attempted, and nothing thrown here is allowed to
 * mask the business test result (the caller - a Hook - logs the {@link CleanupSummary} and
 * does not rethrow). All deletion business logic lives here, not in the clients or the Hook.
 *
 * Currently handles COHORT and PROGRAM. Other registered types (e.g. STUDENT) are skipped
 * with a log until their handlers are added - the switch is the single extension point.
 */
public class CleanupManager {

    private static final Logger log = LoggerFactory.getLogger(CleanupManager.class);
    private static final String BANNER = "============================";

    private final CohortClient cohortClient;
    private final ProgramMasterClient programMasterClient;

    public CleanupManager() {
        this.cohortClient = new CohortClient();
        this.programMasterClient = new ProgramMasterClient();
    }

    public CleanupSummary cleanup(ExecutionContext context) {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        CleanupSummary summary = new CleanupSummary(executionId);

        List<com.lms.cleanup.CreatedResource> resources = context.getCreatedResources();
        if (resources.isEmpty()) {
            // Nothing the suite created - stay quiet (e.g. a run of only auth-negative endpoint tests).
            return summary;
        }

        String adminToken = context.getAdminToken();
        String orgId = context.getAdminOrgId();

        log.info(BANNER);
        log.info("Cleanup Started | Execution ID: {}", executionId);

        if (isBlank(adminToken)) {
            log.warn("No admin token available - skipping all cleanup (nothing can be authenticated).");
            resources.forEach(r -> summary.recordSkipped(r.getType()));
            logFooter(summary);
            return summary;
        }

        // Reverse creation order: children (e.g. Cohort) before parents (Program).
        for (int i = resources.size() - 1; i >= 0; i--) {
            com.lms.cleanup.CreatedResource resource = resources.get(i);
            try {
                cleanupOne(resource, context, summary, adminToken, orgId);
            } catch (Throwable unexpected) {
                // Safety net - a single resource must never abort the rest of cleanup.
                log.error("Unexpected error cleaning up {} [{}]: {}",
                        resource.getType(), resource.getId(), unexpected.toString());
                summary.recordFailure(resource.getType(), resource.getId(), unexpected.toString());
            }
        }

        logFooter(summary);
        return summary;
    }

    private void cleanupOne(com.lms.cleanup.CreatedResource resource, ExecutionContext context,
                            CleanupSummary summary, String adminToken, String orgId) {
        ResourceType type = resource.getType();
        String id = resource.getId();

        // Rule 2: missing id -> skip, log, do not fail.
        if (isBlank(id)) {
            log.warn("Skipping {} cleanup. UUID not available.", type);
            summary.recordSkipped(type);
            return;
        }

        switch (type) {
            case COHORT:
                deleteCohort(id, adminToken, summary);
                break;
            case PROGRAM:
                deleteProgram(id, orgId, adminToken, summary);
                break;
            default:
                log.info("No cleanup handler for {} yet - skipping [{}].", type, id);
                summary.recordSkipped(type);
        }
    }

    // ---- Cohort ----

    private void deleteCohort(String cohortId, String adminToken, CleanupSummary summary) {
        log.info("Deleting Cohort | UUID: {}", cohortId);
        try {
            Response response = cohortClient.deleteCohort(cohortId, adminToken);
            verifyCohortDeleted(response, cohortId);
            summary.recordDeleted(ResourceType.COHORT);
            log.info("Result: SUCCESS (Cohort {})", cohortId);
        } catch (Throwable t) {
            summary.recordFailure(ResourceType.COHORT, cohortId, t.getMessage());
            log.error("Deleting Cohort | UUID: {} | FAILED | Reason: {}", cohortId, t.getMessage());
        }
    }

    /**
     * Verifies the documented cohort-delete contract: HTTP 2xx, IsSuccess == true,
     * message == "Program deleted", Data.uuid == the created cohort id, Data.isDeleted == true.
     * Throws AssertionError with the raw body on any mismatch so the reason is diagnosable.
     */
    private void verifyCohortDeleted(Response response, String expectedCohortId) {
        int status = response.statusCode();
        String body = response.getBody().asString();
        if (status < 200 || status >= 300) {
            throw new AssertionError(status + " (body: " + body + ")");
        }
        JsonPath jp = response.jsonPath();

        Boolean isSuccess = readBool(jp, "IsSuccess", "isSuccess");
        if (!Boolean.TRUE.equals(isSuccess)) {
            throw new AssertionError("expected IsSuccess=true but body was: " + body);
        }
        String message = readStr(jp, "message");
        if (!"Program deleted".equals(message)) {
            throw new AssertionError("expected message=\"Program deleted\" but was \"" + message + "\"");
        }
        String deletedUuid = readStr(jp, "Data.uuid", "data.uuid");
        if (!expectedCohortId.equals(deletedUuid)) {
            throw new AssertionError("Data.uuid \"" + deletedUuid + "\" != created cohort id \"" + expectedCohortId + "\"");
        }
        Boolean isDeleted = readBool(jp, "Data.isDeleted", "data.isDeleted");
        if (!Boolean.TRUE.equals(isDeleted)) {
            throw new AssertionError("expected Data.isDeleted=true but body was: " + body);
        }
    }

    // ---- Program ----

    private void deleteProgram(String programId, String orgId, String adminToken, CleanupSummary summary) {
        log.info("Deleting Program | UUID: {}", programId);
        try {
            Response deleteResponse = programMasterClient.delete(orgId, programId, adminToken);
            int status = deleteResponse.statusCode();
            if (status < 200 || status >= 300) {
                throw new AssertionError(status + " (body: " + deleteResponse.getBody().asString() + ")");
            }
            verifyProgramGone(orgId, programId, adminToken);
            summary.recordDeleted(ResourceType.PROGRAM);
            log.info("Result: SUCCESS (Program {})", programId);
        } catch (Throwable t) {
            summary.recordFailure(ResourceType.PROGRAM, programId, t.getMessage());
            log.error("Deleting Program | UUID: {} | FAILED | Reason: {}", programId, t.getMessage());
        }
    }

    /**
     * Confirms the program is gone via GET (no list-all). Verified live behaviour: the backend
     * does a SOFT delete - DELETE returns "Program Master deleted", then GET still returns 200
     * but with the record flagged {@code isDeleted: true} (nested under Data.programMaster).
     * Also accepts a not-found response, or the record being absent from the payload, in case
     * behaviour differs on other environments - assertions follow the real response, not an
     * assumed single shape.
     */
    private void verifyProgramGone(String orgId, String programId, String adminToken) {
        Response getResponse = programMasterClient.getById(orgId, programId, adminToken);
        int status = getResponse.statusCode();
        if (status == 404 || status == 400 || status == 410) {
            return; // no longer retrievable => deleted
        }
        if (status >= 200 && status < 300) {
            JsonPath jp = getResponse.jsonPath();
            Boolean isDeleted = readBool(jp,
                    "Data.programMaster.isDeleted", "data.programMaster.isDeleted",
                    "Data.isDeleted", "data.isDeleted", "isDeleted");
            boolean recordAbsent = jp.get("Data.programMaster") == null && jp.get("data.programMaster") == null;
            if (Boolean.TRUE.equals(isDeleted) || recordAbsent) {
                return; // flagged soft-deleted, or the record is no longer present in the payload
            }
            throw new AssertionError("program still retrievable and not flagged deleted after DELETE (body: "
                    + getResponse.getBody().asString() + ")");
        }
        throw new AssertionError("unexpected status " + status + " verifying program deletion (body: "
                + getResponse.getBody().asString() + ")");
    }

    // ---- Logging + helpers ----

    private void logFooter(CleanupSummary summary) {
        log.info("Cleanup Completed | Execution ID: {}", summary.getExecutionId());
        if (!summary.getDeleted().isEmpty()) {
            summary.getDeleted().forEach(t -> log.info("  Deleted: {}", t));
        }
        if (!summary.getSkipped().isEmpty()) {
            summary.getSkipped().forEach(t -> log.info("  Skipped: {}", t));
        }
        if (summary.hasFailures()) {
            log.error("Cleanup completed WITH FAILURES ({}):", summary.getFailures().size());
            summary.getFailures().forEach(f -> log.error("  Failed: {}", f));
        }
        log.info(BANNER);
    }

    private static Boolean readBool(JsonPath jp, String... paths) {
        for (String path : paths) {
            Object value = safeGet(jp, path);
            if (value != null) {
                return Boolean.valueOf(value.toString());
            }
        }
        return null;
    }

    private static String readStr(JsonPath jp, String... paths) {
        for (String path : paths) {
            Object value = safeGet(jp, path);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private static Object safeGet(JsonPath jp, String path) {
        try {
            return jp.get(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
