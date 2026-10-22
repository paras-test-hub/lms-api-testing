package com.lms.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Defensive recursive search over a parsed JSON structure (nested Maps/Lists, e.g. from
 * RestAssured's {@code response.jsonPath().getMap("$")}), used when a response's exact
 * envelope shape is unverified but a specific object still needs to be located by one of
 * its fields - e.g. finding a just-created entity's id by searching for an object whose
 * "name" matches a known unique value, regardless of how deeply that object is nested in
 * the response. Prefer a precise JsonPath expression once the real shape is confirmed live;
 * this exists for the gap before that.
 */
public final class JsonSearch {

    private JsonSearch() {
    }

    /**
     * Recursively searches for a Map where {@code matchKey} equals {@code matchValue}, and
     * returns that Map's value for {@code idKey} (falling back to "_id" then "id" if
     * {@code idKey} isn't present). Returns null if no match is found anywhere.
     */
    public static Object findIdByField(Object node, String matchKey, Object matchValue, String idKey) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (matchValue.equals(map.get(matchKey))) {
                Object id = map.get(idKey);
                if (id == null) id = map.get("_id");
                if (id == null) id = map.get("id");
                if (id != null) return id;
            }
            for (Object value : map.values()) {
                Object found = findIdByField(value, matchKey, matchValue, idKey);
                if (found != null) return found;
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                Object found = findIdByField(item, matchKey, matchValue, idKey);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Recursively finds the first non-null value for {@code key} anywhere in the structure
     * (document order). Useful for "just grab any valid id" lookups, e.g. picking an
     * existing category id from a list response without caring which one.
     */
    public static Object findFirstValue(Object node, String key) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            Object direct = map.get(key);
            if (direct != null) return direct;
            for (Object value : map.values()) {
                Object found = findFirstValue(value, key);
                if (found != null) return found;
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                Object found = findFirstValue(item, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Recursively checks whether any Map in the structure has {@code key} equal to {@code value}. */
    public static boolean containsMatchingObject(Object node, String key, Object value) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (value.equals(map.get(key))) {
                return true;
            }
            for (Object v : map.values()) {
                if (containsMatchingObject(v, key, value)) return true;
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                if (containsMatchingObject(item, key, value)) return true;
            }
        }
        return false;
    }

    /**
     * Recursively collects every Map in the structure that contains ALL of {@code requiredKeys}
     * (e.g. "uuid" and "name", or "uuid" and "mode" for endpoints whose label field isn't
     * "name"), then returns one at random. Used for the "GET a reference-data list, pick any
     * valid entry" pattern shared by several lookups (course type/mode/class/department/tag)
     * where the exact envelope shape (a bare array vs Data vs Data.items) isn't worth hardcoding
     * a JsonPath expression for. Returns null if no matching Map is found anywhere.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> pickRandomObjectWithKeys(Object node, String... requiredKeys) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        collectObjectsWithKeys(node, requiredKeys, candidates);
        if (candidates.isEmpty()) return null;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    @SuppressWarnings("unchecked")
    private static void collectObjectsWithKeys(Object node, String[] requiredKeys, List<Map<String, Object>> out) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            boolean hasAll = true;
            for (String k : requiredKeys) {
                if (!map.containsKey(k)) {
                    hasAll = false;
                    break;
                }
            }
            if (hasAll) out.add(map);
            for (Object v : map.values()) {
                collectObjectsWithKeys(v, requiredKeys, out);
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                collectObjectsWithKeys(item, requiredKeys, out);
            }
        }
    }

    /**
     * Recursively searches for the first Map that contains a specific key-value pair and also
     * contains the uuid key, and returns that Map. Used for finding a specific reference item
     * by its label (e.g. find the courseType where name="core").
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> findObjectWithKeyValue(Object node, String labelKey, Object labelValue, String uuidKey) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            if (labelValue.equals(map.get(labelKey)) && map.containsKey(uuidKey)) {
                return map;
            }
            for (Object v : map.values()) {
                Map<String, Object> found = findObjectWithKeyValue(v, labelKey, labelValue, uuidKey);
                if (found != null) return found;
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                Map<String, Object> found = findObjectWithKeyValue(item, labelKey, labelValue, uuidKey);
                if (found != null) return found;
            }
        }
        return null;
    }
}
