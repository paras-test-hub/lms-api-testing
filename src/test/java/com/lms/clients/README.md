# API Clients

This directory contains REST API client classes, one per API section.

## Client Naming Convention

- `AuthenticationClient.java` - Login, logout, token refresh
- `{Section}Client.java` - One client per OpenAPI tag/section

## Example Sections

**Student API:**
- `AcademicSummaryClient.java`
- `AssignmentClient.java`
- `CourseClient.java`
- `DoubtClient.java`
- `ClubClient.java`
- `EventClient.java`
- `AnnouncementClient.java`

**Admin API:**
- `ProgramClient.java`
- `GradingClient.java`
- `AttendanceClient.java`
- `ReportCardClient.java`
- `ClubManagementClient.java`
- `EventManagementClient.java`

## Implementation Pattern

Each client should:
1. Extend or wrap `RestClient` for HTTP operations
2. Define methods for each endpoint (GET, POST, PUT, DELETE)
3. Use models (POJOs) for request/response serialization
4. Handle error responses and exceptions
5. Return strongly-typed response objects

## Example

```java
public class AuthenticationClient {
    private RestClient restClient;

    public AuthenticationClient() {
        this.restClient = new RestClient();
    }

    public LoginResponse studentLogin(String email, String password) {
        LoginRequest request = new LoginRequest(email, password, "");
        return restClient.post(
            "/api/v1/auth/studentLogin",
            request,
            LoginResponse.class
        );
    }

    public LoginResponse adminLogin(String email, String password) {
        LoginRequest request = new LoginRequest(email, password);
        return restClient.post(
            "/api/v1/auth/login",
            request,
            LoginResponse.class
        );
    }
}
```

These will be implemented in Phase 3 (Authentication) and beyond.
