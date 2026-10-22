package com.lms.models;

import com.google.gson.annotations.SerializedName;

/**
 * A single JWT + its expiry, as returned nested under token.access / token.refresh.
 * Verified shape (see lms-admin-frontend and lms-student-frontend, e.g.
 * ViewSubmission.js: `data?.token?.access?.token`).
 */
public class TokenDetail {

    @SerializedName("token")
    private String token;

    @SerializedName("expires")
    private String expires;

    public String getToken() {
        return token;
    }

    public String getExpires() {
        return expires;
    }
}
