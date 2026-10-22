package com.lms.models;

import com.google.gson.annotations.SerializedName;

/**
 * POST body for both login endpoints. Verified against the live backend
 * (see lms-api-testing/projects/api/clients/auth.js):
 *   Student: POST /api/v1/auth/studentLogin  { officialEmail, password, captcha }
 *   Admin:   POST /api/v1/auth/login         { officialEmail, password }  (no captcha)
 *
 * Gson omits null fields by default, so {@link #forAdmin} (captcha left null) serializes
 * without a "captcha" key at all, matching the admin endpoint's contract.
 */
public class LoginRequest {

    @SerializedName("officialEmail")
    private String officialEmail;

    @SerializedName("password")
    private String password;

    @SerializedName("captcha")
    private String captcha;

    public LoginRequest(String officialEmail, String password, String captcha) {
        this.officialEmail = officialEmail;
        this.password = password;
        this.captcha = captcha;
    }

    public static LoginRequest forAdmin(String officialEmail, String password) {
        return new LoginRequest(officialEmail, password, null);
    }

    public String getOfficialEmail() {
        return officialEmail;
    }

    public String getPassword() {
        return password;
    }

    public String getCaptcha() {
        return captcha;
    }
}
