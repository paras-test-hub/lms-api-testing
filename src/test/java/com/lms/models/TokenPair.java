package com.lms.models;

import com.google.gson.annotations.SerializedName;

public class TokenPair {

    @SerializedName("access")
    private TokenDetail access;

    @SerializedName("refresh")
    private TokenDetail refresh;

    public TokenDetail getAccess() {
        return access;
    }

    public TokenDetail getRefresh() {
        return refresh;
    }
}
