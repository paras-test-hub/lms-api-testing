# LMS API Testing Framework — Architectural Design

**Version:** 1.0.0  
**Framework:** Java 11 + RestAssured + Cucumber  
**Purpose:** Document design philosophy, architectural principles, and decision rationale

---

## 1. Core Principles

### 1.1 HAR-Driven Testing Philosophy

**Principle:** All API shapes, payloads, and response structures come from captured HTTP Archive (HAR) files, never from specification documents or assumptions.

**Why This Matters:**
- **Single Source of Truth:** A HAR is an immutable record of what the browser actually sent and received
- **Specification Lag:** Docs often lag implementation; HAR always reflects current behavior
- **No Invented Tests:** Tests never accidentally test something the real frontend doesn't do
- **Portable & Maintainable:** When an API changes, the HAR captures it; tests update based on facts

**In Practice:**
```
Real Browser Interaction (HAR Capture)
       ↓
Entry 23: POST /program/{cohort}/termSection { programTermId, createdBy, isCapstoneSection }
Entry 24: GET /program/{cohort}/programTerm (verify creation)
       ↓
Test Code Mirrors Exact Payload
CohortClient.createSection() → { programTermId, createdBy } ← captured from entry 23
Workflow verifies response includes { uuid, name, programTermId } ← captured from entry 24
```

**When HAR Data is Missing:**
- If a business operation doesn't appear in any HAR (concern creation across 20 HARs), the test is blocked
- We don't invent endpoints or payload shapes
- We document the gap and flag it for new HAR capture

---

### 1.2 Workflow-Driven Architecture

**Principle:** Business logic lives in workflow classes, not scattered across individual HTTP calls or step definitions.

**Why Workflows Instead of Endpoint-Driven Tests?**

**Endpoint-Driven (Anti-Pattern):**
```gherkin
Scenario: Student views assignment
  When the admin calls POST /assignments with { name, description }
  Then the response has status 201
  When the student calls GET /student/assignments
  Then the response contains { name, description }
  When the admin calls PATCH /assignments/{id} with { status: "published" }
  Then the response has status 200
  When the student calls GET /student/assignments
  Then the response shows { status: "published" }
```
**Problems:**
- Tests are HTTP specifications, not business scenarios
- No distinction between "request accepted" (201) and "change persisted"
- Repeats the same read-back pattern across 10 tests
- Hard to maintain (change read path → update 10 tests)

**Workflow-Driven (This Framework):**
```gherkin
Scenario: Admin creates assignment and student retrieves it
  When the admin creates an assignment with name and description
  Then the student can retrieve the assignment
  When the admin publishes the assignment
  Then the student sees it as published
```

**Implementation:**
```java
// All business logic in one workflow
public class AssignmentWorkflow {
    public void createAssignment(ScenarioContext ctx) {
        Response r = assignmentClient.create(ctx.getPayload());
        assertThat(r.statusCode()).isBetween(200,299);
        // Extract and store uuid
        ctx.setAssignmentUuid(...);
    }
    
    public void verifyStudentSeesAssignment(ScenarioContext ctx) {
        Response r = studentClient.listAssignments(ctx.getStudentToken());
        // Assert assignment appears by name
    }
    
    public void publishAssignment(ScenarioContext ctx) {
        Response r = assignmentClient.update(ctx.getAssignmentUuid(), "published");
        assertThat(r.statusCode()).isBetween(200,299);
    }
    
    public void verifyStudentSeesPublished(ScenarioContext ctx) {
        Response r = studentClient.getAssignmentDetail(ctx.getAssignmentUuid(), ...);
        assertThat(r.jsonPath().getString("status")).isEqualTo("published");
    }
}

// Steps are just thin wrappers
@When("the admin creates an assignment with name and description")
public void adminCreates() {
    workflow.createAssignment(context);
}
```

**Benefits:**
- **Reusability:** `createAssignment()` called from 5 different scenarios, stays synchronized
- **Clarity:** Method name tells you what business action it performs
- **Maintainability:** Change how creation works → update one method, 5 tests benefit
- **Testability:** Can unit-test workflow logic in isolation (not done here, but possible)

---

### 1.3 Business Verification Over HTTP Status

**Principle:** Assertions verify business outcomes (data persisted correctly, visible to right actors), not just HTTP status codes.

**Why Status Codes Aren't Enough:**

```
Admin sends: PUT /student/profile/dob { dateOfBirth: "2000-01-15" }
Server responds: 200 OK

Assertion 1 (Wrong): assertThat(response.statusCode()).isEqualTo(200) ✅ PASSES
Assertion 2 (Right): assertThat(
    studentClient.getProfile().jsonPath().getString("dateOfBirth")
).isEqualTo("2000-01-15")
```

If the backend has a timezone bug and persists `"2000-01-14"` instead of `"2000-01-15"`, Assertion 1 still passes but Assertion 2 catches the bug.

**Pattern: Create → Verify Persistence**

Every workflow follows this cycle:

```java
// Stage 1: Create via HTTP
Response createResp = client.create(payload);
assertThat(createResp.statusCode()).isBetween(200, 299);
String uuid = extractUuid(createResp);

// Stage 2: Verify via independent GET
Response getResp = client.get(uuid);
assertThat(getResp.statusCode()).isBetween(200, 299);
Map<String, Object> persisted = getResp.jsonPath().getMap("Data");

// Stage 3: Assert persisted values match sent values
assertThat(persisted.get("fieldName"))
    .as("the backend must persist exactly what we sent")
    .isEqualTo(sentValue);
```

**Why This Matters for API Testing:**
- **Layer Separation:** HTTP layer (status) tells you if request was routed; data layer (read-back) tells you if it was processed
- **Silent Failures:** A 200 with corrupted data is worse than a 500 — at least 500 tells you something's wrong
- **Doubles as Regression Detector:** If a backend update breaks reads without breaking writes, we catch it immediately

---

### 1.4 Runtime Isolation (No Seeded Data)

**Principle:** Each test suite run creates its own environment (program, cohort, student). Never depends on pre-existing seeded data.

**Why Isolation?**

| Aspect | With Seeds | With Runtime Isolation |
|--------|-----------|----------------------|
| **Suite Repeatability** | Depends on seed data staying stable | Creates fresh data each run |
| **Parallel Execution** | Multiple suites interfere | Completely independent |
| **Failure Debugging** | "Was seed data deleted?" Unknown | "I created this; I see what I created" |
| **CI/CD Confidence** | "Tests pass locally but fail in CI" | Same behavior everywhere |
| **Cleanup Risk** | "Did cleanup actually work?" | No cleanup needed if not created |

**What We Don't Reuse:**
- ❌ Hardcoded program/cohort/student uuids
- ❌ Pre-seeded users or roles
- ❌ Pre-created courses or programs
- ❌ Database snapshots or fixtures

**What We DO Create at Suite Start:**
- ✅ Program (AUTO_PROGRAM_*)
- ✅ Cohort (AUTO_COHORT_*)
- ✅ Program Term (active window: startDate ≤ today ≤ endDate)
- ✅ Section (AUTO_SECTION_*)
- ✅ Course (AUTO_COURSE_*)
- ✅ Student (paras.sen+N@venturepact.com)
- ✅ Student enrollment in section
- ✅ Both admin and student session tokens

**Cost/Benefit:**
- **Cost:** ~30s overhead per suite run (create program + course + student enrollment)
- **Benefit:** Complete isolation, no data cleanup needed, parallel-safe infrastructure

---

### 1.5 Layered Architecture (Separation of Concerns)

**Principle:** Each layer has a single, well-defined responsibility.

```
Gherkin Features (What to test)
       ↓ (Human-readable user stories)
Step Definitions (Thin wrappers)
       ↓ (Just call workflow methods)
Workflows (Business logic)
       ↓ (Orchestrate client calls, verify outcomes)
Clients (HTTP abstraction)
       ↓ (Stateless HTTP wrappers)
RestClient (Network layer)
       ↓ (Actual HTTP requests)
API Server
```

**Why This Layering?**

**Layer 1: Gherkin Features**
- **Responsibility:** Specify business scenarios in human-readable language
- **Who:** Business analysts, QA leads, developers
- **What They Control:** Scenario names, Given-When-Then flow
- **Example:**
  ```gherkin
  Scenario: Admin publishes assignment and student sees it
    Given the admin has created an assignment
    When the admin publishes the assignment
    Then the student can see the published assignment
  ```

**Layer 2: Step Definitions**
- **Responsibility:** Bind Gherkin sentences to workflow methods
- **Who:** Developers (minimal effort)
- **What They Control:** Which workflow method to call
- **Example:**
  ```java
  @When("the admin publishes the assignment")
  public void adminPublishes() {
      workflow.publishAssignment(context);
  }
  ```
- **Constraint:** No HTTP calls, no assertions — just call workflow

**Layer 3: Workflows**
- **Responsibility:** Orchestrate business operations (call clients, verify outcomes)
- **Who:** Developers (core logic)
- **What They Control:** API call sequence, read-back verification, error handling
- **Example:**
  ```java
  public void publishAssignment(ScenarioContext ctx) {
      String assignmentId = ctx.getAssignmentId();
      Response r = assignmentClient.update(assignmentId, "published");
      assertThat(r.statusCode()).isBetween(200, 299);
      
      // Verify persistence
      Response get = assignmentClient.get(assignmentId);
      assertThat(get.jsonPath().getString("status"))
          .as("the assignment must be published in the backend")
          .isEqualTo("published");
  }
  ```

**Layer 4: Clients**
- **Responsibility:** HTTP abstraction (one method = one API endpoint)
- **Who:** Developers (API-focused)
- **What They Control:** Request/response shapes, endpoint URLs
- **Constraint:** Stateless, no business logic, no assertions
- **Example:**
  ```java
  public Response update(String assignmentId, String status) {
      Map<String, Object> payload = new HashMap<>();
      payload.put("status", status);
      return restClient.patch(
          "/assignments/" + assignmentId,
          payload,
          bearerToken
      );
  }
  ```

**Layer 5: RestClient**
- **Responsibility:** HTTP requests/responses, logging, error handling
- **Who:** Developers (if HTTP behavior needs tuning)
- **What They Control:** Request building, retry logic, logging level

**Benefits of Layering:**
- **Isolation:** A change in how clients build URLs doesn't touch workflow logic
- **Reusability:** A workflow can be used by 3 different step definitions
- **Testing:** Workflows can theoretically be tested with mock clients
- **Debugging:** Each layer has clear responsibility — easier to find bugs

---

## 2. Design Patterns

### 2.1 The Context Pattern (Singleton + Delegation)

**Problem:** Multiple scenarios need to access shared test environment (program, cohort, student).

**Solution:** Singleton `ExecutionContext` (suite-scoped) + delegating `ScenarioContext` (scenario-scoped).

**ExecutionContext (Suite Scope)**
```java
public class ExecutionContext {
    private static ExecutionContext instance; // Singleton
    
    // Environment (set once at suite start, read by all scenarios)
    private String programId;
    private String cohortId;
    private String studentUuid;
    private String studentToken;
    
    // Profile state (accumulated across scenarios)
    private Map<String, Object> personalDetails;
    private List<Map<String, Object>> addresses;
    
    public static ExecutionContext getInstance() {
        if (instance == null) {
            instance = new ExecutionContext();
        }
        return instance;
    }
}
```

**ScenarioContext (Scenario Scope)**
```java
public class ScenarioContext {
    private ExecutionContext execution;
    
    // All getters delegate to ExecutionContext
    public String getStudentUuid() {
        return execution.getStudentUuid();
    }
    
    // Could hold scenario-ephemera here
    // (currently minimal — most state goes to ExecutionContext)
}
```

**How It Works:**
1. **Suite Setup:** SuiteLifecycle creates ExecutionContext singleton
2. **Bootstrap:** BootstrapWorkflow stores program/cohort/student in ExecutionContext
3. **Each Scenario:** PicoContainer creates ScenarioContext, passes it to steps
4. **Steps Call Workflows:** Workflows read/write to ScenarioContext (which delegates to ExecutionContext)
5. **Cleanup:** CleanupWorkflow reads from ExecutionContext to know what to delete

**Tradeoff:**
- **Pro:** Simple, centralized access to environment data
- **Con:** Scenarios aren't fully isolated (Profile Scenario 1 creates data; Scenario 2 can see it)
- **Mitigation:** Scenarios designed to be independent (each creates its own entities)

---

### 2.2 The Verification Pattern (Create → Read → Assert)

**Pattern:** After every mutation (POST, PUT, PATCH), immediately read back via GET and verify persisted state.

```java
public void updateDateOfBirth(ScenarioContext context) {
    String studentUuid = context.getStudentUuid();
    LocalDate newDob = LocalDate.of(1999, 5, 15);
    
    // Stage 1: Mutate
    Response updateResp = studentProfileClient.updateDateOfBirth(
        studentUuid,
        newDob,
        context.getStudentToken()
    );
    assertThat(updateResp.statusCode()).isBetween(200, 299);
    
    // Stage 2: Read back
    Response getResp = studentProfileClient.getProfile(
        studentUuid,
        context.getStudentToken()
    );
    assertThat(getResp.statusCode()).isBetween(200, 299);
    
    // Stage 3: Assert persisted correctly
    LocalDate persistedDob = DateUtils.toCalendarDate(
        getResp.jsonPath().get("Data.dateOfBirth")
    );
    assertThat(persistedDob)
        .as("the backend must persist the exact date of birth sent")
        .isEqualTo(newDob);
}
```

**Why This Pattern?**
- **Catches Data Corruption:** A 200 response with corrupted data would slip past a status-only assertion
- **Catches Timezone Bugs:** Read-back in IST catches if backend stored UTC instead
- **Catches Silent Failures:** If the backend silently drops a field, the read-back catches it
- **Validates Both Directions:** Verifies the backend correctly ingested AND persisted the data

---

### 2.3 The Isolation Pattern (Sibling Tests Within a Scenario)

**Problem:** A single scenario creates multiple addresses (or guardians, or accomplishments). How do you verify they don't corrupt each other?

**Solution:** Assert that changes to one entity don't affect siblings.

```java
public void verifyAddressesIsolated(ScenarioContext context) {
    // Scenario created: address1 (permanent=true), address2 (permanent=false)
    
    // Verify address1 properties persist unchanged
    Response get1 = clientAddress.get(address1Uuid, token);
    assertThat(get1.jsonPath().getBoolean("Data.isPermanent")).isTrue();
    assertThat(get1.jsonPath().getString("Data.city")).isEqualTo("Mumbai");
    
    // Verify address2 properties persist unchanged
    Response get2 = clientAddress.get(address2Uuid, token);
    assertThat(get2.jsonPath().getBoolean("Data.isPermanent")).isFalse();
    assertThat(get2.jsonPath().getString("Data.city")).isEqualTo("Delhi");
    
    // Verify they both appear in list (not deleted)
    Response list = clientAddress.list(token);
    List<String> uuids = list.jsonPath().getList("Data.uuid");
    assertThat(uuids).contains(address1Uuid, address2Uuid);
}
```

**Why This Matters:**
- **Catches Cascade Bugs:** If updating address1 accidentally clears the list, we catch it
- **Proves Isolation:** Proves the backend treats entities as separate objects
- **Regression Prevention:** If a future update adds a bug in list filtering, we catch it

---

### 2.4 The HAR-First Documentation Pattern

**Pattern:** Every endpoint is documented with its HAR entry number and verified payload shape.

```java
/**
 * POST /api/v1/program/{batchId}/programTerm/{programTermId}/section - creates a section.
 * Verified via HAR hawkeye-apis_2026-07-28_13-04-37.har entry 12.
 * Request shape: { sectionName, createdBy }
 * Response shape: { Data: { uuid, name, programTermId, ... } }
 */
public Response createSection(String programTermId, Map<String, Object> fields, String createdBy, String bearerToken) {
    Map<String, Object> payload = new HashMap<>(fields);
    payload.put("createdBy", createdBy);
    return restClient.post(programBase() + "/programTerm/" + programTermId + "/section", payload, bearerToken);
}
```

**Why?**
- **Traceability:** Future maintainer can open the HAR and verify the endpoint still works that way
- **Change Detection:** If backend changes the endpoint URL or payload, HAR re-capture will show it
- **Specification:** Replaces the need for separate API documentation (HAR IS the documentation)

---

## 3. Architectural Decisions

### 3.1 Why RestAssured Instead of WebDriver?

| Aspect | RestAssured (Chosen) | WebDriver |
|--------|-------------|---------|
| **Scope** | REST APIs | Browser automation |
| **Test Speed** | 1-2s per scenario | 20-30s per scenario |
| **Maintenance** | Stable (API rarely changes UI) | Fragile (UI changes break selectors) |
| **Parallelization** | Easy (stateless HTTP) | Hard (browser instances conflict) |
| **Debugging** | Request/response in logs | Screenshot + console (harder to reason about) |

**Decision:** This framework is API-focused. RestAssured is the right tool.

### 3.2 Why Cucumber Over JUnit?

| Aspect | Cucumber (Chosen) | JUnit |
|--------|---------|------|
| **Scenario Definition** | Human-readable Gherkin | Code-based annotations |
| **Stakeholder Communication** | Non-technical stakeholders can read features | Only developers understand |
| **Specification as Test** | Feature files serve as specification | Test code isn't documentation |
| **Reporting** | HTML reports with embedded data | Limited reporting |

**Decision:** BDD is a good fit for API testing (clear business workflows). Gherkin features double as specification.

### 3.3 Why HAR-Driven Over Spec-Driven?

| Aspect | HAR-Driven (Chosen) | Spec-Driven |
|--------|---------|---------|
| **Source of Truth** | Actual browser behavior | Written specification |
| **Accuracy** | Always matches real usage | Spec might lag implementation |
| **Invented Scenarios** | Impossible (HAR has only real data) | Possible (spec might cover unused paths) |
| **Maintenance** | Re-capture HAR when API changes | Update written spec when API changes |

**Decision:** HAR-driven is more reliable. Spec documents are often outdated.

### 3.4 Why Isolated Environment Over Seeded Data?

| Aspect | Isolated (Chosen) | Seeded |
|--------|---------|--------|
| **Data Consistency** | Fresh data each run | Seed data might be stale |
| **Parallel Safe** | Multiple suites can run simultaneously | Suites interfere |
| **Debugging Failed Runs** | Know exactly what was created | Unknown if seeds were used/deleted |
| **Cleanup Complexity** | Simple (delete what we created) | Complex (preserve seeds, delete temp data) |

**Decision:** Isolation is worth the creation overhead (~30s per suite). Enables parallelization and removes ambiguity.

---

## 4. Scalability Considerations

### 4.1 Current Constraints

**Execution Time**
- **Environment Setup:** ~30s (program + course + student provisioning)
- **Journey Scenarios:** ~2s each (average across 24 scenarios)
- **Suite Total:** ~90s sequential

**Parallelization Blocker**
- ExecutionContext singleton prevents parallel scenarios
- Fix: Thread-local ExecutionContext (planned, not implemented)

**Memory Footprint**
- ExecutionContext holds ~30+ fields (negligible)
- Largest memory use: Cucumber report building (embedded base64 reports)

### 4.2 Scaling to 100+ Scenarios

**What Would Break?**
1. **Execution Time:** 100 scenarios × 2s = 200s (acceptable for CI)
2. **Report Size:** Large embedded reports would slow HTML generation
3. **Cleanup Performance:** Deleting 100+ created entities takes ~10s

**How to Mitigate?**
1. **Parallelize:** Make ExecutionContext thread-local (currently prevents this)
2. **Report Streaming:** Don't embed full reports; stream to external storage
3. **Cleanup Optimization:** Bulk delete via database (currently uses individual API calls)

### 4.3 Adding New Journeys

**Current Effort:** ~2-3 hours to add a new journey (e.g., ConcernJourney was 3 hours)

**Pattern:**
1. Analyze HAR to find all endpoints (3 calls for a simple journey, 15+ for complex ones)
2. Create `*Workflow.java` with CRUD methods
3. Create `*JourneySteps.java` with thin step definitions
4. Create `*Journey.feature` with Gherkin scenarios
5. Add context fields to ExecutionContext (if needed)
6. Create `*JourneyReport.java` for checkpoints
7. Test and debug

**Bottleneck:** HAR analysis. If HAR is missing operations, journey is blocked.

---

## 5. Integration with CI/CD

### 5.1 Expected CI Pipeline

```yaml
stages:
  - compile
  - test
  - report
  - cleanup

compile:
  mvn test-compile

test:
  # Run suite with environment-specific profile
  mvn test -P staging
  
  # Or run specific tags (faster feedback)
  mvn test -Dcucumber.filter.tags="@journey and not @blocked"

report:
  # Generate HTML report (automatic via maven-cucumber-reporting)
  # Artifacts: target/cucumber-reports/index.html

cleanup:
  # Not needed (suite auto-deletes created resources)
```

### 5.2 Environment Configuration

**Dev (Local Machine)**
```
.env:
  API_BASE_URL=https://api-dev.mastersunion.in/api
  ADMIN_EMAIL=admin@institution.org
  ADMIN_PASSWORD=(from password manager)
  REQUEST_TIMEOUT_SECONDS=90
```

**Staging (CI)**
```
.env:
  API_BASE_URL=https://api-staging.mastersunion.in/api
  ADMIN_EMAIL=ci-admin@institution.org
  ADMIN_PASSWORD=(from CI secrets)
  REQUEST_TIMEOUT_SECONDS=120  (longer timeout for shared CI infrastructure)
```

**Production (Canary)**
```
.env:
  API_BASE_URL=https://api.mastersunion.in/api
  ADMIN_EMAIL=smoke-test@institution.org
  ADMIN_PASSWORD=(from CI secrets, never committed)
  REQUEST_TIMEOUT_SECONDS=60
```

---

## 6. Future Evolution

### 6.1 Planned Enhancements

**Medium Priority (Weeks 1-2)**
- [ ] Implement Attendance Feedback Journey (analyzed, blocked on user clarification)
- [ ] Implement Capstone Environment Journey (analyzed, payloads extracted)
- [ ] Implement Assignment Grading Journey (analyzed, blocked on user clarification)
- [ ] Add concern-creation endpoint once HAR available

**Low Priority (Month 2)**
- [ ] Make ExecutionContext thread-local for parallelization
- [ ] Add performance assertions (response time < 500ms)
- [ ] Add more error scenario journeys (currently mostly happy path)
- [ ] Implement Quiz/Assessment journey (needs HAR)

**Very Low Priority (Nice-to-Have)**
- [ ] Add contract testing (verify API against OpenAPI spec)
- [ ] Add mutation testing (verify tests catch broken mutations)
- [ ] Database-level cleanup (faster than API-level deletion)

### 6.2 Architectural Improvements (Not Implemented)

**Why Not Implemented Now?**
- Framework is currently sufficient for regression testing
- Premature optimization (YAGNI principle)
- Each improvement trades simplicity for minor performance gain

**Potential Improvements:**

1. **Dependency Injection for Clients**
   - Current: Each workflow creates its own client instances
   - Potential: Inject clients via constructor (Spring/Guice)
   - Benefit: Easier to mock for unit tests (not a goal)
   - Cost: Add DI framework, more complexity
   - Decision: Not worth it (currently no need to unit-test workflows)

2. **Async/Parallel Scenario Execution**
   - Current: Sequential (one scenario at a time)
   - Potential: Run scenarios in parallel with thread-local context
   - Benefit: ~90s → ~30s execution (3x speedup with 3 threads)
   - Cost: Debugging parallelization bugs (hard), thread-safe context, test isolation
   - Decision: Defer until suite grows to 50+ scenarios

3. **Database-Level Cleanup**
   - Current: Call DELETE endpoints for each resource
   - Potential: Direct database deletion (SQL)
   - Benefit: ~10s cleanup → ~1s
   - Cost: Tight coupling to database schema, breaks if schema changes
   - Decision: API-level cleanup is more maintainable

4. **Response Caching**
   - Current: Every scenario reads the same reference data (category list, course types)
   - Potential: Cache responses for scenarios in same execution
   - Benefit: Fewer HTTP calls (~5% savings)
   - Cost: Cache invalidation complexity, minimal benefit
   - Decision: Not worth it

---

## 7. Debugging Guide

### 7.1 "Scenario Failed — What Now?"

**Step 1: Read the assertion error**
```
AssertionError: [the backend must persist the exact date of birth sent]
Expecting: 1999-05-14  (IST calendar date)
to be equal to: 1999-05-15  (IST calendar date)
at PersonalDetailsWorkflow.verifyUpdatePersisted (PersonalDetailsWorkflow.java:156)
```

**Step 2: Check the workflow code**
- Line 156 of PersonalDetailsWorkflow → `assertThat(persistedDob).isEqualTo(sentDob);`
- The backend persisted the wrong date

**Step 3: Check if it's a timezone issue**
- If assertion compares raw ISO strings: likely timezone bug
- If assertion uses DateUtils.toCalendarDate(): timezone bug ruled out, data corruption confirmed

**Step 4: Get the full HTTP response**
- Assertion includes `response.getBody().asString()`
- Look for unexpected fields, nulls, type mismatches

**Step 5: Check the HAR**
- Does the HAR show the backend ever returning this response shape?
- If not, backend behavior has changed

### 7.2 "All Attendance Scenarios Failed"

**Likely Cause:** Environment Setup chose a term that isn't active today.

**Fix Applied (Aug 3, 2026):**
BootstrapWorkflow now selects terms where `startDate <= today <= endDate` instead of selecting the newest term.

**Lesson:** Backend gates many operations on active academic term. If environment setup chooses wrong term, all downstream journeys fail.

### 7.3 "Test Passes Locally But Fails in CI"

**Likely Cause:** Environment differences (API URL, data state, timing).

**Debug Steps:**
1. **Verify API URL:** Check .env file used in CI vs local
2. **Check for Seeded Data:** If tests depend on pre-existing data, CI might not have it
3. **Check Timing:** If tests have implicit waits, CI infrastructure might be slower
4. **Check Auth:** If CI runs as different user, auth might fail

**This Framework's Advantage:** Tests don't depend on seeded data, so CI should behave identically to local.

---

## 8. Comparison with Other Frameworks

### 8.1 vs. Playwright/JavaScript UI Testing

| Aspect | This Framework (RestAssured) | Playwright (UI) |
|--------|-------|---------|
| **What It Tests** | API contracts + data persistence | UI rendering + interactions |
| **Setup Cost** | ~30s (create program + student) | ~5s (but browser overhead) |
| **Execution Time** | ~2s per scenario | ~20s per scenario |
| **Failure Debugging** | Request/response in logs | Screenshot + browser console |
| **Maintenance** | Low (API rarely changes) | High (UI changes break tests) |
| **Spec Compliance** | Verifies API behavior | Verifies UI displays API data |
| **Ideal For** | Backend contract testing | End-to-end user journey testing |

**How They Complement Each Other:**
- This framework: Proves the backend stores data correctly
- Playwright framework: Proves the UI displays that data to users correctly

---

## 9. Summary: Design Guiding Principles

1. **HAR-First:** All test data comes from captured HTTP, never invented
2. **Business-First:** Tests assert user outcomes, not HTTP status codes
3. **Workflow-Centric:** Business logic in reusable workflow classes, not scattered steps
4. **Verify Mutations:** Every POST/PUT/PATCH followed by GET to verify persistence
5. **Isolation-Based:** Each suite run creates its own data; no seeded data dependency
6. **Layered:** Clear separation between Gherkin, steps, workflows, clients, HTTP
7. **Traceable:** Every endpoint documented with HAR entry number
8. **Maintainable:** Patterns that scale from 10 to 100+ scenarios without refactoring

---

**End of Architecture Document**
