package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.CaseBuilder.AttrReq;
import com.iscrulekit.CaseBuilder.Choice;
import com.iscrulekit.RuleAnalyzer.Analysis;
import com.iscrulekit.RuleAnalyzer.Kind;
import com.iscrulekit.RuleAnalyzer.Slot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code iscrule add-test}: asks what you want to test (suggesting the values the rule actually checks), runs the rule,
 * shows what happened, asks whether that is right, and saves the case. {@code iscrule gen}: writes variations
 * automatically. {@code iscrule analyze}: prints what the rule depends on.
 */
final class CaseWizard {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final Ask ask;
    private final PrintStream out;
    private final long timeoutMs;

    CaseWizard(BufferedReader in, PrintStream out, long timeoutMs) {
        this.ask = new Ask(in, out);
        this.out = out;
        this.timeoutMs = timeoutMs;
    }

    static BufferedReader stdin() { return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)); }

    // ===============================================================================================================
    // analyze
    static void printAnalysis(PrintStream out, String label, ParsedRule rule, Analysis a) {
        Report.printHeader(out, label, rule, false);
        out.println();
        if (!a.sourceNames.isEmpty()) out.println(Ansi.bold("Source names              ") + "  " + String.join(", ", a.sourceNames));
        if (a.type == RuleType.BEFORE_PROVISIONING) {
            out.println(Ansi.bold("Account operations checked") + "  " + (a.operations.isEmpty() ? "none" : String.join(", ", a.operations)));
            out.println(Ansi.bold("Attribute operations      ") + "  " + (a.attrOperations.isEmpty() ? "none" : String.join(", ", a.attrOperations)));
            out.println();
        }
        out.println(Ansi.bold("DECIDES ON") + Ansi.dim("  (inputs compared against specific values, or needed to run)"));
        for (Slot s : a.important()) out.println("  " + pad(s.label(), 52) + " " + Ansi.cyan(describeValues(s)));
        if (a.important().isEmpty()) out.println("  nothing found");
        if (!a.others().isEmpty()) {
            out.println(Ansi.bold("ALSO READS") + Ansi.dim("  (no specific values checked)"));
            for (Slot s : a.others()) out.println("  " + s.label());
        }
    }

    private static String describeValues(Slot s) {
        if (s.bool) return "true / false";
        if (s.number) return "a number";
        List<String> v = new ArrayList<>();
        for (String x : s.values) v.add(x.isEmpty() ? "(empty)" : "\"" + x + "\"");
        String kind = s.list ? "list containing " : "";
        List<String> all = new ArrayList<>();
        if (!v.isEmpty()) all.add(kind + String.join(", ", v));
        all.addAll(s.patterns);
        return all.isEmpty() ? "any value" : String.join("; ", all);
    }

    // ===============================================================================================================
    // add-test
    int addTests(Path rulePath, Path casesPath, RuleLoader.Loaded loaded) throws IOException {
        ParsedRule rule = loaded.rule();
        if (rule.type == null) throw new Main.UsageException("could not tell the rule type; pass --type (see: iscrule types)");
        Analysis a = RuleAnalyzer.analyze(rule);

        out.println();
        out.println(Ansi.bold("NEW TEST CASE") + "  " + rulePath + Ansi.dim("  (" + rule.type.xmlName + ")"));
        out.println(Ansi.dim("Answer each question. Press Enter to take the [default], type a number to pick a suggestion,"));
        out.println(Ansi.dim("or type your own value. Suggestions come from the values your rule compares against."));
        int saved = 0;
        while (true) {
            Choice c = askInputs(a);
            ObjectNode inputs = CaseBuilder.inputs(a, c);
            TestSuite.Case tc = new TestSuite.Case("draft", inputs, null, false, false, null, null, List.of());
            RuleRunner.Outcome o = TestSuite.runCase(rule, tc, timeoutMs, false).outcome();

            out.println();
            out.println(Ansi.bold("RESULT OF THE RUN"));
            showOutcome(inputs, o);

            ObjectNode testCase = F.objectNode();
            testCase.put("name", "");
            testCase.set("inputs", inputs);
            out.println();
            String verdict = ask.choose("Is this what the rule should do?",
                    List.of("yes - save it as the expected result", "no - I will say what should happen", "save the inputs only, no check"), "1", false);
            if (verdict.startsWith("yes")) CaseBuilder.record(testCase, rule.type, o);
            else if (verdict.startsWith("no")) describeExpected(testCase, rule.type, o);

            String name = ask.text("Name for this test case", defaultName(a, c));
            testCase.put("name", name);
            CasesFile.append(casesPath, testCase);
            saved++;
            out.println(Ansi.green("Saved") + " \"" + name + "\" to " + casesPath);
            if (!ask.yes("Add another test case?", false)) break;
        }
        if (saved > 0 && ask.yes("Run all test cases in " + casesPath.getFileName() + " now?", true)) return -1; // caller runs them
        return 0;
    }

    private Choice askInputs(Analysis a) {
        Choice c = new Choice();
        if (!a.sourceNames.isEmpty()) c.plan.applicationName = a.sourceNames.iterator().next();

        if (a.type == RuleType.BEFORE_PROVISIONING) {
            out.println();
            out.println(Ansi.bold("The account request"));
            List<String> ops = new ArrayList<>(a.operations);
            for (String op : List.of("Modify", "Create", "Enable", "Disable", "Delete")) if (!ops.contains(op)) ops.add(op);
            c.plan.operation = ask.choose("Operation", ops, a.ofKind(Kind.ATTR_REQUEST).isEmpty() ? ops.get(0) : "Modify", false);
            c.plan.applicationName = ask.choose("Source (application) name", new ArrayList<>(orDefault(a.sourceNames, c.plan.applicationName)),
                    c.plan.applicationName, true);
            c.plan.nativeIdentity = ask.text("Account native identity", c.plan.nativeIdentity);
            askAttributeRequests(a, c);
        } else if (a.type == RuleType.ATTRIBUTE_GENERATOR || a.type == RuleType.ATTRIBUTE_GENERATOR_FROM_TEMPLATE) {
            c.fieldName = ask.text("Account attribute being generated (field name)", c.fieldName);
        } else if (a.type == RuleType.MANAGER_CORRELATION || a.type == RuleType.CORRELATION) {
            c.plan.applicationName = ask.text("Source (application) name", c.plan.applicationName);
        }

        List<Slot> important = new ArrayList<>(a.important());
        important.removeIf(s -> s.kind == Kind.ATTR_REQUEST);
        if (!important.isEmpty()) {
            out.println();
            out.println(Ansi.bold("Values the rule decides on"));
        }
        for (Slot s : important) c.values.put(s.id(), askSlot(s, CaseBuilder.defaultFor(s)));

        List<Slot> others = a.others();
        if (!others.isEmpty()) {
            out.println();
            List<String> names = new ArrayList<>();
            for (Slot s : others) names.add(s.label());
            for (String l : Report.wrap("The rule also reads: " + String.join(", ", names), Report.WIDTH)) out.println(Ansi.dim(l));
            if (ask.yes("Set any of these? (otherwise they are left empty)", false)) {
                for (Slot s : others) {
                    String v = ask.text(s.label() + Ansi.dim(" (Enter = leave empty)"), "");
                    if (!v.isEmpty()) c.values.put(s.id(), CaseBuilder.typed(s, v));
                }
            }
        }
        return c;
    }

    private static Set<String> orDefault(Set<String> s, String d) {
        Set<String> r = new LinkedHashSet<>(s);
        if (r.isEmpty()) r.add(d);
        return r;
    }

    private void askAttributeRequests(Analysis a, Choice c) {
        List<Slot> checked = a.ofKind(Kind.ATTR_REQUEST);
        List<String> names = new ArrayList<>();
        for (Slot s : checked) names.add(s.key);
        String hint = names.isEmpty() ? "" : " The rule looks at: " + String.join(", ", names) + ".";
        out.println(Ansi.dim("Attribute changes in the request (e.g. a group being added)." + hint));
        boolean first = true;
        while (ask.yes(first ? "Add an attribute change to the request?" : "Add another attribute change?", first && !names.isEmpty())) {
            first = false;
            List<String> nameChoices = new ArrayList<>(names);
            String name = ask.choose("  Attribute name", nameChoices.isEmpty() ? List.of("memberOf") : nameChoices,
                    nameChoices.isEmpty() ? "memberOf" : nameChoices.get(0), true);
            List<String> ops = new ArrayList<>(List.of("Add", "Remove", "Set"));
            for (String op : a.attrOperations) if (!ops.contains(op)) ops.add(op);
            String op = ask.choose("  Operation", ops, "Add", false);
            Slot s = checked.stream().filter(x -> x.key.equals(name)).findFirst().orElse(null);
            List<String> vals = s == null ? List.of() : new ArrayList<>(s.values);
            String raw = vals.isEmpty() ? ask.text("  Value (several values: separate with ;)", "")
                    : ask.choose("  Value (several values: separate with ;)", vals, vals.get(0), true);
            List<String> parts = new ArrayList<>(List.of(raw.split("\\s*;\\s*")));
            JsonNode value;
            if (parts.size() > 1) {
                ArrayNode arr = F.arrayNode();
                parts.forEach(arr::add);
                value = arr;
            } else value = F.textNode(raw);
            c.plan.attrs.add(new AttrReq(name, op, value));
        }
    }

    private JsonNode askSlot(Slot s, JsonNode dflt) {
        String d = dflt == null || dflt.isNull() ? "" : dflt.isArray() ? join(dflt) : dflt.asText();
        List<String> opts = new ArrayList<>();
        if (s.bool) opts = List.of("true", "false");
        else {
            for (String v : s.values) opts.add(v);
            for (String v : s.patternValues) if (!opts.contains(v)) opts.add(v);
        }
        String label = s.label() + (s.list ? Ansi.dim(" (a list: separate values with ;)") : "");
        String answer = opts.isEmpty() ? ask.text(label, d) : ask.choose(label, opts, d, true);
        return CaseBuilder.typed(s, answer);
    }

    private static String join(JsonNode arr) {
        List<String> p = new ArrayList<>();
        arr.forEach(x -> p.add(x.asText()));
        return String.join(";", p);
    }

    private void showOutcome(ObjectNode inputs, RuleRunner.Outcome o) {
        if (!o.ok()) {
            out.println("  " + Ansi.red("The rule threw " + o.error().type()) + (o.error().line() != null ? " at line " + o.error().line() : ""));
            for (String l : Report.wrap(o.error().message(), Report.WIDTH - 4)) out.println("    " + l);
            if (o.error().hint() != null) for (String l : Report.wrap(o.error().hint(), Report.WIDTH - 4)) out.println("    " + Ansi.cyan(l));
        } else if (!o.voidResult() && (o.result() != null || !inputs.has("plan"))) {
            out.println("  Returned  " + Ansi.bold(Diff.show(Json.toNode(o.result()), true)));
        }
        List<String> changes = new ArrayList<>();
        o.changed().forEach((k, v) -> changes.addAll(Diff.changes(inputs.get(k), Json.toNode(v), k, true)));
        changes.removeIf(l -> l.contains(".arguments"));
        if (!changes.isEmpty()) {
            out.println("  What the rule changed");
            for (String ch : changes) {
                String colored = ch.startsWith("+") ? Ansi.green(ch) : ch.startsWith("-") ? Ansi.red(ch) : ch;
                for (String l : Report.wrap(colored, Report.WIDTH - 4)) out.println("    " + l);
            }
        } else if (o.ok() && o.voidResult()) out.println("  The rule changed nothing.");
        if (!o.logs().isEmpty()) {
            out.println("  Log (" + o.logs().size() + " lines)");
            int n = 0;
            for (RuleRunner.LogLine l : o.logs()) {
                if (n++ == 15) { out.println(Ansi.dim("    ... " + (o.logs().size() - 15) + " more")); break; }
                out.println(Ansi.dim("    " + pad(l.level(), 5) + " " + l.message()));
            }
        }
        for (String n : o.notes()) if (n.startsWith("NOT MOCKED")) out.println("  " + Ansi.yellow(n));
    }

    private void describeExpected(ObjectNode testCase, RuleType type, RuleRunner.Outcome o) {
        if (ask.yes("Should the rule throw an error?", false)) {
            String txt = ask.text("Text the error should contain (Enter = any error)", "");
            if (txt.isEmpty()) testCase.put("expectError", true);
            else testCase.put("expectError", txt);
            return;
        }
        if (type == RuleType.BEFORE_PROVISIONING) {
            JsonNode plan = o.ok() && o.changed().get("plan") != null ? Json.toNode(o.changed().get("plan")) : testCase.path("inputs").path("plan");
            String op = plan.path("accountRequests").path(0).path("operation").asText("Modify");
            String want = ask.choose("Operation the account request should end with", List.of("Modify", "Create", "Enable", "Disable", "Delete"), op, false);
            out.println(Ansi.dim("List the attribute changes the request should end with, one per line, as"));
            out.println(Ansi.dim("  name            any value         e.g.  password"));
            out.println(Ansi.dim("  name=value      exact value       e.g.  AC_NewParent=OU=Disabled,DC=example,DC=org"));
            out.println(Ansi.dim("  name Op=value   with operation    e.g.  memberOf Remove=CN=VPN,OU=Groups,DC=example,DC=org"));
            out.println(Ansi.dim("Empty line to finish. The list must be complete (every change, in order)."));
            ObjectNode exp = F.objectNode();
            ObjectNode req = exp.putObject("plan").putArray("accountRequests").addObject();
            req.put("operation", want);
            ArrayNode ars = req.putArray("attributeRequests");
            while (true) {
                String line = ask.text("  change", "");
                if (line.isBlank()) break;
                ObjectNode ar = ars.addObject();
                String left = line, value = null;
                int eq = line.indexOf('=');
                if (eq > 0) { left = line.substring(0, eq).trim(); value = line.substring(eq + 1).trim(); }
                String[] np = left.trim().split("\\s+");
                ar.put("name", np[0]);
                if (np.length > 1) ar.put("operation", np[1]);
                if (value != null) ar.put("value", value);
            }
            testCase.set("expectChanged", exp);
        } else {
            String v = ask.text("What should the rule return? (text, JSON like {\"a\":\"b\"}, or null)", "");
            JsonNode node;
            try { node = v.trim().startsWith("{") || v.trim().startsWith("[") ? Json.MAPPER.readTree(v) : v.equals("null") ? F.nullNode() : F.textNode(v); }
            catch (IOException e) { node = F.textNode(v); }
            testCase.set("expect", node);
        }
        String log = ask.text("Text a log line should contain (Enter = skip)", "");
        if (!log.isEmpty()) testCase.putArray("expectLogContains").add(log);
    }

    /** Name built from the request (BeforeProvisioning) plus every value that differs from the defaults. */
    static String defaultName(Analysis a, Choice c) {
        Choice defaults = new Choice();
        for (Slot s : a.important()) if (s.kind != Kind.ATTR_REQUEST) defaults.values.put(s.id(), CaseBuilder.defaultFor(s));
        String n = describe(a, c, defaults, true);
        return n.isEmpty() ? "default values" : n;
    }

    /** "Modify memberOf Add X, brb=true": the request (when asked) and the slot values that differ from {@code base}. */
    static String describe(Analysis a, Choice c, Choice base, boolean withRequest) {
        List<String> parts = new ArrayList<>();
        if (a.type == RuleType.BEFORE_PROVISIONING && withRequest) {
            StringBuilder b = new StringBuilder(c.plan.operation);
            for (AttrReq r : c.plan.attrs) b.append(" ").append(r.name()).append(" ").append(r.op()).append(" ").append(CaseBuilder.brief(r.value()));
            parts.add(b.toString());
        }
        for (Slot s : a.important()) {
            if (s.kind == Kind.ATTR_REQUEST) continue;
            JsonNode v = c.values.get(s.id());
            if (v == null || v.equals(base.values.get(s.id()))) continue;
            parts.add((s.kind == Kind.IDN ? (s.key.isEmpty() ? s.method : s.key) : s.key) + "=" + CaseBuilder.brief(v));
        }
        String n = String.join(", ", parts);
        return n.length() > 140 ? n.substring(0, 137) + "..." : n;
    }

    /** What a variant changes compared with the baseline request. */
    private static String requestDiff(Choice c, Choice base) {
        if (!c.plan.operation.equals(base.plan.operation)) return "operation " + c.plan.operation;
        List<String> d = new ArrayList<>();
        for (int i = 0; i < c.plan.attrs.size(); i++) {
            AttrReq r = c.plan.attrs.get(i), b = i < base.plan.attrs.size() ? base.plan.attrs.get(i) : null;
            if (b == null || !r.op().equals(b.op())) d.add(r.name() + " " + r.op());
            if (b == null || !r.value().equals(b.value())) d.add(r.name() + " value " + CaseBuilder.brief(r.value()));
        }
        return String.join(", ", d);
    }

    private static String pad(String s, int w) { return s.length() >= w ? s : s + " ".repeat(w - s.length()); }

    // ===============================================================================================================
    // gen
    /** Writes a baseline case plus one case per alternative value of each deciding input. Returns how many were added. */
    static int generate(ParsedRule rule, Path casesPath, boolean record, long timeoutMs, int max, PrintStream out) throws IOException {
        if (rule.type == null) throw new Main.UsageException("could not tell the rule type; pass --type (see: iscrule types)");
        Analysis a = RuleAnalyzer.analyze(rule);
        Choice base = new Choice();
        if (!a.sourceNames.isEmpty()) base.plan.applicationName = a.sourceNames.iterator().next();
        List<Slot> deciding = new ArrayList<>(a.important());
        deciding.removeIf(s -> s.kind == Kind.ATTR_REQUEST);
        for (Slot s : deciding) base.values.put(s.id(), CaseBuilder.defaultFor(s));
        List<Slot> attrSlots = a.ofKind(Kind.ATTR_REQUEST);
        List<String> attrOps = new ArrayList<>();
        for (String op : List.of("Add", "Remove", "Set")) if (a.attrOperations.contains(op)) attrOps.add(op);
        if (attrOps.isEmpty()) attrOps.add("Add");
        if (a.type == RuleType.BEFORE_PROVISIONING) {
            base.plan.operation = a.operations.isEmpty() || (a.operations.contains("Modify") && !attrSlots.isEmpty())
                    ? "Modify" : a.operations.iterator().next();
            for (Slot s : attrSlots) {
                String v = s.values.isEmpty() ? "test" : s.values.iterator().next();
                base.plan.attrs.add(new AttrReq(s.key, attrOps.get(0), F.textNode(v)));
            }
        }

        List<Choice> variants = new ArrayList<>();
        variants.add(base);
        if (a.type == RuleType.BEFORE_PROVISIONING) {
            for (String op : a.operations) if (!op.equals(base.plan.operation)) variants.add(withOperation(base, op));
            for (int i = 0; i < base.plan.attrs.size(); i++) {
                AttrReq r = base.plan.attrs.get(i);
                for (String op : attrOps) if (!op.equals(r.op())) variants.add(withAttr(base, i, new AttrReq(r.name(), op, r.value())));
                Slot s = attrSlots.get(i);
                for (String v : s.values) if (!v.equals(r.value().asText())) variants.add(withAttr(base, i, new AttrReq(r.name(), r.op(), F.textNode(v))));
            }
        }
        for (Slot s : deciding) {
            List<String> alts = new ArrayList<>(s.values);
            for (String v : s.patternValues) if (!alts.contains(v)) alts.add(v);
            if (s.bool) alts = List.of("true", "false");
            for (String v : alts) {
                JsonNode jv = CaseBuilder.typed(s, s.list ? v : v);
                if (jv.equals(base.values.get(s.id()))) continue;
                Choice c = copy(base);
                c.values.put(s.id(), jv);
                variants.add(c);
            }
        }
        if (variants.size() > max) {
            out.println(Ansi.yellow("Limiting to " + max + " of " + variants.size() + " cases (use --max N for more)."));
            variants = variants.subList(0, max);
        }

        Set<String> existing = CasesFile.names(casesPath);
        int added = 0;
        for (int i = 0; i < variants.size(); i++) {
            Choice c = variants.get(i);
            String name;
            if (i == 0) name = "baseline: " + defaultName(a, c);
            else {
                String req = requestDiff(c, base), vals = describe(a, c, base, false);
                name = String.join(", ", List.of(req, vals).stream().filter(x -> !x.isEmpty()).toList());
                if (name.isEmpty()) name = "variant " + i;
            }
            if (!existing.add(name)) continue;
            ObjectNode inputs = CaseBuilder.inputs(a, c);
            ObjectNode tc = F.objectNode();
            tc.put("name", record ? name + "  [recorded - review]" : name);
            tc.set("inputs", inputs);
            if (record) {
                RuleRunner.Outcome o = TestSuite.runCase(rule, new TestSuite.Case(name, inputs, null, false, false, null, null, List.of()), timeoutMs, false).outcome();
                CaseBuilder.record(tc, rule.type, o);
            }
            CasesFile.append(casesPath, tc);
            added++;
        }
        return added;
    }

    private static Choice copy(Choice b) {
        Choice c = new Choice();
        c.values.putAll(b.values);
        c.fieldName = b.fieldName;
        c.plan.operation = b.plan.operation;
        c.plan.applicationName = b.plan.applicationName;
        c.plan.nativeIdentity = b.plan.nativeIdentity;
        c.plan.attrs.addAll(b.plan.attrs);
        return c;
    }

    private static Choice withOperation(Choice b, String op) {
        Choice c = copy(b);
        c.plan.operation = op;
        c.plan.attrs.clear();
        return c;
    }

    private static Choice withAttr(Choice b, int i, AttrReq r) {
        Choice c = copy(b);
        c.plan.attrs.set(i, r);
        return c;
    }

    // ===============================================================================================================
    /** Line-based prompts that also work when answers are piped in (EOF means "take the default"). */
    static final class Ask {
        private final BufferedReader in;
        private final PrintStream out;

        Ask(BufferedReader in, PrintStream out) { this.in = in; this.out = out; }

        String line() {
            try {
                String s = in.readLine();
                return s == null ? "" : s.replace("﻿", "").trim(); // PowerShell pipes start with a BOM
            } catch (IOException e) {
                return "";
            }
        }

        String text(String q, String dflt) {
            out.print(q + (dflt == null || dflt.isEmpty() ? "" : " " + Ansi.dim("[" + shorten(dflt) + "]")) + ": ");
            out.flush();
            String s = line();
            return s.isEmpty() ? (dflt == null ? "" : dflt) : s;
        }

        boolean yes(String q, boolean dflt) {
            out.print(q + " " + Ansi.dim(dflt ? "[Y/n]" : "[y/N]") + ": ");
            out.flush();
            String s = line().toLowerCase();
            return s.isEmpty() ? dflt : s.startsWith("y");
        }

        /** Numbered list; Enter takes the default (an option text, or "1"), a number picks, other text is taken as typed. */
        String choose(String q, List<String> options, String dflt, boolean allowCustom) {
            out.println(q);
            for (int i = 0; i < options.size(); i++) {
                String o = options.get(i);
                out.println("   " + Ansi.cyan(String.valueOf(i + 1)) + ") " + (o.isEmpty() ? Ansi.dim("(empty)") : o));
            }
            String d = dflt;
            if (d != null && d.matches("\\d+") && !options.contains(d)) {
                int k = Integer.parseInt(d) - 1;
                d = k >= 0 && k < options.size() ? options.get(k) : options.get(0);
            }
            out.print("   " + (allowCustom ? "number or your own value" : "number") + (d == null ? "" : " " + Ansi.dim("[" + shorten(d.isEmpty() ? "(empty)" : d) + "]")) + ": ");
            out.flush();
            String s = line();
            if (s.isEmpty()) return d == null ? "" : d;
            if (s.matches("\\d+")) {
                int k = Integer.parseInt(s) - 1;
                if (k >= 0 && k < options.size()) return options.get(k);
            }
            for (String o : options) if (o.equalsIgnoreCase(s)) return o;
            if (allowCustom && s.contains(";")) { // a list: numbers inside it pick suggestions too
                List<String> parts = new ArrayList<>();
                for (String part : s.split("\\s*;\\s*")) {
                    int k = part.matches("\\d+") ? Integer.parseInt(part) - 1 : -1;
                    parts.add(k >= 0 && k < options.size() ? options.get(k) : part);
                }
                return String.join(";", parts);
            }
            if (!allowCustom) {
                out.println("   " + Ansi.yellow("'" + s + "' is not one of the choices; using " + (d == null ? options.get(0) : d)));
                return d == null ? options.get(0) : d;
            }
            return s.equals("\"\"") ? "" : s;
        }

        private static String shorten(String s) { return s.length() > 70 ? s.substring(0, 67) + "..." : s; }
    }

    // ===============================================================================================================
    /** Appends cases to a cases file without reformatting what is already there. */
    static final class CasesFile {
        private CasesFile() {}

        static Set<String> names(Path p) {
            Set<String> s = new LinkedHashSet<>();
            if (!Files.exists(p)) return s;
            try {
                for (TestSuite.Case c : TestSuite.load(p)) s.add(c.name());
            } catch (IOException | RuntimeException e) {
                // unreadable file: append will report it
            }
            return s;
        }

        static void append(Path p, ObjectNode testCase) throws IOException {
            String caseJson = indent(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(testCase), "    ");
            if (!Files.exists(p) || Files.readString(p, StandardCharsets.UTF_8).isBlank()) {
                if (p.toAbsolutePath().getParent() != null) Files.createDirectories(p.toAbsolutePath().getParent());
                Files.writeString(p, "{\n  \"cases\": [\n" + caseJson + "\n  ]\n}\n", StandardCharsets.UTF_8);
                return;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            JsonNode root;
            try {
                root = Json.MAPPER.readTree(text.startsWith("﻿") ? text.substring(1) : text);
            } catch (IOException e) {
                throw new Main.UsageException(p + " is not valid JSON, so the new case was not added: " + e.getMessage());
            }
            if (root == null || !root.isObject() || !root.has("cases") || !root.get("cases").isArray()) {
                // other accepted shapes: rewrite as { "cases": [...] }
                ObjectNode fresh = F.objectNode();
                ArrayNode arr = fresh.putArray("cases");
                if (root != null && root.isArray()) root.forEach(arr::add);
                else if (root != null && root.isObject()) arr.add(root);
                arr.add(testCase);
                Files.writeString(p, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(fresh) + "\n", StandardCharsets.UTF_8);
                return;
            }
            int close = closingBracketOfCases(text);
            if (close < 0) throw new Main.UsageException("could not find the end of the \"cases\" list in " + p);
            String before = text.substring(0, close).replaceAll("\\s+$", "");
            boolean empty = before.endsWith("[");
            String updated = before + (empty ? "\n" : ",\n") + caseJson + "\n  " + text.substring(close);
            Files.writeString(p, updated, StandardCharsets.UTF_8);
        }

        /** Index of the ']' that closes the top-level "cases" array (string-aware bracket matching). */
        private static int closingBracketOfCases(String t) {
            int key = t.indexOf("\"cases\"");
            if (key < 0) return -1;
            int open = t.indexOf('[', key);
            int depth = 0;
            for (int i = open; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch == '"') {
                    for (i++; i < t.length() && t.charAt(i) != '"'; i++) if (t.charAt(i) == '\\') i++;
                    continue;
                }
                if (ch == '[' || ch == '{') depth++;
                else if ((ch == ']' || ch == '}') && --depth == 0) return i;
            }
            return -1;
        }

        private static String indent(String s, String pad) {
            return pad + s.replace("\r\n", "\n").replace("\n", "\n" + pad);
        }
    }
}
