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

    /** Runs one case and checks it. A bad fixture (e.g. an unknown operation name) becomes an ERROR result, not a crash. */
    public static Result runCase(ParsedRule rule, Case c, long timeoutMs, boolean strictMocks) {
        RuleRunner.Outcome oc;
        try {
            oc = RuleRunner.run(rule, c.inputs(), timeoutMs);
        } catch (RuntimeException e) {
            oc = new RuleRunner.Outcome(false, null, false, "void", List.of(), List.of(), java.util.Map.of(),
                    new RuleRunner.ErrorInfo(e.getClass().getSimpleName(), String.valueOf(e.getMessage()), null, "Check the test case inputs."), 0);
        }
        return evaluate(c, oc, strictMocks);
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
            if (!ok) {
                JsonNode act = o.voidResult() ? null : Json.toNode(o.result());
                if (exp != null && act != null && exp.isObject() && act.isObject()) {
                    List<String> lines = new ArrayList<>(Diff.mismatches(exp, act, "return"));
                    act.fieldNames().forEachRemaining(k -> { if (!exp.has(k)) lines.add("return." + k + ": not expected, got " + Diff.show(act.get(k), true)); });
                    fails.add("Return value does not match expect:\n  " + String.join("\n  ", lines));
                } else {
                    fails.add("Return value: expected " + Diff.show(exp, true) + ", got " + (o.voidResult() ? "no value (void)" : Diff.show(act, true)));
                }
            }
        }
        if (c.expectChanged() != null) {
            // inputs the rule did not touch count as unchanged, so compare against the original input for those
            ObjectNode full = c.inputs().deepCopy();
            Json.toNode(o.changed()).fields().forEachRemaining(f -> full.set(f.getKey(), f.getValue()));
            if (!Json.subset(c.expectChanged(), full)) {
                List<String> lines = Diff.mismatches(c.expectChanged(), full, "");
                fails.add("expectChanged does not match what the rule did:" + (lines.isEmpty() ? "" : "\n  " + String.join("\n  ", lines)));
            }
        }
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
