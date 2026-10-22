# LMS API Testing - RestAssured + Cucumber

BDD-style API test automation framework for the LMS platform using **RestAssured** and **Cucumber**, supporting both **Student** and **Admin** roles with cross-functional workflows.

## Overview

This framework provides:
- **1,143 API endpoints** coverage (268 student endpoints, 875 admin endpoints)
- **BDD-driven tests** written in Gherkin (.feature files)
- **Modular client structure** - one client class per API section (Authentication, AcademicSummary, Programs, etc.)
- **Multi-environment support** - Dev, Staging, Prod, and 4 white-labeled brands
- **Cross-functional workflows** - Student + Admin operations in a single scenario
- **Comprehensive logging & reporting** - HTML reports, REST request/response logging

## Project Structure

```
lms-api-testing-restassured-cucumber/
├── pom.xml                                 # Maven configuration + dependencies
├── .env / .env.example                     # Environment variables & credentials
├── README.md                               # This file
│
├── src/test/java/com/lms/
│   ├── clients/                            # REST API clients
│   │   ├── AuthenticationClient.java       # Login, token refresh (student/admin)
│   │   ├── AcademicSummaryClient.java      # Academic data (student)
│   │   ├── ProgramClient.java              # Program management (admin)
│   │   └── ... (one per API section)
│   │
│   ├── steps/                              # Cucumber step definitions
│   │   ├── AuthenticationSteps.java        # Gherkin steps for login scenarios
│   │   ├── AcademicSteps.java              # Steps for academic operations
│   │   └── ... (one per section)
│   │
│   ├── hooks/                              # Cucumber Before/After hooks
│   │   ├── Hooks.java                      # Global setup/teardown
│   │   └── EnvironmentHook.java            # Environment initialization
│   │
│   ├── models/                             # Request/Response POJOs
│   │   ├── LoginRequest.java
│   │   ├── LoginResponse.java
│   │   ├── AuthenticatedUser.java
│   │   └── ... (by domain)
│   │
│   ├── config/                             # Configuration classes
│   │   ├── EnvironmentConfig.java          # Load URLs from environments_config.json
│   │   └── TestConfig.java                 # Global test settings
│   │
│   ├── context/                            # Shared test state
│   │   ├── ScenarioContext.java            # Thread-safe storage (tokens, user data)
│   │   ├── StudentSession.java             # Student auth session wrapper
│   │   └── AdminSession.java               # Admin auth session wrapper
│   │
│   └── utils/
│       ├── RestClient.java                 # RestAssured wrapper (HTTP methods + logging)
│       ├── DataFactory.java                # Test data generation (Faker)
│       └── Assertions.java                 # Custom assertion helpers
│
├── src/test/resources/
│   ├── features/                           # Cucumber .feature files (BDD scenarios)
│   │   ├── student/
│   │   │   ├── Authentication.feature      # Student login scenarios
│   │   │   ├── AcademicSummary.feature     # Academic data retrieval
│   │   │   ├── Courses.feature             # Course operations
│   │   │   └── ... (by section)
│   │   │
│   │   ├── admin/
│   │   │   ├── Authentication.feature      # Admin login scenarios
│   │   │   ├── Program.feature             # Program management
│   │   │   ├── Grading.feature             # Grading & report cards
│   │   │   └── ... (by section)
│   │   │
│   │   └── cross-functional/
│   │       ├── StudentAdminWorkflows.feature  # Multi-actor workflows
│   │       └── ... (complex scenarios)
│   │
│   ├── log4j2.xml                          # Log4j2 configuration
│   └── cucumber.properties                 # Cucumber runner settings
│
└── target/
    └── cucumber-reports/                   # HTML reports (generated after test run)
```

## Prerequisites

- **Java 11+** (JDK)
- **Maven 3.8+**
- **Git**
- **.env file** with test credentials (copy from `.env.example`)

## Setup

### 1. Clone/Navigate to the Project
```bash
cd "C:\Thanos\codex QA AI\lms-api-testing-restassured-cucumber"
```

### 2. Configure Environment Variables
```bash
# Copy the example file
cp .env.example .env

# Edit .env with your test credentials
# STUDENT_TEST_EMAIL=your_student@example.com
# STUDENT_TEST_PASSWORD=your_password
# ADMIN_TEST_EMAIL=your_admin@example.com
# ADMIN_TEST_PASSWORD=your_password
```

### 3. Install Dependencies
```bash
mvn clean install
```

## Running Tests

### Run All Tests
```bash
mvn test
```

### Run Specific Environment
```bash
# Dev (default)
mvn test -Pdev

# Staging
mvn test -Pstaging

# Production
mvn test -Pprod
```

### Run Specific Feature File
```bash
# Student authentication scenarios only
mvn test -Dgroups="student" -Dfeatures=src/test/resources/features/student/Authentication.feature
```

### Run with Tags
```bash
# Scenarios tagged @student
mvn test -Dgroups="@student"

# Scenarios tagged @smoke
mvn test -Dgroups="@smoke"

# Scenarios tagged @admin AND @high-priority
mvn test -Dgroups="@admin and @high-priority"
```

### View HTML Report
```bash
# After test run, open the HTML report
start target/cucumber-reports/index.html

# Or on macOS
open target/cucumber-reports/index.html
```

## Writing Tests (BDD Workflow)

### 1. Write Gherkin Scenario (.feature file)

**Example: `src/test/resources/features/student/Authentication.feature`**
```gherkin
Feature: Student Authentication
  As a student
  I want to log in with my credentials
  So that I can access my academic data

  @student @smoke
  Scenario: Student logs in successfully
    Given a student with valid credentials
    When the student submits the login request
    Then the student receives a 200 response
    And the response contains a valid JWT token
    And the response contains student profile data

  @student @negative
  Scenario: Student login fails with invalid credentials
    Given a student with email "student@example.com"
    And password "wrongpassword"
    When the student submits the login request
    Then the student receives a 401 response
    And the response contains error message "Incorrect email or password"
```

### 2. Implement Step Definitions

**Example: `src/test/java/com/lms/steps/AuthenticationSteps.java`**
```java
package com.lms.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import com.lms.context.ScenarioContext;
import com.lms.clients.AuthenticationClient;

public class AuthenticationSteps {
    private ScenarioContext context;
    private AuthenticationClient authClient;

    public AuthenticationSteps(ScenarioContext context) {
        this.context = context;
        this.authClient = new AuthenticationClient();
    }

    @Given("a student with valid credentials")
    public void studentWithValidCredentials() {
        String email = System.getenv("STUDENT_TEST_EMAIL");
        String password = System.getenv("STUDENT_TEST_PASSWORD");
        context.setStudentEmail(email);
        context.setStudentPassword(password);
    }

    @When("the student submits the login request")
    public void submitStudentLogin() {
        var response = authClient.studentLogin(
            context.getStudentEmail(),
            context.getStudentPassword()
        );
        context.setLastResponse(response);
    }

    @Then("the student receives a 200 response")
    public void verifySuccessResponse() {
        assert context.getLastResponse().statusCode() == 200;
    }

    // More steps...
}
```

## Key Components

### RestClient Wrapper
Central HTTP client wrapping RestAssured with common functionality:
- Auto-inject Bearer token from `ScenarioContext`
- Request/response logging
- Timeout handling
- Retry logic for flaky endpoints

**Usage:**
```java
RestClient client = new RestClient();
var response = client.get("/api/v1/auth/profile", "Bearer " + token);
```

### ScenarioContext
Thread-safe storage for per-scenario data:
- Auth tokens (student & admin)
- User IDs, UUIDs
- Session data
- Last API response

**Usage:**
```java
context.setStudentToken(token);
String token = context.getStudentToken();
context.clearSessionData(); // Reset between scenarios
```

### AuthenticationClient
Dedicated client for login operations:
- `studentLogin(email, password)` → LoginResponse
- `adminLogin(email, password)` → LoginResponse
- `refreshToken(refreshToken)` → TokenResponse
- Error handling for 401, 400 responses

## API Specifications

### Supported Endpoints

**Student API (268 endpoints across 26 modules):**
- Authentication, Academic Summary, Courses, Assignments, Doubts
- Club Management, Events, Announcements, Feed, Tickets, Proctoring

**Admin API (875 endpoints across 48 modules):**
- Authentication, Program Management, Grading, Attendance, Report Cards
- Placement Management, User Management, Analytics, Clubs, Events

### Authentication

All APIs use **JWT Bearer Token**:
- **Student Login**: `POST /api/v1/auth/studentLogin`
- **Admin Login**: `POST /api/v1/auth/login`
- **No reCAPTCHA** for admin (only for student, which we skip in tests)

### Multi-Environment Support

Automatic URL switching via `TEST_ENV` environment variable:
- `dev` → https://api-dev.mastersunion.in
- `prod` → https://api.mastersunion.in
- `staging` → https://api-dev.mastersunion.in
- `tetr`, `aimer`, `coachlms`, `isbf` → respective white-labeled URLs

## Development Workflow

### Phase-by-Phase Implementation

This framework is built incrementally:

1. **Phase 1**: Project structure + Maven setup ✅
2. **Phase 2**: Core infrastructure (RestClient, Config, ScenarioContext)
3. **Phase 3**: Authentication (student + admin login)
4. **Phase 4**: Student API operations (modules 1-5)
5. **Phase 5**: Admin API operations (modules 1-5)
6. **Phase 6**: Cross-functional workflows (multi-actor scenarios)
7. **Phase 7**: Advanced features (parameterization, performance, parallel execution)

### Adding a New API Section

1. **Create the .feature file** in `src/test/resources/features/{student|admin}/{Section}.feature`
2. **Create the Client** in `src/test/java/com/lms/clients/{Section}Client.java`
3. **Create Models** in `src/test/java/com/lms/models/` (Request, Response POJOs)
4. **Create Step Definitions** in `src/test/java/com/lms/steps/{Section}Steps.java`
5. **Run tests**: `mvn test -Dgroups="@{section}"`

## Troubleshooting

### Tests Fail with 401 Unauthorized
- Verify `STUDENT_TEST_EMAIL` and `STUDENT_TEST_PASSWORD` in `.env` are correct
- Verify `ADMIN_TEST_EMAIL` and `ADMIN_TEST_PASSWORD` in `.env` are correct
- Token may have expired (tests create new token per scenario)

### Tests Timeout
- Increase `REQUEST_TIMEOUT` in `.env` (default: 30000ms)
- Check API server connectivity

### Environment Variable Not Loading
- Ensure `.env` file exists in project root
- Restart IDE/terminal after editing `.env`

## References

- **Student OpenAPI Spec**: `C:\Thanos\codex QA AI\student_openapi.json`
- **Admin OpenAPI Spec**: `C:\Thanos\codex QA AI\admin_openapi.json`
- **Environment Config**: `C:\Thanos\codex QA AI\environments_config.json`
- **Swagger UI**: `C:\Thanos\codex QA AI\swagger-ui-app\`

## Next Steps

1. Copy `.env.example` to `.env` and fill in credentials
2. Run `mvn clean install` to verify setup
3. Move to **Phase 2** (Core Infrastructure)

---

**Model Recommendation for Phase 2**: Use **Sonnet** — architectural decisions on RestClient wrapper design, configuration management, and ScenarioContext implementation.
