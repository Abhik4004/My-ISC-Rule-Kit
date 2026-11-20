package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java API for testing a rule from JUnit (or any Java code), in the style of SailPoint's Rule Development Kit.
 * Assertion failures throw {@link AssertionError}, so any test framework reports them; the kit itself needs no JUnit.
 *
 * <pre>
 *   private static final RuleTester RULE = RuleTester.load("rules/BeforeProvisioning/MyRule.java");
 *
 *   &#64;Test
 *   public void disableMovesToDisabledOu() {
 *       RULE.run("""
 *           { "plan": { ... }, "application": { ... }, "idn": { ... } }
 *           """)
 *           .assertNoError()
 *           .assertChanged("""
 *           { "plan": { "accountRequests": [ { "operation": "Disable" } ] } }
 *           """);
 *   }
 * </pre>
 * The JSON is the same as the {@code inputs} / {@code expect...} parts of a cases file.
 */
public final class RuleTester {
    private final ParsedRule rule;
    private final String label;
    private long timeoutMs = 10_000;

    private RuleTester(ParsedRule rule, String label) {
        this.rule = rule;
        this.label = label;
    }

    /**
     * Loads a rule file (.java, .bsh or rule .xml). A relative path is tried from the working directory, then from the
     * {@code iscrule.root} system property, then from an {@code isc-rule-kit} sub-folder.
     */
    public static RuleTester load(String path) {
        Path p = resolve(path);
        try {
            RuleLoader.Loaded l = RuleLoader.fromFile(p, null);
            if (l.rule().type == null) throw new IllegalStateException("Cannot tell the rule type of " + p + ": put it in a folder named after its type");
            return new RuleTester(l.rule(), p.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read rule " + p, e);
        }
    }

    /** Same as {@link #load(String)} but forces the rule type (e.g. "BeforeProvisioning"). */
    public static RuleTester load(String path, String type) {
        RuleType t = RuleType.fromName(type).orElseThrow(() -> new IllegalArgumentException("Unknown rule type " + type));
        Path p = resolve(path);
        try {
            return new RuleTester(RuleLoader.fromFile(p, t).rule(), p.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read rule " + p, e);
        }
    }

    private static Path resolve(String path) {
        List<Path> tries = new ArrayList<>();
        tries.add(Path.of(path));
        String root = System.getProperty("iscrule.root");
        if (root != null) tries.add(Path.of(root).resolve(path));
        tries.add(Path.of("isc-rule-kit").resolve(path));
        for (Path p : tries) if (Files.isRegularFile(p)) return p;
        throw new IllegalArgumentException("Rule file not found: " + path + " (working directory: " + Path.of("").toAbsolutePath() + ")");
    }

    public RuleTester timeoutMs(long ms) {
        this.timeoutMs = ms;
        return this;
    }

    /** Runs the rule with the given inputs (JSON object: plan, identity, application, idn mocks, ...). */
    public Run run(String inputsJson) {
        ObjectNode inputs;
        try {
            JsonNode n = Json.MAPPER.readTree(inputsJson);
            if (!(n instanceof ObjectNode o)) throw new IllegalArgumentException("inputs must be a JSON object");
            inputs = o;
        } catch (IOException e) {
            throw new IllegalArgumentException("inputs are not valid JSON: " + e.getMessage(), e);
        }
        TestSuite.Case c = new TestSuite.Case("run", inputs, null, false, false, null, null, List.of());
        return new Run(TestSuite.runCase(rule, c, timeoutMs, false).outcome(), inputs);
    }

    /** The outcome of one run, with fluent assertions. */
    public final class Run {
        private final RuleRunner.Outcome outcome;
        private final ObjectNode inputs;

        Run(RuleRunner.Outcome outcome, ObjectNode inputs) {
            this.outcome = outcome;
            this.inputs = inputs;
        }

        /** The returned value as plain Java (String, Map, List, ...); null for a void rule. */
        public Object returned() { return outcome.result(); }

        /** The plan after the rule ran (BeforeProvisioning), as JSON. */
        public JsonNode plan() {
            Object p = outcome.changed().get("plan");
            return p != null ? Json.toNode(p) : inputs.get("plan");
        }

        public List<String> logs() {
            List<String> l = new ArrayList<>();
            for (RuleRunner.LogLine x : outcome.logs()) l.add(x.level() + " " + x.message());
            return l;
        }

        public Run assertNoError() {
            if (!outcome.ok()) fail("the rule threw " + outcome.error().type() + ": " + outcome.error().message()
                    + (outcome.error().line() != null ? " (line " + outcome.error().line() + ")" : ""));
            return this;
        }

        /** The rule must throw; {@code contains} (may be empty) must appear in "Type: message". */
        public Run assertError(String contains) {
            check(caseWith(c -> c.put("expectError", contains == null || contains.isEmpty() ? null : contains), contains == null || contains.isEmpty()));
            return this;
        }

        /** The return value must equal this JSON (a JSON string like "\"abc\"" or an object/array/number/null). */
        public Run assertReturns(String expectedJson) {
            check(caseWith(c -> c.set("expect", parse(expectedJson)), false));
            return this;
        }

        /** Inputs after the run must contain this JSON (same rules as expectChanged: objects are subsets, arrays match in size). */
        public Run assertChanged(String expectedJson) {
            check(caseWith(c -> c.set("expectChanged", parse(expectedJson)), false));
            return this;
        }

        public Run assertLogContains(String text) {
            check(caseWith(c -> c.putArray("expectLogContains").add(text), false));
            return this;
        }

        private TestSuite.Case caseWith(java.util.function.Consumer<ObjectNode> f, boolean anyError) {
            ObjectNode c = Json.MAPPER.createObjectNode();
            f.accept(c);
            boolean expectError = c.has("expectError");
            String contains = expectError && !c.get("expectError").isNull() ? c.get("expectError").asText() : null;
            List<String> logs = new ArrayList<>();
            if (c.has("expectLogContains")) c.get("expectLogContains").forEach(x -> logs.add(x.asText()));
            return new TestSuite.Case("assert", inputs, c.get("expect"), c.has("expect"), expectError || anyError, contains,
                    c.get("expectChanged"), logs);
        }

        private void check(TestSuite.Case c) {
            TestSuite.Result r = TestSuite.evaluate(c, outcome, false);
            if (r.status() == TestSuite.Status.ERROR)
                fail("the rule threw " + outcome.error().type() + ": " + outcome.error().message());
            if (!r.failures().isEmpty()) fail(String.join("\n", r.failures()));
        }

        private JsonNode parse(String json) {
            try {
                return Json.MAPPER.readTree(json);
            } catch (IOException e) {
                throw new IllegalArgumentException("not valid JSON: " + json, e);
            }
        }

        private void fail(String msg) {
            StringBuilder b = new StringBuilder(label).append(": ").append(msg);
            if (!outcome.logs().isEmpty()) {
                b.append("\nLog:");
                for (RuleRunner.LogLine l : outcome.logs()) b.append("\n  ").append(l.level()).append(' ').append(l.message());
            }
            throw new AssertionError(b.toString());
        }
    }
}
