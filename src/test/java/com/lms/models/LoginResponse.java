package com.lms.models;

import com.google.gson.annotations.SerializedName;

/**
 * 200 response body for both studentLogin and login (admin). Verified shape:
 * { IsSuccess: true, Data: { user, token: { access, refresh }, program, userRole }, message }
 */
public class LoginResponse {

    @SerializedName("IsSuccess")
    private Boolean isSuccess;

    @SerializedName("Data")
    private LoginData data;

    @SerializedName("message")
    private String message;

    public Boolean getIsSuccess() {
        return isSuccess;
    }

    public LoginData getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}
