# Request & Response Models (POJOs)

This directory contains Java Plain Old Java Objects (POJOs) representing API request and response payloads.

## Naming Convention

- `{Entity}Request.java` - Request payload (e.g., `LoginRequest.java`)
- `{Entity}Response.java` - Response payload (e.g., `LoginResponse.java`)
- `{Entity}.java` - Shared data models (e.g., `AuthenticatedUser.java`)

## Key Models to Implement

### Authentication
- `LoginRequest` - Email, password, (optional) captcha
- `LoginResponse` - Token data, user profile, program info
- `TokenResponse` - Access token, refresh token, expiry
- `AuthenticatedUser` - User ID, name, email, role

### Academic (Student)
- `AcademicSummary` - GPA, grades, attendance stats
- `Transcript` - Term-wise grades and performance
- `CourseProgress` - Course enrollment, progress metrics

### Programs & Grading (Admin)
- `Program` - Program details, term info, enrollment capacity
- `Grade` - Grade value, scale, student ID
- `ReportCard` - Student report card with grades

## Implementation Pattern

Use **Lombok** to reduce boilerplate:

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    @SerializedName("officialEmail")
    private String officialEmail;
    
    @SerializedName("password")
    private String password;
    
    @SerializedName("captcha")
    private String captcha;
}

@Data
@NoArgsConstructor
public class LoginResponse {
    @SerializedName("IsSuccess")
    private Boolean isSuccess;
    
    @SerializedName("Data")
    private LoginData data;
    
    @SerializedName("message")
    private String message;
}

@Data
@NoArgsConstructor
public class LoginData {
    @SerializedName("user")
    private AuthenticatedUser user;
    
    @SerializedName("token")
    private TokenInfo token;
    
    @SerializedName("program")
    private ProgramInfo program;
}
```

**Benefits:**
- `@Data` - Auto-generates getters, setters, equals(), hashCode(), toString()
- `@NoArgsConstructor` - Default constructor (needed for Gson deserialization)
- `@AllArgsConstructor` - Constructor with all fields
- `@SerializedName` - Maps JSON keys to Java fields (Gson annotation)

These will be created as needed in Phase 3 and beyond.
