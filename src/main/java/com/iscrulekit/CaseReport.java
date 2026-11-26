package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code iscrule report}: one page per rule that shows, for every test case, what went in, what the rule did
 * (operations and attribute requests it added, changed or removed, or what it returned), its log lines,
 * the check result, and the RDK JUnit test method that calls the rule together with the RDK's result.
 */
final class CaseReport {
    private CaseReport() {}

    /** What one case did, in the kit and (when known) in the RDK. */
    record Entry(int n, TestSuite.Case c, TestSuite.Result r, String rdkMethod, String rdkCode, String rdkStatus, String rdkMessage, String rdkOutput) {}

    /** Runs every case and writes {@code <cases>.report.html}; returns the entries (for printing one case). */
    static List<Entry> write(Path rule, ParsedRule p, Path cases, Path rdkTestFile, Path rdkReport, long timeoutMs, Path html) throws IOException {
        List<TestSuite.Case> list = TestSuite.load(cases);
        List<String[]> methods = rdkTestFile != null && Files.isRegularFile(rdkTestFile) ? methods(Files.readString(rdkTestFile, StandardCharsets.UTF_8)) : List.of();
        Map<String, String[]> rdk = rdkReport != null ? surefire(rdkReport) : Map.of();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            TestSuite.Case c = list.get(i);
            TestSuite.Result r = TestSuite.runCase(p, c, timeoutMs, false);
            String[] m = i < methods.size() ? methods.get(i) : null;
            String[] res = m == null ? null : rdk.get(m[0]);
            entries.add(new Entry(i + 1, c, r, m == null ? null : m[0], m == null ? null : m[1],
                    res == null ? null : res[0], res == null ? null : res[1], res == null ? null : res[2]));
        }
        String setup = rdkTestFile != null && Files.isRegularFile(rdkTestFile) ? setup(Files.readString(rdkTestFile, StandardCharsets.UTF_8)) : null;
        Files.writeString(html, html(rule, p, cases, rdkTestFile, setup, entries), StandardCharsets.UTF_8);
        return entries;
    }

    /** Default report file: next to the cases file, {@code X.cases.json -> X.report.html}. */
    static Path htmlFor(Path cases) {
        String f = cases.getFileName().toString().replaceFirst("(?i)\\.cases\\.json$", "").replaceFirst("\\.json$", "");
        return cases.toAbsolutePath().resolveSibling(f + ".report.html");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // RDK test class

    /** [method name, method source incl. its doc comment] for each @Test, in file order. */
    static List<String[]> methods(String java) {
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^([ \\t]*/\\*\\*[^\\n]*\\*/\\s*\\n)?[ \\t]*@Test\\s+public void (\\w+)\\s*\\([^)]*\\)[^{]*\\{").matcher(java);
        while (m.find()) {
            int depth = 0, end = m.end() - 1;
            for (int k = end; k < java.length(); k++) {
                char ch = java.charAt(k);
                if (ch == '"') { // skip string literals
                    for (k++; k < java.length() && java.charAt(k) != '"'; k++) if (java.charAt(k) == '\\') k++;
                    continue;
                }
                if (ch == '{') depth++;
                if (ch == '}' && --depth == 0) { end = k; break; }
            }
            out.add(new String[] {m.group(2), dedent(java.substring(m.start(), end + 1))});
        }
        return out;
    }

    /** The class header up to the first test (imports, logger, rule file name), shared by every test. */
    private static String setup(String java) {
        Matcher m = Pattern.compile("(?m)^([ \\t]*/\\*\\*[^\\n]*\\*/\\s*\\n)?[ \\t]*@Test").matcher(java);
        return m.find() ? java.substring(0, m.start()).stripTrailing() + "\n\n    // ... one @Test method per case (below) ...\n}" : null;
    }

    private static String dedent(String s) {
        return s.lines().map(l -> l.startsWith("    ") ? l.substring(4) : l).reduce((a, b) -> a + "\n" + b).orElse(s);
    }

    /** method -> [PASS|FAIL, message, console output] from a surefire XML report. */
    static Map<String, String[]> surefire(Path report) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(report)) return out;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList tcs = f.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("testcase");
            for (int i = 0; i < tcs.getLength(); i++) {
                Element tc = (Element) tcs.item(i);
                String status = "PASS", msg = "";
                for (String tag : List.of("failure", "error")) {
                    NodeList bad = tc.getElementsByTagName(tag);
                    if (bad.getLength() == 0) continue;
                    Element e = (Element) bad.item(0);
                    status = "FAIL";
                    msg = e.getAttribute("message").isBlank() ? e.getTextContent().strip().lines().findFirst().orElse("") : e.getAttribute("message");
                }
                StringBuilder console = new StringBuilder();
                for (String tag : List.of("system-out", "system-err")) {
                    NodeList o = tc.getElementsByTagName(tag);
                    for (int k = 0; k < o.getLength(); k++) console.append(o.item(k).getTextContent().strip()).append('\n');
                }
                out.put(tc.getAttribute("name").replaceFirst("\\(.*$", ""), new String[] {status, msg, console.toString().strip()});
            }
        } catch (Exception e) {
            // no RDK details then
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // what the rule did

    /** One line per operation the rule performed, e.g. "Modify -> Enable", "+ AC_NewParent Set \"OU=...\"". */
    static List<String[]> operations(JsonNode inputs, RuleRunner.Outcome o) {
        List<String[]> ops = new ArrayList<>(); // [kind (op|add|del|same|ret|err), text]
        if (!o.ok()) {
            ops.add(new String[] {"err", "throws " + o.error().type() + ": " + o.error().message()
                    + (o.error().line() != null ? " (line " + o.error().line() + ")" : "")});
            return ops;
        }
        JsonNode before = inputs.path("plan");
        Object afterObj = o.changed().get("plan");
        if (before.isObject() || afterObj != null) {
            JsonNode after = afterObj == null ? before : Json.toNode(afterObj);
            if (afterObj == null) ops.add(new String[] {"same", "the plan was not changed"});
            JsonNode br = before.path("accountRequests"), ar = after.path("accountRequests");
            for (int i = 0; i < Math.max(br.size(), ar.size()); i++) {
                JsonNode b = br.path(i), a = ar.path(i);
                String app = (a.isMissingNode() ? b : a).path("applicationName").asText("");
                if (b.isMissingNode()) { ops.add(new String[] {"add", "new account request: " + a.path("operation").asText() + " on " + app}); }
                else if (a.isMissingNode()) { ops.add(new String[] {"del", "account request removed: " + b.path("operation").asText() + " on " + app}); continue; }
                else {
                    String bo = b.path("operation").asText(), ao = a.path("operation").asText();
                    ops.add(new String[] {bo.equals(ao) ? "same" : "op", (bo.equals(ao) ? "operation " + ao + " (unchanged)" : "operation " + bo + "  ->  " + ao) + "   on " + app});
                }
                Set<String> was = new HashSet<>(), now = new HashSet<>();
                for (JsonNode x : b.path("attributeRequests")) was.add(Json.compact(x));
                for (JsonNode x : a.path("attributeRequests")) now.add(Json.compact(x));
                for (JsonNode x : a.path("attributeRequests"))
                    ops.add(new String[] {was.contains(Json.compact(x)) ? "same" : "add", (was.contains(Json.compact(x)) ? "  " : "+ ") + attr(x)
                            + (was.contains(Json.compact(x)) ? "   (from the request)" : "   (added by the rule)")});
                for (JsonNode x : b.path("attributeRequests"))
                    if (!now.contains(Json.compact(x))) ops.add(new String[] {"del", "- " + attr(x) + "   (removed by the rule)"});
            }
            for (Map.Entry<String, Object> e : o.changed().entrySet())
                if (!e.getKey().equals("plan")) ops.add(new String[] {"op", e.getKey() + " changed to " + Diff.show(Json.toNode(e.getValue()), true)});
            return ops;
        }
        ops.add(new String[] {"ret", o.voidResult() ? "returns nothing" : "returns " + Diff.show(Json.toNode(o.result()), true)});
        for (Map.Entry<String, Object> e : o.changed().entrySet()) ops.add(new String[] {"op", e.getKey() + " changed to " + Diff.show(Json.toNode(e.getValue()), true)});
        return ops;
    }

    private static String attr(JsonNode x) {
        JsonNode v = x.path("value");
        return x.path("name").asText() + " " + x.path("operation").asText("Set") + " " + (v.isMissingNode() ? "" : Diff.show(v, true));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // terminal

    static void print(Entry e, JsonNode inputs, PrintStream out) {
        TestSuite.Result r = e.r();
        out.println(Ansi.bold("CASE " + e.n()) + "  " + e.c().name());
        out.println("  " + pad("kit") + (r.passed() ? Ansi.green("PASS") : Ansi.red("FAIL")) + Ansi.dim("  (" + r.outcome().ms() + " ms)"));
        for (String f : r.failures()) out.println("  " + pad("") + Ansi.red(f.replace("\n", "\n" + " ".repeat(14))));
        if (e.rdkStatus() != null) out.println("  " + pad("RDK") + (e.rdkStatus().equals("PASS") ? Ansi.green("PASS") : Ansi.red("FAIL")) + "  " + Ansi.dim(e.rdkMethod()));
        if (e.rdkMessage() != null && !e.rdkMessage().isBlank()) out.println("  " + pad("") + Ansi.red(e.rdkMessage()));
        out.println();
        out.println(Ansi.bold("  What the rule did"));
        for (String[] op : operations(inputs, r.outcome())) {
            String t = "    " + op[1];
            out.println(switch (op[0]) { case "add" -> Ansi.green(t); case "del", "err" -> Ansi.red(t); case "op" -> Ansi.yellow(t); default -> Ansi.dim(t); });
        }
        out.println();
        out.println(Ansi.bold("  Log") + Ansi.dim("  (" + r.outcome().logs().size() + " lines)"));
        if (r.outcome().logs().isEmpty()) out.println(Ansi.dim("    (the rule logged nothing)"));
        for (RuleRunner.LogLine l : r.outcome().logs())
            if (!l.message().startsWith("@@cov:")) out.println("    " + Ansi.dim(String.format("%-5s", l.level())) + " " + l.message());
        if (e.rdkCode() != null) {
            out.println();
            out.println(Ansi.bold("  RDK test that calls the rule") + Ansi.dim("  (" + e.rdkMethod() + ")"));
            for (String l : e.rdkCode().split("\n")) out.println("    " + (l.contains("i.eval(source)") ? Ansi.bold(Ansi.cyan(l)) : l));
        }
    }

    private static String pad(String s) { return s + " ".repeat(Math.max(1, 12 - s.length())); }

    // ---------------------------------------------------------------------------------------------------------------
    // HTML

    private static String html(Path rule, ParsedRule p, Path cases, Path rdkTestFile, String setup, List<Entry> entries) {
        int kitPass = 0, rdkPass = 0, rdkRun = 0;
        for (Entry e : entries) {
            if (e.r().passed()) kitPass++;
            if (e.rdkStatus() != null) { rdkRun++; if (e.rdkStatus().equals("PASS")) rdkPass++; }
        }
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>Rule Test Report</title><style>").append(CSS).append("</style></head><body><main>");
        h.append("<header><h1>").append(esc(rule.getFileName().toString())).append("</h1><p class=\"sub\">")
         .append(esc(p.type == null ? "" : p.type.xmlName)).append(" rule &middot; cases: ").append(esc(cases.toString()))
         .append(" &middot; ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("</p>");
        h.append("<div class=\"stats\"><div><b>").append(entries.size()).append("</b><span>cases</span></div>")
         .append("<div><b class=\"").append(kitPass == entries.size() ? "ok" : "bad").append("\">").append(kitPass).append("</b><span>pass in the kit</span></div>");
        if (rdkRun > 0) h.append("<div><b class=\"").append(rdkPass == rdkRun ? "ok" : "bad").append("\">").append(rdkPass).append(" / ").append(rdkRun).append("</b><span>pass in the RDK</span></div>");
        h.append("</div>");
        h.append("<input id=\"q\" type=\"search\" placeholder=\"Filter cases (name, attribute, log text)...\" oninput=\"f(this.value)\">");
        h.append("<label class=\"only\"><input type=\"checkbox\" id=\"fo\" onchange=\"f(document.getElementById('q').value)\"> failing only</label></header>");
        if (setup != null) {
            h.append("<details class=\"setup\"><summary>RDK test class: shared setup <span class=\"muted\">").append(esc(String.valueOf(rdkTestFile)))
             .append("</span></summary><pre><code>").append(esc(setup)).append("</code></pre></details>");
        }
        for (Entry e : entries) {
            TestSuite.Result r = e.r();
            boolean fail = !r.passed() || "FAIL".equals(e.rdkStatus());
            StringBuilder text = new StringBuilder(e.c().name());
            h.append("<details class=\"case").append(fail ? " failing" : "").append("\"").append(fail ? " open" : "").append(" data-fail=\"").append(fail).append("\">");
            h.append("<summary><span class=\"n\">").append(e.n()).append("</span><span class=\"name\">").append(esc(e.c().name())).append("</span>")
             .append(badge("kit", r.passed())).append(e.rdkStatus() == null ? "" : badge("RDK", e.rdkStatus().equals("PASS"))).append("</summary><div class=\"body\">");

            if (!r.failures().isEmpty() || e.rdkMessage() != null && !e.rdkMessage().isBlank()) {
                h.append("<div class=\"fails\">");
                for (String f : r.failures()) h.append("<pre>").append(esc(f)).append("</pre>");
                if (e.rdkMessage() != null && !e.rdkMessage().isBlank()) h.append("<pre><b>RDK:</b> ").append(esc(e.rdkMessage())).append("</pre>");
                h.append("</div>");
            }

            h.append("<h3>What the rule did</h3><ul class=\"ops\">");
            for (String[] op : operations(e.c().inputs(), r.outcome())) { h.append("<li class=\"").append(op[0]).append("\">").append(esc(op[1])).append("</li>"); text.append(' ').append(op[1]); }
            h.append("</ul>");

            h.append("<h3>Log <span class=\"muted\">").append(r.outcome().logs().size()).append(" lines &middot; ").append(r.outcome().ms()).append(" ms</span></h3>");
            if (r.outcome().logs().isEmpty()) h.append("<p class=\"muted\">The rule logged nothing.</p>");
            else {
                h.append("<pre class=\"log\">");
                for (RuleRunner.LogLine l : r.outcome().logs()) {
                    if (l.message().startsWith("@@cov:")) continue;
                    h.append("<span class=\"lv ").append(esc(l.level().toLowerCase())).append("\">").append(esc(String.format("%-5s", l.level()))).append("</span> ").append(esc(l.message())).append('\n');
                    text.append(' ').append(l.message());
                }
                h.append("</pre>");
            }

            if (e.rdkCode() != null) {
                h.append("<h3>RDK test that calls the rule <span class=\"muted\">").append(esc(e.rdkMethod())).append("</span></h3>");
                h.append("<pre class=\"code\"><code>").append(highlightEval(esc(e.rdkCode()))).append("</code></pre>");
                if (e.rdkOutput() != null && !e.rdkOutput().isBlank()) h.append("<details><summary>RDK console output</summary><pre class=\"log\">").append(esc(e.rdkOutput())).append("</pre></details>");
            }
            h.append("<details><summary>Inputs (JSON)</summary><pre class=\"code\"><code>").append(esc(Json.pretty(e.c().inputs()))).append("</code></pre></details>");
            if (r.outcome().changed() != null && !r.outcome().changed().isEmpty())
                h.append("<details><summary>Objects after the rule (JSON)</summary><pre class=\"code\"><code>").append(esc(Json.pretty(Json.toNode(r.outcome().changed())))).append("</code></pre></details>");
            h.append("</div><span class=\"idx\" hidden>").append(esc(text.toString().toLowerCase())).append("</span></details>");
        }
        h.append("</main><script>function f(q){q=q.toLowerCase();var fo=document.getElementById('fo').checked;")
         .append("document.querySelectorAll('details.case').forEach(function(d){var t=d.querySelector('.idx').textContent;")
         .append("d.style.display=(t.indexOf(q)>=0&&(!fo||d.dataset.fail==='true'))?'':'none';});}</script></body></html>\n");
        return h.toString();
    }

    private static String badge(String what, boolean ok) {
        return "<span class=\"badge " + (ok ? "ok" : "bad") + "\">" + what + " " + (ok ? "pass" : "fail") + "</span>";
    }

    private static String highlightEval(String code) {
        return code.replace("i.eval(source)", "<mark>i.eval(source)</mark>");
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root{--bg:#f7f7f5;--card:#fff;--fg:#1d1d1b;--muted:#6b6b66;--line:#e3e2dd;--ok:#1a7f37;--bad:#c62828;--add:#1a7f37;--del:#c62828;--op:#9a6700;--code:#f1f0ec;--mark:#fff1a8}
            @media (prefers-color-scheme:dark){:root{--bg:#161615;--card:#1f1f1d;--fg:#ecebe6;--muted:#9c9b94;--line:#33322f;--ok:#4ac26b;--bad:#ff7b72;--add:#4ac26b;--del:#ff7b72;--op:#d29922;--code:#262624;--mark:#5c4d00}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
            main{max-width:1100px;margin:0 auto;padding:24px 16px 64px}h1{font-size:20px;margin:0}h3{font-size:13px;margin:18px 0 6px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
            .sub,.muted{color:var(--muted);font-weight:normal;text-transform:none;letter-spacing:0}.stats{display:flex;gap:24px;margin:16px 0}.stats div{display:flex;flex-direction:column}
            .stats b{font-size:22px}.stats span{color:var(--muted);font-size:12px}.ok{color:var(--ok)}.bad{color:var(--bad)}
            #q{width:100%;max-width:520px;padding:8px 10px;border:1px solid var(--line);border-radius:6px;background:var(--card);color:var(--fg);font:inherit}.only{margin-left:12px;color:var(--muted)}
            details.case,details.setup{background:var(--card);border:1px solid var(--line);border-radius:8px;margin:10px 0}details.case.failing{border-left:3px solid var(--bad)}
            summary{cursor:pointer;padding:10px 14px;display:flex;gap:10px;align-items:center;list-style:none}summary::-webkit-details-marker{display:none}
            .n{color:var(--muted);min-width:2ch;text-align:right}.name{flex:1;overflow-wrap:anywhere}.badge{font-size:11px;padding:2px 8px;border-radius:10px;border:1px solid currentColor;white-space:nowrap}
            .badge.ok{color:var(--ok)}.badge.bad{color:var(--bad)}.body{padding:0 16px 14px;border-top:1px solid var(--line)}
            pre{background:var(--code);padding:10px 12px;border-radius:6px;overflow-x:auto;margin:6px 0;font:12.5px/1.45 ui-monospace,Consolas,monospace;white-space:pre}
            .fails pre{white-space:pre-wrap;color:var(--bad);margin-top:12px}mark{background:var(--mark);color:inherit;padding:0 2px;border-radius:3px}
            .ops{list-style:none;padding:0;margin:0;font:12.5px/1.6 ui-monospace,Consolas,monospace}.ops li{padding:1px 0;white-space:pre-wrap;overflow-wrap:anywhere}
            .ops .add{color:var(--add)}.ops .del,.ops .err{color:var(--del)}.ops .op{color:var(--op);font-weight:600}.ops .same{color:var(--muted)}.ops .ret{font-weight:600}
            .lv{color:var(--muted)}.lv.error{color:var(--bad)}.lv.warn{color:var(--op)}details details summary{padding:6px 0;color:var(--muted)}
            """;
}
