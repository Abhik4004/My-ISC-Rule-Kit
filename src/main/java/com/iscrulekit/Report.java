package com.iscrulekit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.Finding.Severity;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Console / JSON / JUnit rendering of validator findings and test results. */
public final class Report {
    private Report() {}

    public static int count(List<Finding> f, Severity s) {
        int n = 0;
        for (Finding x : f) if (x.severity() == s) n++;
        return n;
    }

    static final int WIDTH = 110;
    private static final String RULE = "-".repeat(WIDTH);

    public static void printHeader(PrintStream out, String file, ParsedRule p, boolean autoType) {
        String type = p.type != null ? p.type.xmlName + (autoType ? " (auto-detected)" : "")
                : p.typeName != null ? p.typeName + " (unsupported)" : "unknown";
        out.println(Ansi.bold("Rule  ") + file + Ansi.dim(p.xml ? "  (rule XML)" : ""));
        out.println(Ansi.bold("Type  ") + type + (p.type != null ? Ansi.dim("   inputs: " + String.join(", ", p.type.inputs)) : ""));
    }

    public static void printFindings(PrintStream out, String file, ParsedRule p, boolean autoType, List<Finding> findings, boolean verbose) {
        printHeader(out, file, p, autoType);
        out.println();
        int errors = count(findings, Severity.ERROR), warns = count(findings, Severity.WARN), notes = count(findings, Severity.INFO);
        String summary = (errors > 0 ? Ansi.red(errors + " error" + s(errors)) : "0 errors") + ", "
                + (warns > 0 ? Ansi.yellow(warns + " warning" + s(warns)) : "0 warnings") + ", " + notes + " note" + s(notes);
        out.println(Ansi.bold("VALIDATION") + "  " + summary);
        out.println(Ansi.dim(RULE));
        // one entry per distinct problem, listing every line it occurs on
        Map<String, List<Finding>> groups = new LinkedHashMap<>();
        for (Severity sev : new Severity[] {Severity.ERROR, Severity.WARN, Severity.INFO}) {
            if (sev == Severity.INFO && !verbose) continue;
            for (Finding f : findings) if (f.severity() == sev) groups.computeIfAbsent(sev + f.code() + f.message(), k -> new ArrayList<>()).add(f);
        }
        for (List<Finding> g : groups.values()) {
            Finding f = g.get(0);
            List<String> lines = new ArrayList<>();
            for (Finding x : g) if (x.line() != null) lines.add(String.valueOf(x.line()));
            String where = lines.isEmpty() ? "" : (lines.size() == 1 ? "line " : "lines ") + String.join(", ", lines);
            String tag = switch (f.severity()) { case ERROR -> Ansi.red("ERROR"); case WARN -> Ansi.yellow("WARN "); default -> Ansi.dim("NOTE "); };
            out.println("  " + tag + "  " + Ansi.bold(where.isEmpty() ? f.code() : where) + Ansi.dim("  " + (where.isEmpty() ? "" : f.code())));
            for (String l : wrap(f.message(), WIDTH - 9)) out.println("         " + l);
        }
        if (findings.isEmpty()) out.println("  " + Ansi.green("No problems found."));
        else if (!verbose && notes > 0) out.println(Ansi.dim("  " + notes + " note" + s(notes) + " hidden (unused imports and similar). --verbose shows them."));
    }

    private static String s(long n) { return n == 1 ? "" : "s"; }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + w.length() > width) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    public static ObjectNode findingsJson(String file, ParsedRule p, List<Finding> findings) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("file", file);
        root.put("format", p.xml ? "xml" : "raw");
        root.put("type", p.type == null ? null : p.type.xmlName);
        ArrayNode arr = root.putArray("findings");
        for (Finding f : findings) {
            ObjectNode o = arr.addObject();
            o.put("severity", f.severity().name());
            o.put("code", f.code());
            if (f.line() == null) o.putNull("line"); else o.put("line", f.line());
            o.put("message", f.message());
        }
        ObjectNode s = root.putObject("summary");
        s.put("errors", count(findings, Severity.ERROR));
        s.put("warnings", count(findings, Severity.WARN));
        s.put("notes", count(findings, Severity.INFO));
        return root;
    }

    public static void printTestsHeading(PrintStream out, int n, String note) {
        out.println();
        out.println(Ansi.bold("TESTS") + "  " + n + " case" + s(n) + (note == null ? "" : Ansi.dim("  " + note)));
        out.println(Ansi.dim(RULE));
    }

    private static final String IND = "         ";

    public static void printResult(PrintStream out, TestSuite.Result r, boolean verbose) {
        RuleRunner.Outcome o = r.outcome();
        String tag = switch (r.status()) {
            case PASS -> Ansi.green("PASS ");
            case FAIL -> Ansi.red("FAIL ");
            case ERROR -> Ansi.red("ERROR");
            default -> Ansi.cyan("RAN  ");
        };
        out.println("  " + tag + "  " + r.testCase().name() + (verbose ? Ansi.dim("  (" + o.ms() + " ms)") : ""));
        boolean details = verbose || r.status() != TestSuite.Status.PASS;
        if (!details) return;

        if (!r.failures().isEmpty()) {
            out.println(IND + Ansi.bold("Why it failed"));
            for (String f : r.failures()) for (String l : f.split("\n")) {
                String lead = l.substring(0, l.length() - l.stripLeading().length());
                List<String> parts = wrap(l.stripLeading(), WIDTH - 11 - lead.length());
                for (int i = 0; i < parts.size(); i++) out.println(IND + "  " + lead + (i == 0 ? "" : "    ") + parts.get(i));
            }
        }
        if (!o.ok()) {
            RuleRunner.ErrorInfo e = o.error();
            out.println(IND + Ansi.red(e.type()) + (e.line() != null ? Ansi.bold("  at line " + e.line()) : ""));
            for (String l : wrap(e.message(), WIDTH - 11)) out.println(IND + "  " + l);
            if (e.hint() != null) for (String l : wrap("Hint: " + e.hint(), WIDTH - 11)) out.println(IND + "  " + Ansi.cyan(l));
        } else if (!o.voidResult() && (o.result() != null || r.testCase().hasExpect())) {
            out.println(IND + Ansi.bold("Returned  ") + Diff.show(Json.toNode(o.result()), verbose) + Ansi.dim("  (" + o.kind() + ")"));
        }

        List<String> changes = new ArrayList<>();
        JsonNode after = Json.toNode(o.changed());
        after.fieldNames().forEachRemaining(k -> changes.addAll(Diff.changes(r.testCase().inputs().get(k), after.get(k), k, verbose)));
        if (!changes.isEmpty()) {
            out.println(IND + Ansi.bold("What the rule changed"));
            for (String c : changes) {
                List<String> parts = wrap(c, WIDTH - 11);
                for (int i = 0; i < parts.size(); i++) {
                    String p = i == 0 ? parts.get(i) : "    " + parts.get(i);
                    out.println(IND + "  " + (c.startsWith("+ ") ? Ansi.green(p) : c.startsWith("- ") ? Ansi.red(p) : p));
                }
            }
        } else if (o.ok() && (verbose || r.testCase().expectChanged() != null)) {
            out.println(IND + Ansi.bold("What the rule changed") + Ansi.dim("  nothing"));
        }

        boolean showLogs = verbose || r.status() == TestSuite.Status.FAIL || r.status() == TestSuite.Status.ERROR;
        if (!o.logs().isEmpty()) {
            if (showLogs) {
                out.println(IND + Ansi.bold("Log"));
                for (RuleRunner.LogLine l : o.logs()) {
                    List<String> parts = wrap(l.message(), WIDTH - 17);
                    for (int i = 0; i < parts.size(); i++)
                        out.println(IND + "  " + (i == 0 ? Ansi.dim(String.format("%-5s", l.level())) : "     ") + " " + parts.get(i));
                }
            } else {
                out.println(IND + Ansi.dim(o.logs().size() + " log line" + s(o.logs().size()) + " (--verbose shows them)"));
            }
        }
        List<String> notes = new ArrayList<>();
        for (String n : o.notes()) if (verbose || n.startsWith("NOT MOCKED")) notes.add(n);
        if (!notes.isEmpty()) {
            out.println(IND + Ansi.bold("Notes"));
            for (String n : notes) out.println(IND + "  " + (n.startsWith("NOT MOCKED") ? Ansi.yellow(n) : Ansi.dim(n)));
        }
        out.println();
    }

    public static void printSummary(PrintStream out, List<TestSuite.Result> results) {
        long pass = 0, fail = 0, error = 0, ran = 0;
        for (TestSuite.Result r : results) switch (r.status()) {
            case PASS -> pass++;
            case FAIL -> fail++;
            case ERROR -> error++;
            default -> ran++;
        }
        List<String> parts = new ArrayList<>();
        if (pass > 0) parts.add(Ansi.green(pass + " passed"));
        if (fail > 0) parts.add(Ansi.red(fail + " failed"));
        if (error > 0) parts.add(Ansi.red(error + " error" + s(error)));
        if (ran > 0) parts.add(Ansi.cyan(ran + " ran without checks"));
        out.println(Ansi.dim(RULE));
        out.println(Ansi.bold("RESULT") + "  " + String.join(", ", parts) + Ansi.dim("  (" + results.size() + " case" + s(results.size()) + ")"));
    }

    public static ArrayNode resultsJson(List<TestSuite.Result> results) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (TestSuite.Result r : results) {
            ObjectNode o = arr.addObject();
            RuleRunner.Outcome oc = r.outcome();
            o.put("name", r.testCase().name());
            o.put("status", r.status().name());
            o.put("ms", oc.ms());
            ArrayNode f = o.putArray("failures");
            r.failures().forEach(f::add);
            if (oc.ok()) {
                o.put("kind", oc.kind());
                o.set("result", oc.voidResult() ? null : Json.toNode(oc.result()));
                o.set("changed", Json.toNode(oc.changed()));
            } else {
                ObjectNode e = o.putObject("error");
                e.put("type", oc.error().type());
                e.put("message", oc.error().message());
                if (oc.error().line() != null) e.put("line", oc.error().line());
                if (oc.error().hint() != null) e.put("hint", oc.error().hint());
            }
            ArrayNode logs = o.putArray("logs");
            oc.logs().forEach(l -> logs.addObject().put("level", l.level()).put("message", l.message()));
            ArrayNode notes = o.putArray("notes");
            oc.notes().forEach(notes::add);
        }
        return arr;
    }

    public static String junit(String suiteName, List<TestSuite.Result> results) {
        int fail = 0, err = 0;
        for (TestSuite.Result r : results) { if (r.status() == TestSuite.Status.FAIL) fail++; if (r.status() == TestSuite.Status.ERROR) err++; }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(x(suiteName)).append("\" tests=\"").append(results.size())
                .append("\" failures=\"").append(fail).append("\" errors=\"").append(err).append("\">\n");
        for (TestSuite.Result r : results) {
            sb.append("  <testcase name=\"").append(x(r.testCase().name())).append("\" time=\"").append(r.outcome().ms() / 1000.0).append("\"");
            if (r.passed()) { sb.append("/>\n"); continue; }
            sb.append(">\n");
            if (r.status() == TestSuite.Status.ERROR) {
                RuleRunner.ErrorInfo e = r.outcome().error();
                sb.append("    <error type=\"").append(x(e.type())).append("\" message=\"").append(x(e.message())).append("\"/>\n");
            } else {
                sb.append("    <failure message=\"").append(x(String.join("; ", r.failures()))).append("\"/>\n");
            }
            sb.append("  </testcase>\n");
        }
        return sb.append("</testsuite>\n").toString();
    }

    private static String x(String s) {
        return String.valueOf(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
