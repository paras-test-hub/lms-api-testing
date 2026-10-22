Feature: Admin Program Master CRUD
  As an admin
  I want to create, view, update, and delete Program Masters
  So that I can set up and manage academic programs for my organization

  # 5 endpoints, a deliberately scoped-down subset of admin_openapi.json's "program" tag
  # (297 endpoints total - covering the whole tag as one flow would be unreasonable; see
  # Program.feature for that note). Paths and REQUEST shapes verified directly from
  # lms-admin-frontend/src/redux/program/programSlice.js - NOT from the OpenAPI spec, whose
  # documented request body (fields wrapped under an "obj" key) does not match the real
  # frontend (fields spread at the top level: { ...fields, createdBy/updatedBy }):
  #   GET    /api/v1/organization/{orgId}/programMasterMain                  (list)
  #   POST   /api/v1/organization/{orgId}/programMaster                     (create)
  #   GET    /api/v1/organization/{orgId}/programMaster/{programMasterId}   (get single)
  #   PATCH  /api/v1/organization/{orgId}/programMaster/{programMasterId}   (update)
  #   DELETE /api/v1/organization/{orgId}/programMaster/{programMasterId}   (delete)
  #
  # orgId is per-admin-account (Data.user.Organization.uuid from login), not a fixed value.
  #
  # Full lifecycle VERIFIED LIVE end-to-end 2026-07-13 (real Automation Admin creds). Real
  # findings that corrected earlier guesses:
  #   - LIST 500s with NO query params at all (spec marks them all optional - likely
  #     parseInt(undefined) crashing server-side); must send searchParam/pageSize/pageNo/filter.
  #   - LIST's real shape is { IsSuccess, Data: [ {uuid, name, ...}, ... ] } - a flat array
  #     directly under "Data", NOT the spec's nested data.Data.programMasters[].
  #   - The identifier field on these objects is "uuid", not "_id"/"id" (same convention as
  #     the category list).
  #   - CREATE returns 201 (not 200 as originally assumed - the generic "indicates success"
  #     2xx check handled this correctly without needing a fix).
  #   - UPDATE (PATCH) 500s on a partial payload ({name, updatedBy} only) - this endpoint has
  #     no real partial-merge support server-side; it needs the full field set resent (same
  #     shape as create) with just the changed field(s) overridden.
  #   - DELETE succeeds (200) with this admin account - contradicts an earlier note (from a
  #     different admin account/session) that assumed delete was blocked (403) for this class
  #     of account. This account has full create+delete rights on Program Master.

  @admin @negative @smoke
  Scenario: Listing program masters without a bearer token is rejected
    When the admin lists program masters without authentication
    Then the response status code is 401

  @admin @negative
  Scenario: Creating a program master without a bearer token is rejected
    When the admin creates a program master without authentication
    Then the response status code is 401

  @admin @negative
  Scenario: Retrieving a program master without a bearer token is rejected
    When the admin retrieves a program master without authentication
    Then the response status code is 401

  @admin @negative
  Scenario: Updating a program master without a bearer token is rejected
    When the admin updates a program master without authentication
    Then the response status code is 401

  @admin @negative
  Scenario: Deleting a program master without a bearer token is rejected
    When the admin deletes a program master without authentication
    Then the response status code is 401

  @admin @smoke @requires-credentials
  Scenario: Admin creates, retrieves, updates, and deletes a Program Master
    Given the admin logs in with valid credentials
    And a valid category id is available
    When the admin creates a program master with a unique name based on "QA Automation Program"
    Then the response status code indicates success

    When the created program master id is looked up from the list
    And the admin retrieves the program master by id
    Then the response status code is 200
    And the response contains the created program master's name

    When the admin updates the program master name to "QA Automation Program Updated"
    Then the response status code indicates success

    When the admin deletes the program master
    Then the response status code indicates success
