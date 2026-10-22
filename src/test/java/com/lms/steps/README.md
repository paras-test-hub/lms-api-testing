# Cucumber Step Definitions

This directory contains Cucumber step definition classes that implement Gherkin steps.

## Step Definition Naming Convention

- `AuthenticationSteps.java` - Steps for login/logout/token scenarios
- `{Section}Steps.java` - Steps for each API section

## Example Sections

**Student API:**
- `AcademicSteps.java`
- `AssignmentSteps.java`
- `CourseSteps.java`
- `DoubtSteps.java`
- `ClubSteps.java`
- `EventSteps.java`
- `AnnouncementSteps.java`

**Admin API:**
- `ProgramSteps.java`
- `GradingSteps.java`
- `AttendanceSteps.java`
- `ReportCardSteps.java`
- `ClubManagementSteps.java`
- `EventManagementSteps.java`

## Implementation Pattern

Each step class should:
1. Accept `ScenarioContext` via constructor injection (Cucumber PicoContainer)
2. Define methods annotated with `@Given`, `@When`, `@Then`
3. Use the corresponding client class for API calls
4. Store results in `ScenarioContext` for use in subsequent steps
5. Perform assertions to validate API responses

## Example

```java
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
        assert context.getLastResponse().getStatusCode() == 200;
    }
}
```

These will be implemented in Phase 3 and beyond.
