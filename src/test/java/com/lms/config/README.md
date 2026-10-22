# Configuration Classes

This directory contains configuration classes for loading and managing test settings.

## Key Classes

### EnvironmentConfig.java
Loads API base URLs from environment variables based on the `TEST_ENV` setting.

**Responsibilities:**
- Read `TEST_ENV` from `.env` file
- Return the corresponding API base URL
- Support all 7 environments (dev, prod, staging, tetr, aimer, coachlms, isbf)

**Usage:**
```java
String baseUrl = EnvironmentConfig.getApiBaseUrl(); // Returns dev URL by default
```

### TestConfig.java
Global test configuration and constants.

**Responsibilities:**
- Define request/connection timeouts
- Store base paths for API endpoints
- Feature flags (e.g., ENABLE_RECAPTCHA_VERIFICATION)
- Logging levels

**Usage:**
```java
int timeout = TestConfig.getRequestTimeout(); // 30000 ms
String apiV1 = TestConfig.getApiV1Path(); // "/api/v1"
```

## Environment Switching

The framework supports multi-environment testing via Maven profiles:

```bash
mvn test -Pdev      # Development (default)
mvn test -Pstaging  # Staging
mvn test -Pprod     # Production
```

Also via environment variables:
```bash
export TEST_ENV=prod
mvn test
```

## Implementation Strategy

Use dependency injection to provide configuration to clients and steps:

```java
// In RestClient or clients
String baseUrl = EnvironmentConfig.getApiBaseUrl();
int timeout = TestConfig.getRequestTimeout();

// RestAssured setup
RestAssured.baseURI = baseUrl;
RestAssured.requestSpecification = new RequestSpecBuilder()
    .setContentType(ContentType.JSON)
    .setAccept(ContentType.JSON)
    .build()
    .config(RestAssured.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", timeout)
            .setParam("http.socket.timeout", timeout)));
```

These will be implemented in Phase 2 (Core Infrastructure).
