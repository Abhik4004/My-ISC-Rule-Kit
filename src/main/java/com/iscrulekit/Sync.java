package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.Finding.Severity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code iscrule auto} and {@code iscrule sync}: keep a rule's test cases in step with its code.
 * <ol>
 *   <li>Runs every existing case. Your own cases are never changed: a failure is reported. Auto cases (written by the
 *       kit) whose result changed are shown with the difference, and you accept the new behaviour or keep the old
 *       expectation (the test then fails, flagging a regression).</li>
 *   <li>Finds blocks of the rule no case reaches, and generates cases for them (coverage-guided, {@link AutoGen}).</li>
 *   <li>Prints the coverage: blocks reached, and the ones still unreached.</li>
 *   <li>{@code sync} only: regenerates the RDK test class and runs it in the Rule Development Kit.</li>
 * </ol>
 */
final class Sync {
    private Sync() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final java.util.regex.Pattern SET_MAP = java.util.regex.Pattern.compile("\\.\\s*setMap\\s*\\(");

    record Options(RuleType type, boolean rdk, String rdkFolder, String name, boolean acceptAll, int max, long timeoutMs, long budgetMs) {}

    static int run(Path rule, Path cases, Options o, BufferedReader in, PrintStream out) throws IOException {
        RuleLoader.Loaded l = RuleLoader.fromFile(rule, o.type());
        ParsedRule p = l.rule();
        if (p.type == null) throw new Main.UsageException("could not tell the rule type of " + rule + "; pass --type (see: iscrule types)");
        if (cases == null) cases = RunPasted.casesFor(rule);
        CaseWizard.Ask ask = new CaseWizard.Ask(in, out);

        // setMap(...) works in the kit but not on SailPoint's classes (every RDK test fails): offer the one-word fix
        if (o.rdk()) {
            String text = Files.readString(rule);
            java.util.regex.Matcher sm = SET_MAP.matcher(text);
            int n = 0;
            while (sm.find()) n++;
            if (n > 0) {
                out.println(Ansi.yellow("setMap(...)") + " is used " + n + " time" + (n == 1 ? "" : "s") + " in " + rule.getFileName()
                        + ". SailPoint's rule API has no setMap, so every RDK test would fail with 'Method setMap not found'.");
                out.println("putAll(map) does the same thing on a new Attributes object.");
                if (o.acceptAll() || ask.yes("Replace setMap( with putAll( in " + rule.getFileName() + " now?", true)) {
                    Files.writeString(rule, SET_MAP.matcher(text).replaceAll(".putAll("));
                    out.println("  " + Ansi.green("fixed") + " " + n + " place" + (n == 1 ? "" : "s") + " in " + rule);
                    l = RuleLoader.fromFile(rule, o.type());
                    p = l.rule();
                }
                out.println();
            }
        }

        out.println(Ansi.bold(o.rdk() ? "SYNC" : "AUTO") + "  " + rule + Ansi.dim("  (" + p.type.xmlName + ")   cases: " + cases));
        List<Finding> findings = Validator.validate(p);
        int errors = Report.count(findings, Severity.ERROR), warns = Report.count(findings, Severity.WARN);
        out.println("  " + pad("validation") + (errors > 0 ? Ansi.red(errors + " error" + (errors == 1 ? "" : "s")) : Ansi.green("0 errors")) + ", " + warns + " warning" + (warns == 1 ? "" : "s"));
        for (Finding f : findings) if (f.severity() == Severity.ERROR || f.severity() == Severity.WARN && (f.code().startsWith("BUG") || f.code().startsWith("API")))
            out.println("  " + pad("") + (f.severity() == Severity.ERROR ? Ansi.red("ERROR") : Ansi.yellow("WARN ")) + " " + Ansi.dim(f.code())
                    + (f.line() != null ? " line " + f.line() : "") + ": " + firstSentence(f.message()));

        // 1. existing cases
        ObjectNode root = readCases(cases);
        ArrayNode list = (ArrayNode) root.get("cases");
        boolean changedFile = false, acceptRest = o.acceptAll();
        int pass = 0, accepted = 0, kept = 0, userFail = 0;
        List<String> userFailures = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ObjectNode node = (ObjectNode) list.get(i);
            TestSuite.Case c = TestSuite.parse(node, "case " + (i + 1));
            TestSuite.Result r = TestSuite.runCase(p, c, o.timeoutMs(), false);
            if (r.passed()) { pass++; continue; }
            boolean auto = node.path("auto").asBoolean(false) || c.name().contains("[recorded - review]");
            if (!auto) {
                userFail++;
                userFailures.add(c.name() + ": " + reason(r));
                continue;
            }
            out.println();
            out.println("  " + Ansi.yellow("Behaviour changed") + "  " + c.name());
            for (String line : reason(r).split("\n")) for (String w : Report.wrap(line, Report.WIDTH - 6)) out.println("      " + w);
            boolean accept = acceptRest;
            if (!accept) {
                String a = ask.choose("    Is the new behaviour right?", List.of("yes - update this test", "no - it's a bug, keep the old expectation (the test fails)",
                        "yes to this and all other changed auto tests"), "2", false);
                accept = !a.startsWith("no");
                if (a.startsWith("yes to this and all")) acceptRest = true;
            }
            if (accept) {
                rerecord(node, p.type, r.outcome());
                accepted++;
                changedFile = true;
            } else kept++;
        }

        // 2. new cases for unreached blocks
        List<ObjectNode> seeds = new ArrayList<>();
        for (JsonNode n : list) if (n.has("inputs") && n.get("inputs").isObject()) seeds.add((ObjectNode) n.get("inputs"));
        AutoGen.Result g = AutoGen.generate(p, seeds, o.max(), o.timeoutMs(), o.budgetMs());
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode n : list) names.add(n.path("name").asText());
        List<String> added = new ArrayList<>();
        for (AutoGen.Found f : g.cases()) {
            ObjectNode c = AutoGen.toJson(f, p.type);
            String name = c.get("name").asText();
            for (int k = 2; names.contains(name); k++) name = c.get("name").asText() + " (" + k + ")";
            c.put("name", name);
            names.add(name);
            list.add(c);
            added.add(name);
            changedFile = true;
        }
        if (changedFile) {
            if (cases.toAbsolutePath().getParent() != null) Files.createDirectories(cases.toAbsolutePath().getParent());
            Files.writeString(cases, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        }

        // 3. report
        out.println();
        if (list.size() == added.size()) out.println("  " + pad("existing") + "none yet");
        else out.println("  " + pad("existing") + (list.size() - added.size()) + " cases: " + Ansi.green(pass + " unchanged")
                + (accepted > 0 ? ", " + accepted + " updated (new behaviour accepted)" : "")
                + (kept > 0 ? ", " + Ansi.red(kept + " kept as failing (regressions)") : "")
                + (userFail > 0 ? ", " + Ansi.red(userFail + " of your own cases fail") : ""));
        for (String uf : userFailures) for (String w : Report.wrap(uf, Report.WIDTH - 16)) out.println("  " + pad("") + "  " + Ansi.red(w));
        if (!userFailures.isEmpty()) out.println("  " + pad("") + Ansi.dim("  your own cases are never changed automatically: fix the rule, or edit the case if the change is intended"));
        out.println("  " + pad("added") + (added.isEmpty() ? "nothing (every reachable block already has a test)" : added.size() + " auto case" + (added.size() == 1 ? "" : "s")));
        for (String a : added) out.println("  " + pad("") + "  + " + a);

        Set<Integer> covered = new LinkedHashSet<>(g.covered());
        int total = g.blocks().size();
        int pct = total == 0 ? 100 : covered.size() * 100 / total;
        out.println("  " + pad("coverage") + (pct >= 90 ? Ansi.green(covered.size() + " of " + total + " blocks") : Ansi.yellow(covered.size() + " of " + total + " blocks"))
                + Ansi.dim("  (" + pct + "%, " + g.runs() + " runs)"));
        int shown = 0;
        for (Coverage.Block b : g.blocks()) {
            if (covered.contains(b.id())) continue;
            if (shown++ == 12) { out.println("  " + pad("") + Ansi.dim("  ... and " + (total - covered.size() - 12) + " more (iscrule coverage " + rule + ")")); break; }
            out.println("  " + pad("") + Ansi.dim("  not reached: line " + b.line() + "  " + b.what()));
        }
        if (!g.paths().isEmpty()) {
            int pt = g.paths().size(), pc = g.pathsCovered().size();
            out.println("  " + pad("") + (pc == pt ? Ansi.green(pc + " of " + pt + " value paths") : Ansi.yellow(pc + " of " + pt + " value paths"))
                    + Ansi.dim("  (each branch's value checked where it is used, e.g. per-environment OUs)"));
            int vs = 0;
            for (Coverage.ValuePath vp : g.paths()) {
                if (g.pathsCovered().contains(vp.key())) continue;
                if (vs++ == 6) { out.println("  " + pad("") + Ansi.dim("  ...")); break; }
                out.println("  " + pad("") + Ansi.dim("  not checked: " + vp.var() + " set on line " + vp.defLine() + ", used on line " + vp.useLine()));
            }
        }
        if (covered.size() < total)
            out.println("  " + pad("") + Ansi.dim("  To reach a block, add a case with the values it needs: iscrule add-test " + rule));

        // 4. RDK
        if (!o.rdk()) {
            out.println();
            out.println("Cases saved in " + cases + ". Run them in SailPoint's RDK:  iscrule rdk " + rule);
            return errors > 0 || kept > 0 || userFail > 0 ? 1 : 0;
        }
        out.println();
        try {
            Rdk.findRdk(o.rdkFolder());
        } catch (Main.UsageException e) {
            out.println(Ansi.yellow("RDK not found, so the RDK step was skipped. Install it into this folder with:  iscrule rdk-update"));
            return 1;
        }
        int rc = Rdk.run(rule, cases, o.type(), o.rdkFolder(), o.name(), false, false, o.timeoutMs(), out);
        return rc != 0 || kept > 0 || userFail > 0 ? 1 : 0;
    }

    /** --watch: run, then again every time the rule file is saved. Stops on Ctrl+C. */
    static int watch(Path rule, Path cases, Options o, BufferedReader in, PrintStream out) throws IOException {
        long seen = -2;
        while (true) {
            long now = stamp(rule);
            if (now != seen) {
                if (seen != -2) { try { Thread.sleep(300); } catch (InterruptedException e) { return 0; } now = stamp(rule); }
                seen = now;
                out.println("==== " + java.time.LocalTime.now().withNano(0) + "  " + rule + " ====");
                try {
                    run(rule, cases, o, in, out);
                } catch (IOException | RuntimeException e) {
                    out.println("error: " + e.getMessage());
                }
                out.println();
                out.println("Watching " + rule + " for changes (Ctrl+C to stop)...");
                out.flush();
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return 0; }
        }
    }

    private static long stamp(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return -1; }
    }

    private static ObjectNode readCases(Path cases) throws IOException {
        ObjectNode root = F.objectNode();
        ArrayNode list = root.putArray("cases");
        if (!Files.exists(cases) || Files.readString(cases, StandardCharsets.UTF_8).isBlank()) return root;
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        JsonNode n = Json.MAPPER.readTree(text.startsWith("﻿") ? text.substring(1) : text);
        if (n.isObject() && n.has("cases") && n.get("cases").isArray()) return (ObjectNode) n; // keeps any other top-level keys
        if (n.isArray()) n.forEach(list::add);
        else if (n.isObject()) list.add(n);
        return root;
    }

    /** Replaces the recorded result of an auto case with the new one (and the "-> result" part of its name). */
    private static void rerecord(ObjectNode node, RuleType type, RuleRunner.Outcome o) {
        node.remove(List.of("expect", "expectChanged", "expectError", "expectLogContains"));
        CaseBuilder.record(node, type, o);
        String name = node.path("name").asText();
        int arrow = name.indexOf("  ->  ");
        if (arrow > 0) node.put("name", name.substring(0, arrow) + "  ->  " + AutoGen.outcomeText(o));
    }

    private static String reason(TestSuite.Result r) {
        if (r.status() == TestSuite.Status.ERROR && r.outcome().error() != null)
            return "throws " + r.outcome().error().type() + ": " + r.outcome().error().message();
        return String.join("\n", r.failures()).replace("expectChanged does not match what the rule did:\n", "").trim();
    }

    private static String pad(String s) { return s + " ".repeat(Math.max(1, 12 - s.length())); }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot + 1) : s;
    }
}
