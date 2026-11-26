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
        if (syntaxStop(rule, findings, out)) return 1;

        // 1. existing cases
        ObjectNode root = readCases(cases);
        ArrayNode list = (ArrayNode) root.get("cases");
        boolean changedFile = false, acceptRest = o.acceptAll();
        int pass = 0, accepted = 0, kept = 0, userFail = 0;
        List<String> userFailures = new ArrayList<>();
        List<Object[]> crashes = new ArrayList<>(); // [case name, inputs, outcome] of cases that end with an error
        for (int i = 0; i < list.size(); i++) {
            ObjectNode node = (ObjectNode) list.get(i);
            if (node.path("auto").asBoolean(false) && node.path("expectError").asText("").contains("ParseException")) {
                list.remove(i--); // recorded while the rule did not parse: not a behaviour worth keeping
                changedFile = true;
                continue;
            }
            TestSuite.Case c = TestSuite.parse(node, "case " + (i + 1));
            TestSuite.Result r = TestSuite.runCase(p, c, o.timeoutMs(), false);
            if (!r.outcome().ok()) crashes.add(new Object[] {c.name(), c.inputs(), r.outcome()});
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
            if (!f.outcome().ok()) crashes.add(new Object[] {f.name(), f.inputs(), f.outcome()});
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
        if (!crashes.isEmpty()) explainCrashes(rule, p, list.size(), crashes, o.timeoutMs(), out);

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
            try {
                Path html = CaseReport.htmlFor(cases);
                CaseReport.write(rule, p, cases, null, null, o.timeoutMs(), html);
                out.println("Report (per case: log and operations): " + html.toUri());
            } catch (IOException | RuntimeException e) {
                out.println(Ansi.dim("(no report: " + e.getMessage() + ")"));
            }
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

    /**
     * Cases that end with an error are recorded as "throws ...", which hides real bugs (and usually means a value the
     * rule needs was missing). Says how many, the message, and where it really happened: a rule that catches the error
     * and throws its own is run once more with its try/catch turned off, so the original line shows.
     */
    private static void explainCrashes(Path rule, ParsedRule p, int total, List<Object[]> crashes, long timeoutMs, PrintStream out) throws IOException {
        RuleRunner.Outcome first = (RuleRunner.Outcome) crashes.get(0)[2];
        out.println("  " + pad("errors") + Ansi.red(crashes.size() + " of " + total + " cases end with an error")
                + Ansi.dim(" (saved as the expected result: check that is intended)"));
        Set<String> msgs = new LinkedHashSet<>();
        for (Object[] c : crashes) msgs.add(((RuleRunner.Outcome) c[2]).error().type() + ": " + ((RuleRunner.Outcome) c[2]).error().message());
        int k = 0;
        for (String m : msgs) { if (k++ == 3) { out.println("  " + pad("") + "  ..."); break; } for (String w : Report.wrap(m, Report.WIDTH - 16)) out.println("  " + pad("") + "  " + w); }

        Integer line = first.error().line();
        String what = first.error().message();
        boolean unwrapped = false;
        ParsedRule bare = Coverage.withoutTryCatch(p);
        if (bare != null) {
            RuleRunner.Outcome again = RuleRunner.run(bare, (JsonNode) crashes.get(0)[1], timeoutMs);
            if (!again.ok() && again.error().line() != null && !again.error().line().equals(line)) {
                line = again.error().line();
                what = again.error().type() + ": " + again.error().message();
                unwrapped = true;
            }
        }
        if (line == null) return;
        List<String> lines = Files.readAllLines(rule, StandardCharsets.UTF_8);
        out.println("  " + pad("") + "  where it really happens" + (unwrapped ? Ansi.dim(" (the rule catches it and throws its own error, so this was found by running case \""
                + shorten(String.valueOf(crashes.get(0)[0]), 40) + "\" with the try/catch turned off)") : "") + ":");
        for (int n = Math.max(1, line - 2); n <= Math.min(lines.size(), line); n++) {
            String t = lines.get(n - 1).replace("\t", "    ");
            out.println("  " + pad("") + (n == line ? Ansi.red(" >") : "  ") + String.format("%5d | ", n) + (n == line ? Ansi.bold(t.strip()) : Ansi.dim(t.strip())));
        }
        out.println("  " + pad("") + "    " + Ansi.red(what));
        if (what.contains("null") || what.contains("Null"))
            out.println("  " + pad("") + "    Something on that line is null in the test. Usually a value the test does not supply (an identity or"
                    + "\n  " + pad("") + "    source attribute, an idn lookup) or one ISC can leave empty: add a null check in the rule, or give"
                    + "\n  " + pad("") + "    the value in a case:  iscrule add-test " + rule);
    }

    private static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    /**
     * When the rule does not parse nothing can run: shows the code around each syntax error and returns true,
     * so no tests are run, recorded or sent to the RDK until the rule is fixed.
     */
    static boolean syntaxStop(Path rule, List<Finding> findings, PrintStream out) throws IOException {
        List<Finding> syntax = findings.stream().filter(f -> f.severity() == Severity.ERROR && f.code().startsWith("SYN")).toList();
        if (syntax.isEmpty()) return false;
        List<String> lines = Files.readAllLines(rule, StandardCharsets.UTF_8);
        out.println();
        out.println(Ansi.red("The rule has a syntax error, so it cannot run.") + " Nothing was tested or recorded. Fix this first:");
        for (Finding f : syntax) {
            if (f.line() == null || f.line() < 1 || f.line() > lines.size()) { out.println("  " + f.message()); continue; }
            int at = f.line();
            out.println();
            java.util.regex.Matcher col = java.util.regex.Pattern.compile("column (\\d+)").matcher(f.message());
            java.util.regex.Matcher tok = java.util.regex.Pattern.compile("Encountered \"([^\"]*)\"").matcher(f.message());
            for (int n = Math.max(1, at - 3); n <= Math.min(lines.size(), at + 1); n++) {
                String text = lines.get(n - 1).replace("\t", "    ");
                out.println((n == at ? Ansi.red(" >") : "  ") + String.format("%5d | ", n) + (n == at ? Ansi.bold(text) : Ansi.dim(text)));
                if (n == at && col.find()) { // marker right under the broken line
                    String raw = lines.get(at - 1);
                    int c = Math.min(Integer.parseInt(col.group(1)) - 1, raw.length());
                    int shown = raw.substring(0, Math.max(0, c)).replace("\t", "    ").length();
                    out.println("          " + " ".repeat(shown) + Ansi.red("^ " + (tok.find() ? "BeanShell did not expect '" + tok.group(1).trim() + "' here" : "here")));
                }
            }
            // the previous code line (skipping blanks and // comments): a statement that does not end like one
            int prev = at - 1;
            while (prev >= 1 && (lines.get(prev - 1).isBlank() || lines.get(prev - 1).trim().startsWith("//")
                    || lines.get(prev - 1).trim().startsWith("*") || lines.get(prev - 1).trim().startsWith("/*"))) prev--;
            if (prev >= 1) {
                String code = lines.get(prev - 1).replaceAll("//.*$", "").trim();
                if (!code.isEmpty() && !code.matches(".*([;{}(,+\\-*/=?:!]|&&|\\|\\||else|try|do)$") && !code.startsWith("@")) {
                    out.println("  " + Ansi.yellow("Likely fix: line " + prev + " does not end with ';'") + " - add it after: "
                            + Ansi.bold(code.length() > 60 ? "..." + code.substring(code.length() - 60) : code));
                }
            }
            out.println("  The mistake is usually just before the marked spot: a missing ; or ), an extra ( or {,");
            out.println("  an 'else' after a statement that already ended, or an if/else written as a value (use ? : instead).");
        }
        return true;
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
