# Student-specific API clients (`com.lms.clients.student`)

One client class per student OpenAPI tag, created **on demand** once a concrete flow for
that tag is described — this folder intentionally starts empty.

## Why this subpackage exists

Several tag names are shared between the student and admin specs (`program`, `feed`,
`allyBot`, `lectureLense`, `form`, `authentication`). Splitting student-specific and
admin-specific clients into `com.lms.clients.student` / `com.lms.clients.admin` avoids
Java class-name collisions (`StudentProgramClient` vs. having to prefix every class name
manually) and keeps the package itself communicating which role a client belongs to.

Cross-cutting clients used by both roles (`AuthenticationClient`) or clients covering a
verified cross-tag group (`StudentDashboardClient`, see Phase 4) stay directly under
`com.lms.clients` — they don't move here.

## Naming convention

`{PascalCase(tag)}Client.java` — e.g. tag `academicSummary` -> `AcademicSummaryClient.java`,
tag `clubMember` -> `ClubMemberClient.java`.

See `../../../../../../../TAG_MANIFEST.json` (project root) for the full tag -> PascalCase ->
endpoint-count mapping used to generate the placeholder feature files in
`src/test/resources/features/student/`.

## Before writing a real client here

1. Confirm the corresponding placeholder feature file exists at
   `src/test/resources/features/student/{PascalCase(tag)}.feature` (it does — all 26 student
   tags got one during the folder-structure scaffolding pass, except `Authentication.feature`
   / `StudentDashboard.feature` which already have real scenarios from Phases 3-4).
2. **Live-probe the endpoint(s) first** (auth via the existing `AuthenticationClient` +
   `TestConfig` credentials) rather than modeling response shapes off the OpenAPI spec — its
   response schemas are unverified placeholders (`{ success: true }`), generated from frontend
   route extraction, not real response capture. See Phase 3/4 completion docs for why.
3. Write the client, its models (in `com.lms.models.student` if response shapes are
   tag-specific), the steps (in `com.lms.steps.student`), and replace the placeholder
   `.feature` file's content with real scenarios.
