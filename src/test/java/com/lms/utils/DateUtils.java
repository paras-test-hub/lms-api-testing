package com.lms.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class DateUtils {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    private DateUtils() {}

    public static LocalDate toCalendarDate(Object rawValue) {
        if (rawValue == null) return null;
        String s = String.valueOf(rawValue);
        if (s.isEmpty() || "null".equals(s)) return null;
        try {
            return Instant.parse(s).atZone(DISPLAY_ZONE).toLocalDate();
        } catch (DateTimeParseException e) {
            return LocalDate.parse(s.substring(0, 10));
        }
    }
}
