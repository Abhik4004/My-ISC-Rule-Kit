package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;

public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonNode toNode(Object plain) { return MAPPER.valueToTree(plain); }

    public static String compact(Object plain) {
        try { return MAPPER.writeValueAsString(plain); } catch (Exception e) { return String.valueOf(plain); }
    }

    public static String pretty(Object plain) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plain); } catch (Exception e) { return String.valueOf(plain); }
    }

    /** Deep equality; numbers compare by value (1 equals 1.0), object key order is irrelevant. */
    public static boolean equal(JsonNode e, JsonNode a) {
        if (e == null || a == null) return e == a;
        if (e.isNumber() && a.isNumber()) return e.decimalValue().compareTo(a.decimalValue()) == 0;
        if (e.isObject() && a.isObject()) {
            if (e.size() != a.size()) return false;
            Iterator<Map.Entry<String, JsonNode>> it = e.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!a.has(f.getKey()) || !equal(f.getValue(), a.get(f.getKey()))) return false;
            }
            return true;
        }
        if (e.isArray() && a.isArray()) {
            if (e.size() != a.size()) return false;
            for (int i = 0; i < e.size(); i++) if (!equal(e.get(i), a.get(i))) return false;
            return true;
        }
        return e.equals(a);
    }

    /** Every field in {@code expected} must match in {@code actual}; extra fields in actual are ignored. */
    public static boolean subset(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isObject() && actual.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = expected.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!actual.has(f.getKey()) || !subset(f.getValue(), actual.get(f.getKey()))) return false;
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int i = 0; i < expected.size(); i++) if (!subset(expected.get(i), actual.get(i))) return false;
            return true;
        }
        return equal(expected, actual);
    }
}
