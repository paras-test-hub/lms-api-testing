# Utility Classes

This directory contains helper and utility classes for common operations.

## Key Classes

### RestClient.java
Central HTTP client wrapping RestAssured with common functionality.

**Responsibilities:**
- Wrap RestAssured for GET, POST, PUT, DELETE, PATCH operations
- Auto-inject Bearer token from `ScenarioContext`
- Log all requests and responses
- Handle timeouts and retries
- Deserialize responses to POJO models using Gson

**Usage:**
```java
RestClient client = new RestClient();

// GET request
Response response = client.get("/api/v1/auth/profile");

// POST request with body
LoginRequest request = new LoginRequest("email", "password", "");
Response response = client.post("/api/v1/auth/studentLogin", request);

// With authentication token
Response response = client.get("/api/v1/program", token);

// Deserialize to POJO
LoginResponse data = client.post("/api/v1/auth/studentLogin", request, LoginResponse.class);
```

### DataFactory.java
Test data generation using Faker library.

**Responsibilities:**
- Generate random but realistic test data
- Create test users, emails, names
- Generate course names, program names, etc.
- Reuse data patterns across tests

**Usage:**
```java
DataFactory factory = new DataFactory();
String email = factory.generateStudentEmail();
String password = factory.generatePassword();
String courseName = factory.generateCourseName();
```

### Assertions.java
Custom assertion helpers for common validations.

**Responsibilities:**
- Assert HTTP status codes (200, 201, 400, 401, etc.)
- Assert response contains expected fields
- Assert token format and validity
- Assert error messages

**Usage:**
```java
Assertions.assertStatusCode(response, 200);
Assertions.assertResponseContainsField(response, "token");
Assertions.assertValidJWT(token);
```

## Logging Strategy

RestAssured with Request/Response Logging:

```java
// In RestClient initialization
RestAssured.filters(new RequestLoggingFilter());
RestAssured.filters(new ResponseLoggingFilter());

// Or per-request
given()
    .log().all()  // Log request
    .when()
    .get("/api/v1/profile")
    .then()
    .log().all()  // Log response
    .statusCode(200);
```

Logs go to:
- Console (real-time output)
- `logs/test-execution.log` (all logs)
- `logs/errors.log` (errors only)
- `logs/rest-requests.log` (REST requests/responses)

These will be implemented in Phase 2 (Core Infrastructure).
