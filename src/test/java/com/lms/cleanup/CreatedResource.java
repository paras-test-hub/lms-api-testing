package com.lms.cleanup;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single entity created during a workflow, recorded so a future CleanupManager can delete
 * it without re-deriving anything. Deletion endpoints often need more than the entity's own
 * id (e.g. deleting a section needs orgId/programTermId too), so {@link #getDeletionContext()}
 * carries whatever extra path ids the delete call will require.
 *
 * Immutable value object - no cleanup logic lives here; this is pure bookkeeping for now.
 */
public final class CreatedResource {

    private final ResourceType type;
    private final String id;
    private final Map<String, String> deletionContext;

    public CreatedResource(ResourceType type, String id) {
        this(type, id, Collections.emptyMap());
    }

    public CreatedResource(ResourceType type, String id, Map<String, String> deletionContext) {
        this.type = type;
        this.id = id;
        this.deletionContext = Collections.unmodifiableMap(new LinkedHashMap<>(deletionContext));
    }

    public ResourceType getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    /** Extra path ids the future delete call will need (e.g. {"orgId": ..., "programTermId": ...}). */
    public Map<String, String> getDeletionContext() {
        return deletionContext;
    }

    @Override
    public String toString() {
        return type + "[" + id + "]" + (deletionContext.isEmpty() ? "" : " " + deletionContext);
    }
}
