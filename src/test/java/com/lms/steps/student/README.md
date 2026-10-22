# Student-specific step definitions (`com.lms.steps.student`)

One steps class per student flow, created **on demand** alongside its client in
`com.lms.clients.student` — see that package's README for the full convention and rationale
for the role-based subpackage split.

Cucumber's glue-path resolution (`GLUE_PROPERTY_NAME` in `RunCucumberTest.java`, currently
`com.lms.steps,com.lms.hooks`) scans subpackages recursively, so classes placed here are
picked up automatically — no changes needed to `RunCucumberTest.java` or
`cucumber.properties` when adding a new steps class here.

Naming convention: `{PascalCase(tag)}Steps.java` — matches the corresponding client name
(minus the `Client`/`Steps` suffix), e.g. `AcademicSummaryClient.java` pairs with
`AcademicSummarySteps.java`.
