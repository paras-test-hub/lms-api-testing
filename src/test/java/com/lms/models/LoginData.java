package com.lms.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/**
 * Payload under the top-level "Data" key. "user"/"program" are kept as loose maps (not
 * fully-typed POJOs) since not every field has been enumerated against the live backend yet -
 * mirrors the z.record(z.unknown()) looseness in the existing Playwright suite's
 * auth.schema.js. Tighten into real POJOs once a concrete flow needs specific fields.
 *
 * "userRole" is verified LIVE (not a placeholder guess) to be a flat array of RBAC policy
 * objects ({@code { uuid, policyName, slug, id, description }}), NOT an object - confirmed
 * by a real Gson deserialization failure ("Expected BEGIN_ARRAY but was BEGIN_OBJECT") before
 * this fix. The admin's actual role NAME lives elsewhere: {@code Data.user.UserRoles[0].Role.roleName}
 * (e.g. "Manager"), not on this field at all.
 */
public class LoginData {

    @SerializedName("user")
    private Map<String, Object> user;

    @SerializedName("token")
    private TokenPair token;

    @SerializedName("program")
    private Map<String, Object> program;

    @SerializedName("userRole")
    private List<Map<String, Object>> userRole;

    public Map<String, Object> getUser() {
        return user;
    }

    public TokenPair getToken() {
        return token;
    }

    public Map<String, Object> getProgram() {
        return program;
    }

    /** Flat list of RBAC policy objects (e.g. {uuid, policyName, slug, id, description}), not a role object. */
    public List<Map<String, Object>> getUserRole() {
        return userRole;
    }
}
