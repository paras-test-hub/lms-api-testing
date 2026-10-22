package com.lms.utils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates collision-safe unique test data for workflow provisioning, so every run creates
 * a fresh isolated environment with no dependency on pre-existing data and no clashes across
 * repeated or (future) parallel runs. Naming follows the AUTO_&lt;ENTITY&gt;_&lt;suffix&gt; convention.
 *
 * The suffix combines a millisecond timestamp with a small random component, so two entities
 * created in the same millisecond still get distinct values.
 */
public final class TestDataFactory {

    private static final String[] FIRST_NAMES = {"Auto", "QA", "Test", "Automation", "LMS"};
    private static final String[] LAST_NAMES = {"User", "Engineer", "Student", "Bot", "Automation"};
    private static final String[] GUARDIAN_OCCUPATIONS = {"Post Graduate", "Graduate", "Business Owner"};
    private static final String[] GUARDIAN_DESIGNATIONS = {"Manager", "Consultant", "Director"};
    private static final String[] GUARDIAN_COMPANIES = {"Automation Testing Co", "QA Solutions", "LMS Corp"};

    // Static config, not generated data - the student password is fixed for now (the Student
    // Login step needs to know it); the SAME value is used to create the account and to log
    // in with it, so any value CREATE accepts works for login too. Verified live: the create
    // endpoint (POST /api/v1/users/org/{orgId}) validates password complexity server-side -
    // "defaultPassword" was rejected with 400 "password must contain at least 1 letter and 1
    // number" (it has letters but no digit). Must contain uppercase, lowercase, a digit, and a
    // special character - matches the same convention as ADMIN_TEST_PASSWORD in .env.
    private static final String STUDENT_PASSWORD = "Pass@1234";

    private TestDataFactory() {
    }

    private static String uniqueSuffix() {
        return System.currentTimeMillis() + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    public static String programName() {
        return "AUTO_PROGRAM_" + uniqueSuffix();
    }

    public static String cohortName() {
        return "AUTO_COHORT_" + uniqueSuffix();
    }

    public static String courseName() {
        return "AUTO_COURSE_" + uniqueSuffix();
    }

    public static String termName() {
        return "AUTO_TERM_" + uniqueSuffix();
    }

    public static String sectionName() {
        return "AUTO_SECTION_" + uniqueSuffix();
    }

    /** Random automation-friendly first name, so repeated runs don't produce identical full names. */
    public static String studentFirstName() {
        return FIRST_NAMES[ThreadLocalRandom.current().nextInt(FIRST_NAMES.length)];
    }

    /** Random automation-friendly last name. */
    public static String studentLastName() {
        return LAST_NAMES[ThreadLocalRandom.current().nextInt(LAST_NAMES.length)];
    }

    /**
     * Unique sequential student email (paras.sen+10001@venturepact.com, +10002, ...).
     * Delegates to {@link SequentialEmailGenerator} - no timestamps, no random numbers, and
     * the counter can later be swapped for a server-side "highest existing suffix" lookup.
     */
    public static String studentEmail() {
        return SequentialEmailGenerator.nextEmail();
    }

    /** The fixed student password (see {@link #STUDENT_PASSWORD}); reused by the Student Login step. */
    public static String studentPassword() {
        return STUDENT_PASSWORD;
    }

    /**
     * Unique 9-digit numeric mobile number (first digit 6-9). Uniqueness within a run comes
     * from the random 9-digit space (~4x10^8); never hardcoded.
     */
    public static String mobileNumber() {
        StringBuilder sb = new StringBuilder();
        sb.append(6 + ThreadLocalRandom.current().nextInt(4)); // 6..9
        for (int i = 0; i < 8; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    /** Random 6-7 digit numeric-only family income string (100000-9999999); never hardcoded. */
    public static String familyIncomeAmount() {
        return String.valueOf(100_000 + ThreadLocalRandom.current().nextInt(9_900_000));
    }

    /** Random guardian first+last name, reusing the same name pools as {@link #studentFirstName()}. */
    public static String guardianName() {
        return studentFirstName() + " " + studentLastName();
    }

    /** Unique guardian email - the same sequential generator {@link #studentEmail()} uses. */
    public static String guardianEmail() {
        return SequentialEmailGenerator.nextEmail();
    }

    /** Random guardian occupation, e.g. for fatherNameOccupation/motherNameOccupation. */
    public static String guardianOccupation() {
        return GUARDIAN_OCCUPATIONS[ThreadLocalRandom.current().nextInt(GUARDIAN_OCCUPATIONS.length)];
    }

    /** Random guardian designation, e.g. for fatherNameDesignation/motherNameDesignation. */
    public static String guardianDesignation() {
        return GUARDIAN_DESIGNATIONS[ThreadLocalRandom.current().nextInt(GUARDIAN_DESIGNATIONS.length)];
    }

    /** Random guardian company, e.g. for fatherNameCompany/motherNameCompany. */
    public static String guardianCompany() {
        return GUARDIAN_COMPANIES[ThreadLocalRandom.current().nextInt(GUARDIAN_COMPANIES.length)];
    }

    /** Realistic-looking 6-digit numeric pincode (100000-999999); the backend does not validate its format. */
    public static String addressPincode() {
        return String.valueOf(100_000 + ThreadLocalRandom.current().nextInt(900_000));
    }

    /** Matches the backend's course-code pattern {@code ^[A-Z]{2,8}\d{1,6}$} (max 14 chars; this is 10). */
    public static String courseCode() {
        return "AUTO" + (System.currentTimeMillis() % 1_000_000L);
    }

    /** Random course credit, 1-9 inclusive (never double digits - verified requirement). */
    public static int courseCredit() {
        return 1 + ThreadLocalRandom.current().nextInt(9);
    }

    /** Random course hours, 10-100 inclusive. */
    public static int courseHours() {
        return 10 + ThreadLocalRandom.current().nextInt(91);
    }

    /** Random number of course sessions, 5-30 inclusive. */
    public static int courseSessions() {
        return 5 + ThreadLocalRandom.current().nextInt(26);
    }

    // ---- Assignment journey data ----

    /** Unique assignment name (AUTO_ASSIGNMENT_&lt;suffix&gt;), following the AUTO_&lt;ENTITY&gt;_ convention. */
    public static String assignmentName() {
        return "AUTO_ASSIGNMENT_" + uniqueSuffix();
    }

    /**
     * Assignment description as HTML - the real create request sends {@code assignmentDetails} as an
     * HTML fragment (e.g. {@code <p>Testing details</p>}), so this mirrors that shape.
     */
    public static String assignmentDescriptionHtml() {
        return "<p>Runtime-generated assignment created by the automated Assignment journey.</p>";
    }

    /** Random valid maximum marks (total points), 10-100 inclusive; sent as a string to match the API. */
    public static String assignmentTotalPoints() {
        return String.valueOf(10 + ThreadLocalRandom.current().nextInt(91));
    }

    /** Random valid weightage percentage, 1-100 inclusive; sent as a string to match the API. */
    public static String assignmentWeightagePercentage() {
        return String.valueOf(1 + ThreadLocalRandom.current().nextInt(100));
    }

    // ---- Session journey data ----

    /**
     * Unique runtime session name in the required {@code test_<timestamp>} business format (e.g.
     * {@code test_20260727_143501_042}). Deliberately NOT the AUTO_&lt;ENTITY&gt;_ convention the
     * environment entities use: the Session journey's own business rule is that the name must start
     * with {@code test_}, and the Session verification asserts that prefix. The trailing 3-digit
     * random component keeps two sessions created within the same SECOND distinct (the timestamp
     * only resolves to seconds), following the same collision-safety rationale as
     * {@link #uniqueSuffix()}.
     */
    public static String sessionName() {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "test_" + timestamp + String.format("_%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    /**
     * Session description as HTML - the real create request sends {@code description} as an HTML
     * fragment (e.g. {@code <p>Testing session details</p>}), so this mirrors that shape.
     */
    public static String sessionDescriptionHtml() {
        return "<p>Runtime-generated offline session created by the automated Session journey.</p>";
    }

    // ---- Work Experience data ----

    private static final String[] EMPLOYMENT_TYPES = {"FullTime", "PartTime", "Internship", "Contract"};
    private static final String[] LOCATION_TYPES = {"On-site", "Remote", "Hybrid"};
    private static final String[] JOB_PROFILES = {"Software Engineer", "QA Engineer", "Data Analyst", "Product Manager"};
    private static final String[] DESIGNATIONS = {"Associate", "Senior Associate", "Lead", "Manager"};
    private static final String[] INDUSTRIES = {"Technology", "Finance", "Healthcare", "Education"};

    public static String companyName() {
        return "AUTO_COMPANY_" + uniqueSuffix();
    }

    public static String employmentType() {
        return EMPLOYMENT_TYPES[ThreadLocalRandom.current().nextInt(EMPLOYMENT_TYPES.length)];
    }

    public static String locationType() {
        return LOCATION_TYPES[ThreadLocalRandom.current().nextInt(LOCATION_TYPES.length)];
    }

    public static String jobProfile() {
        return JOB_PROFILES[ThreadLocalRandom.current().nextInt(JOB_PROFILES.length)];
    }

    public static String experienceDesignation() {
        return DESIGNATIONS[ThreadLocalRandom.current().nextInt(DESIGNATIONS.length)];
    }

    public static String industry() {
        return INDUSTRIES[ThreadLocalRandom.current().nextInt(INDUSTRIES.length)];
    }

    public static String totalExperience() {
        int months = 1 + ThreadLocalRandom.current().nextInt(36);
        return months + (months == 1 ? " month" : " months");
    }

    // ---- Education Qualification data ----

    private static final String[] BOARDS = {"CBSE", "ICSE", "State Board"};
    private static final String[] SCHOOL_TYPES = {"Jawahar Navodaya Vidyalaya", "Kendriya Vidyalaya", "Private School"};
    private static final String[] MARKING_SCHEMES = {"Percentage", "CGPA"};

    public static String instituteName() {
        return "AUTO_SCHOOL_" + uniqueSuffix();
    }

    public static String board() {
        return BOARDS[ThreadLocalRandom.current().nextInt(BOARDS.length)];
    }

    public static String schoolType() {
        return SCHOOL_TYPES[ThreadLocalRandom.current().nextInt(SCHOOL_TYPES.length)];
    }

    public static String markingScheme() {
        return MARKING_SCHEMES[ThreadLocalRandom.current().nextInt(MARKING_SCHEMES.length)];
    }

    public static String percentage() {
        return String.valueOf(40 + ThreadLocalRandom.current().nextInt(56));
    }

    public static String rollNumber() {
        return String.valueOf(1000000 + ThreadLocalRandom.current().nextInt(9000000));
    }

    public static String subjectName() {
        String[] subjects = {"Mathematics", "Science", "English", "Social Studies", "Computer Science"};
        return subjects[ThreadLocalRandom.current().nextInt(subjects.length)];
    }

    public static String marks() {
        return String.valueOf(30 + ThreadLocalRandom.current().nextInt(71));
    }

    // ---- Accomplishment data ----

    public static String accomplishmentTitle() {
        return "AUTO_ACCOMPLISHMENT_" + uniqueSuffix();
    }

    public static String accomplishmentIssuer() {
        String[] issuers = {"Automation Institute", "QA Academy", "Test Foundation"};
        return issuers[ThreadLocalRandom.current().nextInt(issuers.length)];
    }

    public static String accomplishmentDescription() {
        return "Runtime-generated accomplishment for automated testing - " + uniqueSuffix();
    }

    // ---- Career Preferences data ----

    private static final String[] MBA_DOMAINS = {"Sales", "Consulting", "Marketing", "Finance", "Operations", "Strategy"};

    public static String mbaDomain() {
        return MBA_DOMAINS[ThreadLocalRandom.current().nextInt(MBA_DOMAINS.length)];
    }

    public static String dreamCompanyName() {
        return "AUTO_DREAM_CO_" + uniqueSuffix();
    }

    public static String dreamCompanyRole() {
        return "AUTO_ROLE_" + uniqueSuffix();
    }

    public static String aboutYourself() {
        return "Runtime-generated about-yourself text for automated testing - " + uniqueSuffix();
    }

    public static String professionalAccomplishments() {
        return "Runtime-generated professional accomplishments - " + uniqueSuffix();
    }

    // ---- Student assignment submission data ----

    /**
     * Unique HTML submission description embedding an execution timestamp and a fresh execution
     * UUID, so every run's submission is distinguishable from every other (per the "unique runtime
     * submission data" requirement) without depending on any external system.
     */
    public static String assignmentSubmissionDescriptionHtml() {
        String executionUuid = java.util.UUID.randomUUID().toString();
        String timestamp = java.time.Instant.now().toString();
        return "<p>Automation submission - Execution: " + executionUuid + ", Timestamp: " + timestamp + "</p>";
    }

    /**
     * Fixed placeholder drive-link URL for text/link-only submissions - no real external Drive
     * integration, matching the framework's existing "no invented external integrations" discipline
     * (e.g. TestConfig#getDevRecaptchaToken's placeholder token).
     */
    public static String assignmentSubmissionDriveLink() {
        return "https://example.com/automation-submission-placeholder";
    }

    // ---- Capstone journey data ----

    /**
     * ONE suffix per JVM, shared by every name-bearing capstone helper - deliberately NOT a fresh
     * {@link #uniqueSuffix()} per call.
     *
     * The Capstone configuration scenario (S2) persists a value produced by one of these helpers and
     * then asserts the read-back by equality against a SECOND call to the SAME helper (see
     * CapstoneWorkflow#configureCapstoneResources and its termCourse read-back, which compare the
     * persisted {@code folderName}/{@code name}/{@code link} against
     * {@link #capstoneResourceFolderName()}, {@link #capstoneResourceName()} and
     * {@link #capstoneResourceLink()}). A per-call suffix would return a different value on the
     * read-back call and make correct, genuinely persisted data fail.
     *
     * Collision safety is unaffected: the suffix is still generated at class-initialisation time, so
     * each suite run gets its own value.
     */
    private static final String CAPSTONE_RUN_SUFFIX = uniqueSuffix();

    /**
     * Capstone resource folder name (AUTO_CAPSTONE_FOLDER_&lt;suffix&gt;), following the
     * AUTO_&lt;ENTITY&gt;_ convention. Stable within one JVM so a read-back can compare it by value -
     * see {@link #CAPSTONE_RUN_SUFFIX}.
     */
    public static String capstoneResourceFolderName() {
        return "AUTO_CAPSTONE_FOLDER_" + CAPSTONE_RUN_SUFFIX;
    }

    /**
     * Capstone resource name (AUTO_CAPSTONE_RESOURCE_&lt;suffix&gt;), following the
     * AUTO_&lt;ENTITY&gt;_ convention. Stable within one JVM so a read-back can compare it by value -
     * see {@link #CAPSTONE_RUN_SUFFIX}.
     */
    public static String capstoneResourceName() {
        return "AUTO_CAPSTONE_RESOURCE_" + CAPSTONE_RUN_SUFFIX;
    }

    /**
     * Fixed placeholder URL for the link-only capstone resource, following the same convention as
     * {@link #assignmentSubmissionDriveLink()} - no real external integration and no environment
     * host, matching the framework's "no invented external integrations" discipline. Deliberately
     * suffix-free: nothing about the link needs to be unique per run.
     */
    public static String capstoneResourceLink() {
        return "https://example.com/automation-capstone-resource-placeholder";
    }

    /**
     * Capstone team name (AUTO_CAPSTONE_TEAM_&lt;suffix&gt;), following the AUTO_&lt;ENTITY&gt;_
     * convention. Stable within one JVM so a read-back can compare it by value - see
     * {@link #CAPSTONE_RUN_SUFFIX}. The Capstone team scenario (S3) sends this value as the
     * team-creation {@code name} and then asserts the read-back {@code name} by equality against a
     * SECOND call to this helper, so a per-call suffix would fail genuinely persisted data.
     */
    public static String capstoneTeamName() {
        return "AUTO_CAPSTONE_TEAM_" + CAPSTONE_RUN_SUFFIX;
    }

    /**
     * Human-readable Capstone team description, following the convention of
     * {@link #accomplishmentDescription()} and {@link #aboutYourself()}. Stable within one JVM so a
     * read-back can compare it by value - see {@link #CAPSTONE_RUN_SUFFIX}.
     */
    public static String capstoneTeamDescription() {
        return "Runtime-generated capstone team for automated testing - " + CAPSTONE_RUN_SUFFIX;
    }

    /**
     * Display label for the Capstone team's {@code groupRepresentative}, stable within one JVM - see
     * {@link #CAPSTONE_RUN_SUFFIX}.
     *
     * The label is GENERATED rather than composed from the student's real name because
     * {@code ExecutionContext} holds no student first-name or last-name field: its runtime student
     * state is {@code studentUuid}, {@code studentEmail}, {@code studentPassword},
     * {@code studentToken}, {@code studentRefreshToken}, {@code studentUserId},
     * {@code studentProgramId}, {@code studentCohortId} and {@code studentNumericId} - none of which
     * carries a name.
     *
     * The label is a cosmetic display value only. The team-membership verdict is derived from
     * {@code groupRepresentative.value} matched against the runtime student uuid, never from
     * {@code label}.
     */
    public static String capstoneTeamRepresentativeLabel() {
        return "AUTO Representative " + CAPSTONE_RUN_SUFFIX;
    }
}
