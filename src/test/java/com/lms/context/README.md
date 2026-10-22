# Scenario Context & Session Management

This directory contains classes for managing per-scenario and per-session data.

## Key Classes

### ScenarioContext.java
Thread-safe storage for test execution data shared across steps within a scenario.

**Responsibilities:**
- Store authentication tokens (student and admin)
- Store user IDs, UUIDs, and session data
- Store last API response for assertions
- Clear data between scenarios

**Usage:**
```java
context.setStudentToken(token);
String token = context.getStudentToken();
context.setLastResponse(response);
context.clearSessionData();
```

**Thread Safety:**
Use `ThreadLocal` to ensure each test thread has its own context:

```java
private static ThreadLocal<Map<String, Object>> contextData = ThreadLocal.withInitial(HashMap::new);
```

### StudentSession.java
Wrapper for student authentication session.

**Responsibilities:**
- Encapsulate student login process
- Store student token and profile data
- Provide methods for student-specific operations

**Usage:**
```java
StudentSession session = new StudentSession(email, password);
session.login();
String token = session.getToken();
String userId = session.getUserId();
```

### AdminSession.java
Wrapper for admin authentication session.

**Responsibilities:**
- Encapsulate admin login process
- Store admin token and profile data
- Provide methods for admin-specific operations

**Usage:**
```java
AdminSession session = new AdminSession(email, password);
session.login();
String token = session.getToken();
String adminId = session.getAdminId();
```

## Implementation Pattern

```java
@Data
public class ScenarioContext {
    private static ThreadLocal<Map<String, Object>> contextData = 
        ThreadLocal.withInitial(HashMap::new);

    // Student session data
    public void setStudentEmail(String email) {
        contextData.get().put("studentEmail", email);
    }

    public String getStudentEmail() {
        return (String) contextData.get().get("studentEmail");
    }

    public void setStudentToken(String token) {
        contextData.get().put("studentToken", token);
    }

    public String getStudentToken() {
        return (String) contextData.get().get("studentToken");
    }

    // Admin session data
    public void setAdminToken(String token) {
        contextData.get().put("adminToken", token);
    }

    public String getAdminToken() {
        return (String) contextData.get().get("adminToken");
    }

    // Last response (for assertions)
    public void setLastResponse(Response response) {
        contextData.get().put("lastResponse", response);
    }

    public Response getLastResponse() {
        return (Response) contextData.get().get("lastResponse");
    }

    // Clear data between scenarios
    public void clearSessionData() {
        contextData.get().clear();
    }
}
```

These will be implemented in Phase 2 (Core Infrastructure).
