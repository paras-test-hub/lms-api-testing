Feature: Admin Program
  As a admin
  I want to use the program operations
  So that I can [describe the business goal once a concrete flow is provided]

  # Tag "program" - 297 endpoint(s) defined in admin_openapi.json. This tag is far too broad
  # to cover as one flow - it spans program masters, cohorts/batches, terms, sections,
  # attendance, NPS, placement, quizzes, proctoring, zoom integration, and more.
  #
  # The "Program Master CRUD" sub-scope (5 endpoints: list/create/get/update/delete a
  # Program Master) has been implemented separately in ProgramMasterCrud.feature - see that
  # file, not this one, for those scenarios. Everything else under this tag remains
  # unverified against the live backend (the OpenAPI spec's response schemas are unverified
  # placeholders). Live-probe against api-dev.mastersunion.in before writing scenarios for
  # any other sub-scope here, the same way ProgramMasterCrud.feature did.
  #
  # Scenarios pending for the rest of this tag - add once a concrete flow is described.

