package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Human-readable differences between JSON values, used by the text report. */
final class Diff {
    private Diff() {}

    /** Where {@code actual} fails to contain {@code expected} (same rules as {@link Json#subset}). One line per mismatch. */
    static List<String> mismatches(JsonNode expected, JsonNode actual, String path) {
        List<String> out = new ArrayList<>();
        mismatch(expected, actual, path, out);
        return out;
    }

    private static void mismatch(JsonNode e, JsonNode a, String path, List<String> out) {
        if (e == null) return;
        if (a == null || a.isMissingNode()) { out.add(path + ": missing (expected " + show(e, false) + ")"); return; }
        if (e.isObject() && a.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = e.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                mismatch(f.getValue(), a.get(f.getKey()), join(path, f.getKey()), out);
            }
            return;
        }
        if (e.isArray() && a.isArray()) {
            if (e.size() != a.size()) {
                out.add(path + ": expected " + items(e.size()) + ", got " + items(a.size()));
                for (JsonNode x : e) out.add("    expected  " + show(x, false));
                for (JsonNode x : a) out.add("    got       " + show(x, false));
                return;
            }
            for (int i = 0; i < e.size(); i++) mismatch(e.get(i), a.get(i), path + "[" + i + "]", out);
            return;
        }
        if (!Json.subset(e, a)) out.add(path + ": expected " + show(e, false) + ", got " + show(a, false));
    }

    /** What changed from {@code before} to {@code after}: "+ added", "- removed", "path: old -> new". */
    static List<String> changes(JsonNode before, JsonNode after, String path, boolean verbose) {
        List<String> out = new ArrayList<>();
        change(before, after, path, verbose, out);
        return out;
    }

    private static void change(JsonNode b, JsonNode a, String path, boolean verbose, List<String> out) {
        boolean bMissing = b == null || b.isMissingNode() || b.isNull();
        boolean aMissing = a == null || a.isMissingNode() || a.isNull();
        if (bMissing && aMissing) return;
        if (bMissing) {
            if (a.isObject() && a.size() > 3 && !verbose) out.add("+ " + path + "  (" + a.size() + " values set; --verbose shows them)");
            else if (a.isObject() && a.size() > 3) a.fields().forEachRemaining(f -> out.add("+ " + path + "." + f.getKey() + " = " + show(f.getValue(), true)));
            else if (a.isArray() && a.size() == 0) return;
            else out.add("+ " + path + " = " + show(a, verbose));
            return;
        }
        if (aMissing) { out.add("- " + path + "  (was " + show(b, verbose) + ")"); return; }
        if (b.isObject() && a.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            b.fieldNames().forEachRemaining(keys::add);
            a.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) change(b.get(k), a.get(k), join(path, k), verbose, out);
            return;
        }
        if (b.isArray() && a.isArray()) {
            if (allObjects(b) && allObjects(a)) {
                if (b.size() == a.size() && sameShape(b, a)) { // same items in place: show what changed inside each
                    for (int i = 0; i < b.size(); i++) change(b.get(i), a.get(i), path + "[" + i + "]", verbose, out);
                    return;
                }
                // otherwise compare as a bag: show what was added and removed
                List<JsonNode> left = new ArrayList<>();
                b.forEach(left::add);
                List<JsonNode> added = new ArrayList<>();
                for (JsonNode x : a) {
                    int at = indexOf(left, x);
                    if (at >= 0) left.remove(at); else added.add(x);
                }
                for (JsonNode x : left) out.add("- " + path + ": " + show(x, verbose));
                for (JsonNode x : added) out.add("+ " + path + ": " + show(x, verbose));
                return;
            }
            if (!Json.equal(b, a)) out.add("  " + path + ": " + show(b, verbose) + "  ->  " + show(a, verbose));
            return;
        }
        if (!Json.equal(b, a)) out.add("  " + path + ": " + show(b, verbose) + "  ->  " + show(a, verbose));
    }

    /** Short display of a value. Attribute requests read as {@code name Op value}; passwords are masked. */
    static String show(JsonNode n, boolean verbose) {
        if (n == null || n.isNull() || n.isMissingNode()) return "null";
        if (n.isTextual()) return n.asText().isEmpty() ? "\"\"" : "\"" + n.asText() + "\"";
        if (n.isObject() && n.has("name") && (n.has("operation") || n.has("value")) && n.size() <= 3) {
            String name = n.get("name").asText();
            String op = n.has("operation") ? " " + n.get("operation").asText() : "";
            String value = !n.has("value") ? "" : name.toLowerCase().contains("password") ? " ********" : " " + show(n.get("value"), verbose);
            return name + op + value;
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode x : n) parts.add(show(x, verbose));
            return "[" + String.join(", ", parts) + "]";
        }
        String s = Json.compact(n);
        return verbose || s.length() <= 300 ? s : s.substring(0, 297) + "...";
    }

    private static String join(String path, String key) { return path.isEmpty() ? key : path + "." + key; }

    private static String items(int n) { return n + (n == 1 ? " item" : " items"); }

    private static boolean allObjects(JsonNode arr) {
        for (JsonNode x : arr) if (!x.isObject()) return false;
        return true;
    }

    /** Arrays whose elements line up by identity (e.g. account requests): compare position by position instead. */
    private static boolean sameShape(JsonNode b, JsonNode a) {
        for (int i = 0; i < b.size(); i++) {
            JsonNode x = b.get(i), y = a.get(i);
            for (String k : new String[] {"nativeIdentity", "applicationName", "name"})
                if (x.has(k) && y.has(k)) { if (!x.get(k).equals(y.get(k))) return false; else break; }
        }
        return true;
    }

    private static int indexOf(List<JsonNode> list, JsonNode x) {
        for (int i = 0; i < list.size(); i++) if (Json.equal(list.get(i), x)) return i;
        return -1;
    }
}
