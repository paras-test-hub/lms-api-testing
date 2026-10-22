Feature: Capstone Mini-Setup
  As the LMS platform
  I want a capstone section set up on the existing runtime environment
  So that capstone-specific behaviour is verified against the SAME runtime cohort, term, course and
    student the other journeys use, without provisioning a second environment

  # BUSINESS JOURNEY (organized by capability, not by actor): this feature describes ONE capstone
  # setup lifecycle run by the Admin against the SAME suite-scoped runtime environment
  # ExecutionContext holds. It runs in 10_journeys/, after 00_setup/EnvironmentSetup.feature has
  # provisioned + authenticated the environment and before 99_cleanup/Cleanup.feature tears it down.
  # It creates exactly ONE new Section and ONE new section-course mapping; it recreates no program,
  # cohort, term, course or student, and it does not depend on any other journey having run.
  #
  # FOUR DISTINCT ENTITIES: the single largest correctness risk here is conflating four things that
  # all read like "the course". This journey keeps them separate on purpose:
  #
  #   CourseMaster          - the organization-level course definition, provisioned once by
  #                           Environment Setup and REUSED as-is; no new one is created here.
  #   TermCourse            - a CourseMaster's runtime instance inside ONE section of one term. The
  #                           capstone section therefore yields its OWN capstone TermCourse, a
  #                           different entity from the runtime one the Assignment, Session and
  #                           Attendance journeys consume. Gamification is a TermCourse-level
  #                           attribute, so the two are configured independently.
  #   SectionCourse mapping - the join record binding one CourseMaster to one section within the
  #                           term; it is what the section-courses read returns, and the entry
  #                           matched by CourseMaster identity is what yields the TermCourse.
  #   Section               - the Capstone Outclass Section this journey creates, never the normal
  #                           runtime section Environment Setup provisioned.
  #
  # ISOLATION: the runtime section, its TermCourse and its section-course mapping are left untouched.
  # The regression evidence is that the Assignment, Session and Attendance journeys pass on the same
  # run as this one.
  #
  # THE COMPLETE JOURNEY: built one verified business step at a time and extended IN PLACE; the
  # capstone mini-setup is now feature-complete end to end:
  #
  #   Environment Setup (done in 00_setup)
  #        -> Create Capstone Outclass Section  (in the runtime program term, capstone flag set; the
  #                                              section name is backend-generated and read back from
  #                                              the create response, never composed locally)
  #        -> Assign the Runtime Student        (the SAME student Environment Setup created; no new
  #                                              student, and the runtime section's roster is left
  #                                              untouched - the capstone section is the only target)
  #        -> Verify the Capstone Roster        (a separate roster read is what proves the student is
  #                                              really a member; an accepted request is not evidence)
  #        -> Add the Existing Runtime Course   (the SAME CourseMaster Environment Setup created is
  #                                              attached to the capstone section, producing that
  #                                              section's OWN TermCourse; no new course is created and
  #                                              the runtime section's course wiring is left untouched)
  #        -> Verify the Capstone Course        (a separate section-courses read proves the attachment
  #                                              landed and is what resolves the capstone TermCourse -
  #                                              matched by CourseMaster identity, never by course name,
  #                                              and asserted to be a DIFFERENT instance from the
  #                                              runtime TermCourse; resolved once and reused after)
  #        -> Enable Gamification               (on the CAPSTONE course only - its current configuration
  #                                              is read back first and re-sent whole with gamification
  #                                              switched on, because the edit replaces the entire course
  #                                              configuration; the runtime course is never addressed)
  #        -> Verify Gamification               (confirmed TWICE, through two independent reads - the course's
  #                                              own record AND the section's course listing, which is how the
  #                                              rest of the platform sees it. They can disagree, and a failure
  #                                              names which read dissented; the course record read also
  #                                              re-confirms the whole chain - capstone section, runtime course,
  #                                              runtime term - so what is proven is that the CAPSTONE instance
  #                                              of the runtime course is gamified, not just "some course")
  #        -> S2: Configure the Capstone Course (a SECOND scenario, below: the gamified capstone course's
  #                                              metrics, metrics-builder grid, pinned dashboard metrics,
  #                                              analytics display, leaderboard formula, resources and
  #                                              rewards, then a read-back of the whole configuration)

  @journey @capstone @requires-credentials
  Scenario: Admin sets up a capstone section on the runtime environment
    Given the runtime environment is ready for the capstone journey
    When the admin creates a Capstone Outclass section
    When the runtime student is assigned to the Capstone section
    Then the Capstone section contains the runtime student
    When the existing runtime course is added to the Capstone section
    Then the Capstone section contains the runtime course
    When the admin enables gamification for the Capstone course
    Then the Capstone course is verified as gamified

  # S2 - CONFIGURE THE CAPSTONE COURSE. Deliberately a SEPARATE scenario, not extra steps on S1: it
  # CONSUMES the suite-scoped runtime state S1 stored (the capstone section and the capstone TermCourse
  # ExecutionContext carries) and recreates NOTHING - no section, no course attachment, no gamification
  # toggle. It therefore REQUIRES S1 to have run earlier in the same suite; run on its own, the readiness
  # guard fails naming the missing capstone state rather than provisioning it.
  #
  # TAGS: @capstone is retained so the existing tag-filtered run still selects this scenario alongside
  # S1; @configuration marks it as the S2 configuration stage so it can be selected on its own; and
  # @requires-credentials matches the sibling journeys in 10_journeys/.
  #
  # ONE AGGREGATE WRITE BEHIND SIX STEPS: metrics, the metrics builder, the pinned metrics, the analytics
  # display, the leaderboard and the resources are persisted by ONE aggregate configuration request -
  # whichever step runs first sends it, and each step then asserts its OWN slice of what was persisted,
  # so a failing step names the slice that did not land. Rewards are a genuinely separate API.
  @journey @capstone @configuration @requires-credentials
  Scenario: Admin configures the Capstone course
    Given the Capstone course is ready for configuration
    When the admin configures the Capstone metrics
    And the admin configures the Capstone metrics builder
    And the admin configures the Capstone pinned metrics
    And the admin configures the Capstone analytics display
    And the admin configures the Capstone leaderboard
    And the admin configures Capstone resources
    And the admin configures Capstone rewards
    Then the Capstone configuration should be persisted correctly

  # S3 - CREATE THE CAPSTONE TEAM. Like S2, deliberately a SEPARATE scenario rather than extra steps on
  # S1 or S2: it CONSUMES the suite-scoped state S1 and S2 already produced (the capstone section, the
  # capstone TermCourse, the capstone gamification configuration, the runtime student and the admin
  # session ExecutionContext carries) and creates exactly ONE new entity - the Capstone team. No section,
  # no course attachment, no gamification toggle and no configuration write happens here. It therefore
  # REQUIRES S1 and S2 to have run earlier in the same suite; run on its own, the readiness guard fails
  # naming the missing capstone state and the stage that produces it rather than provisioning it.
  #
  # TAGS: @capstone is retained so the existing tag-filtered run still selects this scenario alongside
  # S1 and S2; @team-management marks it as the S3 team-formation stage so it can be selected on its own;
  # and @requires-credentials matches the sibling journeys in 10_journeys/.
  #
  # THREE CALLS BEHIND SEVEN STEPS: the whole scenario is one admin POST (the team creation), one admin
  # GET (the team listing) and one student GET (the student's capstone view). The remaining steps assert
  # their OWN slice of that evidence without issuing further requests, so a failing step names the slice
  # that did not land. Membership is proven from BOTH sides - the admin listing and the student's own
  # session - because the two can disagree, and an accepted create request is never evidence on its own.
  #
  # CLEANUP: the created team is DELIBERATELY not registered for cleanup - no ResourceType value
  # represents a team, so teardown is left to the platform's section-scoped lifecycle when the capstone
  # section goes away. That is a documented, unverified gap rather than an oversight.
  @journey @capstone @team-management @requires-credentials
  Scenario: Admin creates a Capstone team and verifies the team configuration
    Given the Capstone course is configured and ready for team formation
    When the admin creates a Capstone team with the runtime student
    Then the Capstone team should be created successfully
    And the admin should see the runtime student as a member of the Capstone team
    And the student should see the Capstone team with the runtime student as a member
    And the Capstone team should inherit the configured Capstone metrics
    And the Capstone team should retain the configured Capstone configuration
