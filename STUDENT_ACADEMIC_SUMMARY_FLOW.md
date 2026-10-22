# Student Academic Summary Flow ✅ COMPLETE (pending real credentials for full verification)

**Date Completed**: 2026-07-09
**Model Used**: Sonnet
**Verified live**: 4/7 scenarios pass against `https://api-dev.mastersunion.in` (all 4
authorization-enforcement scenarios). The 3 happy-path scenarios correctly fail with 401 at
the login step itself — same placeholder-credential root cause flagged since Phase 3, not a
defect in this flow's code.

## What Was Created

### `clients/student/AcademicSummaryClient.java`
The first client in the new `com.lms.clients.student` subpackage (see the folder-structure
scaffolding from the previous turn). Covers all 3 endpoints under `student_openapi.json`'s
`academicSummary` tag:
- `GET /api/v1/users/stats/getUserAcademicsStats` — no path param
- `GET /api/v1/users/stats/{id}/getUserAcademicsStatsDomainWise` — path param: a domain id
- `GET /api/v1/program/{programTermId}/getLastestAssignmentsEventsClubs` — path param: programTermId

Paths, path params, and the bearer-auth requirement are confirmed directly from the OpenAPI
spec (trustworthy). Response **bodies** are not — same caveat as every other unverified tag,
since the spec's response schemas are generated placeholders (`{ "success": true }`), not
captured from real traffic.

### `steps/student/AcademicSummarySteps.java`
Deliberately does **not** redefine a login step — reuses
`"the student logs in with valid credentials"` from `com.lms.steps.StudentDashboardSteps`
(Phase 4), since Cucumber's glue is global across all step classes on the glue path.
Duplicating it here would have caused an "ambiguous step definition" error.

### `features/student/AcademicSummary.feature`
Replaced the placeholder from the folder-structure scaffolding with 7 real scenarios:
- 4 authorization-enforcement scenarios (one per endpoint... well, per distinct request shape
  — 2 for the no-path-param endpoint covering missing token AND invalid token, 1 each for the
  two path-param endpoints covering missing token) — **all verified live, all passing**,
  confirmed the backend rejects unauthenticated requests before even looking at path param
  validity (tested with obviously-fake `"placeholder-domain-id"` / `"placeholder-term-id"`
  values and still got 401, not 400/404).
- 3 happy-path scenarios, tagged `@requires-credentials` — the overall-stats one is ready to
  run as-is once real credentials exist; the domain-wise and program-term ones are additionally
  tagged `@pending-real-id` because they need a **real** domain id / programTermId, which
  can't be discovered without first seeing an authenticated response.

## Known Gap: Real IDs for the Two Path-Param Endpoints

Unlike Phase 4's dashboard endpoints (which took no path params beyond a uuid already
extracted from the login response), two of these three endpoints need IDs whose real values
are still unknown:
- **Domain id** — best guess is it's discoverable inside the overall academic stats response
  itself (a "domain-wise breakdown" plausibly lists domain ids), but this is unconfirmed.
- **Program term id** — best guess is it's a field in the `program` dashboard endpoint's
  response (`StudentDashboardClient#get("program", token)`, verified working in Phase 4), but
  the exact field name is unconfirmed.

Once real credentials are added to `.env`, the next step for this flow is:
1. Run the "overall academic statistics" happy-path scenario and inspect the real response
   body for a domain-id-shaped field.
2. Run the Phase-4 `program` endpoint and inspect its response for a programTermId-shaped
   field.
3. Replace the `"domain-id-todo"` / `"term-id-todo"` literals in `AcademicSummary.feature`
   with real values (or better, add chaining steps that extract them dynamically from a prior
   response within the same scenario, so the tests don't hardcode IDs tied to one specific
   test account).

## Verification Performed

```bash
mvn test
# Tests run: 30, Failures: 13 (10 pre-existing + 3 new, all placeholder-credential-related)
```

Confirmed via request/response logs that all 3 endpoints genuinely enforce bearer-auth
(401 with a clear `{"code":401,...}` body) regardless of whether the path segment values are
real or fake — this rules out the concern that testing with placeholder IDs might trigger a
500 or other unexpected error instead of a clean 401.

## Next Steps

Same standing item as Phases 3-4: add real credentials to `.env` (both student and admin) so
this flow's 3 happy-path scenarios — and the two `@pending-real-id` ones, once real IDs are
substituted in — can be fully closed out.

Ready for the next flow whenever you describe one — the folder skeleton from the previous
turn has placeholders for all remaining student and admin tags (see `TAG_MANIFEST.json`).
