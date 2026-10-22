# Cucumber Hooks

This directory contains Cucumber Before/After hooks for test lifecycle management.

## Key Classes

### Hooks.java
Global setup and teardown for all scenarios.

**Responsibilities:**
- Initialize RestAssured configuration (base URL, timeouts, headers)
- Load environment variables from `.env` file
- Initialize logging
- Run before each scenario
- Clean up and reset state after each scenario

**Usage (Gherkin):**
```gherkin
Scenario: Student logs in
  # Before hook runs automatically
  Given a student with valid credentials
  When the student submits the login request
  Then the student receives a 200 response
  # After hook runs automatically
```

**Typical Implementation:**
```java
@Before
public void setUp() {
    // Load environment variables
    new Dotenv().load();
    
    // Initialize RestAssured
    RestAssured.baseURI = EnvironmentConfig.getApiBaseUrl();
    RestAssured.requestSpecification = new RequestSpecBuilder()
        .setContentType(ContentType.JSON)
        .setAccept(ContentType.JSON)
        .build();
    
    // Initialize logging
    Logger.info("Starting scenario: " + context.getScenarioName());
}

@After
public void tearDown() {
    // Clean up scenario data
    context.clearSessionData();
    
    // Log scenario summary
    Logger.info("Scenario completed");
}
```

### EnvironmentHook.java (Optional)
Environment-specific setup.

**Responsibilities:**
- Verify environment is reachable (ping API health endpoint)
- Set environment-specific headers or data
- Skip tests for unavailable environments

**Usage:**
```java
@Before(value = "@production")
public void verifyProductionEnvironment() {
    // Only runs for scenarios tagged with @production
    if (!EnvironmentConfig.isProduction()) {
        throw new SkipException("Not running against production");
    }
}
```

## Hook Ordering

Hooks execute in this order:
1. Global `@Before` hooks
2. Environment-specific `@Before` hooks (if tagged)
3. Test scenario
4. Environment-specific `@After` hooks
5. Global `@After` hooks

## Conditional Hooks

Use Cucumber tags to run hooks conditionally:

```java
@Before("@student")
public void setupStudentSession() {
    // Only for scenarios tagged with @student
}

@Before("@admin")
public void setupAdminSession() {
    // Only for scenarios tagged with @admin
}

@Before("@smoke or @regression")
public void setupCommonData() {
    // For scenarios tagged with either @smoke OR @regression
}
```

These will be implemented in Phase 2 (Core Infrastructure).
