package com.lms.cleanup;

/**
 * Categories of LMS entities that a workflow creates and that a future CleanupManager will
 * need to tear down. Declared in dependency order (parents before children); cleanup will
 * iterate the registered resources in REVERSE so children are removed before their parents.
 */
public enum ResourceType {
    PROGRAM,
    COHORT,
    PROGRAM_TERM,
    SECTION,
    COURSE,
    SECTION_COURSE,
    STUDENT,
    ENROLLMENT,
    // ---- Business-journey entities (created after the environment, torn down first) ----
    ASSIGNMENT,
    ASSIGNMENT_SUBMISSION,
    SESSION,
    ATTENDANCE
}
