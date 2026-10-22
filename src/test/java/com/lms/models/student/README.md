# Student-specific request/response models (`com.lms.models.student`)

POJOs for student-flow request/response bodies, created **on demand** alongside the client
that uses them in `com.lms.clients.student`.

Only add a model here once its shape has been **verified against a live response** (see
`com.lms.clients.student`'s README) — don't pre-model fields off the OpenAPI spec's
placeholder schemas.

Shared/cross-cutting models used by both roles (`LoginRequest`, `LoginResponse`, `TokenPair`,
`TokenDetail`, `ErrorResponse`, `LoginData` — from Phase 3) stay directly under
`com.lms.models` — they don't move here.
