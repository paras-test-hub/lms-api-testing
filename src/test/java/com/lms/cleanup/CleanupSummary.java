package com.lms.cleanup;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of a single CleanupManager run - which resources were deleted, skipped, or failed.
 * Returned to (and logged by) the caller so cleanup results are reported SEPARATELY from the
 * business test result and never mask it. Purely a data holder; no cleanup logic here.
 */
public final class CleanupSummary {

    /** A resource whose deletion failed, with the reason (status/exception message). */
    public static final class Failure {
        private final ResourceType type;
        private final String id;
        private final String reason;

        public Failure(ResourceType type, String id, String reason) {
            this.type = type;
            this.id = id;
            this.reason = reason;
        }

        public ResourceType getType() { return type; }
        public String getId() { return id; }
        public String getReason() { return reason; }

        @Override
        public String toString() {
            return type + "[" + id + "]: " + reason;
        }
    }

    private final String executionId;
    private final List<ResourceType> deleted = new ArrayList<>();
    private final List<ResourceType> skipped = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();

    public CleanupSummary(String executionId) {
        this.executionId = executionId;
    }

    public void recordDeleted(ResourceType type) {
        deleted.add(type);
    }

    public void recordSkipped(ResourceType type) {
        skipped.add(type);
    }

    public void recordFailure(ResourceType type, String id, String reason) {
        failures.add(new Failure(type, id, reason));
    }

    public String getExecutionId() { return executionId; }
    public List<ResourceType> getDeleted() { return deleted; }
    public List<ResourceType> getSkipped() { return skipped; }
    public List<Failure> getFailures() { return failures; }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
