package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Test-case files. Accepted shapes:
 * <pre>
 *   { "cases": [ { "name": "...", "inputs": {...}, "expect": ..., "expectError": false, "expectChanged": {...},
 *                  "expectLogContains": ["text"] } ] }
 *   [ { ...case... }, ... ]
 *   { "inputs": {...} }   or just the inputs object itself (a single case with no assertions)
 * </pre>
 */
public final class TestSuite {
    private TestSuite() {}

    public record Case(String name, ObjectNode inputs, JsonNode expect, boolean hasExpect, boolean expectError,
                       String expectErrorContains, JsonNode expectChanged, List<String> expectLogContains) {}

    public enum Status { PASS, FAIL, ERROR, RAN }

    public record Result(Case testCase, Status status, List<String> failures, RuleRunner.Outcome outcome) {
        public boolean passed() { return status == Status.PASS || status == Status.RAN; }
    }

    private static final Set<String> CONTROL_KEYS = Set.of("name", "expect", "expectError", "expectChanged", "expectLogContains", "inputs");

    public static List<Case> load(Path file) throws java.io.IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1); // Windows editors often write a BOM
        JsonNode root = Json.MAPPER.readTree(text);
        List<Case> cases = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) throw new java.io.IOException("Empty test-case file: " + file);
        JsonNode list = root.isArray() ? root : root.has("cases") ? root.get("cases") : null;
        if (list != null) {
            int i = 1;
            for (JsonNode c : list) cases.add(parseCase(c, "case " + i++));
        } else {
            cases.add(parseCase(root, "case 1"));
        }
        return cases;
    }

    /** Smoke-run cases for a rule type: the scaffold's inputs with every assertion removed (catches crashes, not wrong answers). */
    public static List<Case> defaults(RuleType t) {
        try {
            JsonNode root = Json.MAPPER.readTree(Scaffold.cases(t));
            List<Case> out = new ArrayList<>();
            for (JsonNode c : root.get("cases")) {
                Case k = parseCase(c, "default inputs");
                out.add(new Case("default inputs: " + k.name(), k.inputs(), null, false, false, null, null, List.of()));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Case parseCase(JsonNode c, String dflt) {
        if (!c.isObject()) throw new IllegalArgumentException("Each test case must be a JSON object");
        ObjectNode inputs;
        if (c.has("inputs") && c.get("inputs").isObject()) inputs = (ObjectNode) c.get("inputs");
        else {
            inputs = JsonNodeFactory.instance.objectNode();
            c.fields().forEachRemaining(f -> { if (!CONTROL_KEYS.contains(f.getKey())) inputs.set(f.getKey(), f.getValue()); });
        }
        String name = c.hasNonNull("name") ? c.get("name").asText() : dflt;
        boolean expectError = false;
        String contains = null;
        JsonNode ee = c.get("expectError");
        if (ee != null && ee.isBoolean()) expectError = ee.booleanValue();
        else if (ee != null && ee.isTextual()) { expectError = true; contains = ee.asText(); }
        List<String> logs = new ArrayList<>();
        if (c.has("expectLogContains")) c.get("expectLogContains").forEach(x -> logs.add(x.asText()));
        return new Case(name, inputs, c.get("expect"), c.has("expect"), expectError, contains,
                c.hasNonNull("expectChanged") ? c.get("expectChanged") : null, logs);
    }

    public static Result evaluate(Case c, RuleRunner.Outcome o, boolean strictMocks) {
        List<String> fails = new ArrayList<>();
        boolean asserted = c.hasExpect() || c.expectError() || c.expectChanged() != null || !c.expectLogContains().isEmpty() || strictMocks;

        if (!o.ok()) {
            if (c.expectError()) {
                String msg = o.error().type() + ": " + o.error().message();
                if (c.expectErrorContains() != null && !msg.contains(c.expectErrorContains()))
                    fails.add("Expected an exception containing \"" + c.expectErrorContains() + "\" but got: " + msg);
                return new Result(c, fails.isEmpty() ? Status.PASS : Status.FAIL, fails, o);
            }
            return new Result(c, Status.ERROR, fails, o);
        }
        if (c.expectError()) fails.add("Expected an exception, but the rule returned normally.");
        if (c.hasExpect()) {
            JsonNode actual = o.voidResult() ? null : Json.toNode(o.result());
            JsonNode exp = c.expect();
            boolean ok = o.voidResult() ? exp == null || exp.isNull() : Json.equal(exp, actual == null ? Json.toNode(null) : actual);
            if (!ok) fails.add("Expected " + Json.compact(exp) + " but got " + (o.voidResult() ? "no value (void)" : Json.compact(o.result())));
        }
        if (c.expectChanged() != null && !Json.subset(c.expectChanged(), Json.toNode(o.changed())))
            fails.add("Expected modified inputs to include " + Json.compact(c.expectChanged()) + " but got " + Json.compact(o.changed()));
        for (String needle : c.expectLogContains()) {
            boolean found = o.logs().stream().anyMatch(l -> l.message().contains(needle));
            if (!found) fails.add("Expected a log line containing \"" + needle + "\"");
        }
        if (strictMocks && o.notes().stream().anyMatch(n -> n.startsWith("NOT MOCKED")))
            fails.add("Strict mocks: the rule called idn methods that are not mocked (see notes).");
        Status s = !fails.isEmpty() ? Status.FAIL : asserted ? Status.PASS : Status.RAN;
        return new Result(c, s, fails, o);
    }
}
