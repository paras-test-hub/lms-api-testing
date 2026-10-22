package com.lms.steps.workflows;

import com.lms.context.ScenarioContext;
import com.lms.workflows.StudentLoginWorkflow;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for student authentication - the FINAL step of Environment Setup
 * (features/cross-functional/EnvironmentSetup.feature), not a business workflow. Thin by design:
 * all logic lives in {@link StudentLoginWorkflow}. It logs in the Student that Environment Setup
 * already created and assigned, using that student's OWN dynamically-generated credentials, and
 * stores the session in ScenarioContext. Only after this does the environment become "Ready".
 * Every later Student business workflow (Dashboard, Assignments, Attendance, Quizzes,
 * Announcements, Grades, ...) consumes that stored session - none of them log in themselves.
 */
public class StudentLoginWorkflowSteps {

    private final ScenarioContext context;
    private final StudentLoginWorkflow studentLoginWorkflow;

    public StudentLoginWorkflowSteps(ScenarioContext context) {
        this.context = context;
        this.studentLoginWorkflow = new StudentLoginWorkflow();
    }

    @Given("the student logs in for the isolated environment")
    public void theStudentLogsInForTheIsolatedEnvironment() {
        studentLoginWorkflow.loginStudent(context);
    }

    @Then("the isolated environment has an authenticated student session")
    public void theIsolatedEnvironmentHasAnAuthenticatedStudentSession() {
        assertThat(context.getStudentToken()).isNotBlank();
        assertThat(context.getStudentUserId()).isNotBlank();
    }
}
