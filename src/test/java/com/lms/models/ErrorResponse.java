package com.lms.models;

import com.google.gson.annotations.SerializedName;

/** 400/401 error body: { code, message }. Verified against the live backend. */
public class ErrorResponse {

    @SerializedName("code")
    private Integer code;

    @SerializedName("message")
    private String message;

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
