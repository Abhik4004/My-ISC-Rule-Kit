package com.iscrulekit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.Finding.Severity;

import java.io.PrintStream;
import java.util.List;

/** Console / JSON / JUnit rendering of validator findings and test results. */
public final class Report {
    private Report() {}

    public static int count(List<Finding> f, Severity s) {
        int n = 0;
        for (Finding x : f) if (x.severity() == s) n++;
        return n;
    }

    public static void printFindings(PrintStream out, String file, ParsedRule p, boolean autoType, List<Finding> findings) {
        out.println("Rule      : " + file);
        out.println("Format    : " + (p.xml ? "ISC rule XML" : "raw source"));
        out.println("Rule type : " + (p.type != null ? p.type.xmlName + " (" + p.type.label + ")" + (autoType ? "  [auto-detected]" : "")
                : p.typeName != null ? p.typeName + " (unsupported)" : "unknown"));
        if (p.type != null) out.println("Inputs    : " + String.join(", ", p.type.inputs));
        out.println();
        for (Finding f : findings) {
            out.printf("  %-5s %-6s %-9s %s%n", f.severity(), f.line() == null ? "-" : "L" + f.line(), f.code(), f.message());
        }
        if (findings.isEmpty()) out.println("  No findings.");
        out.println();
        out.printf("%d error(s), %d warning(s), %d note(s)%n", count(findings, Severity.ERROR), count(findings, Severity.WARN), count(findings, Severity.INFO));
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

    public static void printResult(PrintStream out, TestSuite.Result r, boolean verbose) {
        RuleRunner.Outcome o = r.outcome();
        out.printf("[%s] %s  (%d ms)%n", r.status(), r.testCase().name(), o.ms());
        for (String f : r.failures()) out.println("      FAIL: " + f);
        if (!o.ok()) {
            out.println("      Exception: " + o.error().type() + ": " + o.error().message());
            if (o.error().line() != null) out.println("      at line " + o.error().line());
            if (o.error().hint() != null) out.println("      Hint: " + o.error().hint());
        } else {
            out.println("      Returned" + " (" + o.kind() + "): " + (o.voidResult() ? "(no return value)" : Json.compact(o.result())));
        }
        if (!o.changed().isEmpty()) out.println("      Modified inputs: " + Json.compact(o.changed()));
        if (verbose || !r.passed()) {
            for (RuleRunner.LogLine l : o.logs()) out.println("      log " + l.level() + ": " + l.message());
            for (String n : o.notes()) out.println("      note: " + n);
        } else if (!o.logs().isEmpty() || !o.notes().isEmpty()) {
            out.println("      (" + o.logs().size() + " log line(s), " + o.notes().size() + " note(s); use --verbose to show)");
        }
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
