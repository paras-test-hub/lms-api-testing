package com.lms.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Minimal JWT payload reader - decodes the standard registered "exp" claim (epoch seconds)
 * without verifying the signature, since this is only used to decide whether a CACHED token
 * is still worth reusing, not to authorize anything. The token's own "exp" claim is trusted
 * over the login response's separate "expires" string field, whose format is unverified
 * (see TokenDetail's Javadoc) - the JWT payload format is standardized and self-describing.
 */
public final class JwtUtils {

    private JwtUtils() {
    }

    /**
     * Returns the token's "exp" claim as epoch seconds, or null if the token isn't a
     * well-formed 3-part JWT or has no numeric "exp" claim (callers should treat null as
     * "assume expired" and force a fresh login rather than fail).
     */
    public static Long extractExpiryEpochSeconds(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64Url(parts[1]));
            JsonObject payload = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!payload.has("exp")) return null;
            return payload.get("exp").getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** java.util.Base64's URL decoder requires proper padding; JWT segments often omit it. */
    private static String padBase64Url(String segment) {
        int remainder = segment.length() % 4;
        if (remainder == 0) return segment;
        return segment + "====".substring(remainder);
    }
}
