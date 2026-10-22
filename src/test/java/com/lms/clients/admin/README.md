# Admin-specific API clients (`com.lms.clients.admin`)

One client class per admin OpenAPI tag, created **on demand** once a concrete flow for that
tag is described — this folder intentionally starts empty.

## Why this subpackage exists

See `../student/README.md` — same reasoning. Several tag names are shared between the
student and admin specs (`program`, `feed`, `allyBot`, `lectureLense`, `form`,
`authentication`); keeping admin-specific clients in their own subpackage avoids class-name
collisions and makes the role explicit from the package alone.

Cross-cutting clients used by both roles (`AuthenticationClient`) stay directly under
`com.lms.clients` — they don't move here.

## Naming convention

`{PascalCase(tag)}Client.java` — e.g. tag `reportCard` -> `ReportCardClient.java`, tag
`lifeAtMu-clubs` -> `LifeAtMuClubsClient.java`, tag `placementJobsList` ->
`PlacementJobsListClient.java`.

See `../../../../../../../TAG_MANIFEST.json` (project root) for the full tag -> PascalCase ->
endpoint-count mapping used to generate the placeholder feature files in
`src/test/resources/features/admin/` (49 of the admin spec's 50 tags — `authentication`
already has real scenarios from Phase 3).

## Before writing a real client here

1. Confirm the corresponding placeholder feature file exists at
   `src/test/resources/features/admin/{PascalCase(tag)}.feature`.
2. **Live-probe the endpoint(s) first** using real admin credentials once available (see
   `PHASE_3_COMPLETED.md` / `PHASE_4_COMPLETED.md` — admin endpoints beyond login are **not
   yet verified** against the live backend by any existing suite, unlike several student
   endpoints). Don't model response shapes off the OpenAPI spec alone — its schemas are
   unverified placeholders generated from frontend route extraction.
3. Write the client, its models (in `com.lms.models.admin` if response shapes are
   tag-specific), the steps (in `com.lms.steps.admin`), and replace the placeholder
   `.feature` file's content with real scenarios.

## Note on admin login rate-limiting

`PHASE_4_COMPLETED.md` documented intermittent `406` responses from
`POST /api/v1/auth/login` under rapid repeated testing, suspected to be brute-force/rate-limit
protection. Keep this in mind when live-probing new admin endpoints in quick succession —
space out repeated login attempts if you see unexpected non-401/200 statuses.
