package com.iscrulekit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.Finding.Severity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ISC Rule Kit command line.
 *
 * <pre>
 *   iscrule validate &lt;rule&gt; [--type T] [--format text|json] [--fail-on error|warn]
 *   iscrule test     &lt;rule&gt; &lt;cases.json&gt; [--type T] [--timeout-ms N] [--format text|json] [--junit FILE] [--strict-mocks] [--verbose]
 *   iscrule check    &lt;rule&gt; &lt;cases.json&gt; ...   (validate + test)
 *   iscrule export   &lt;rule&gt; [--type T] [--name N] [--desc D] [-o FILE]
 *   iscrule scaffold &lt;type&gt;
 *   iscrule types
 * </pre>
 * {@code <rule>} may be {@code -} to read the rule from standard input, so code can simply be piped or pasted in.
 * Exit codes: 0 ok, 1 validation/test failures, 2 usage or I/O error.
 */
public final class Main {
    private Main() {}

    private static final class Opts {
        final List<String> positional = new ArrayList<>();
        String type, format = "text", failOn = "error", junit, out, name, desc;
        long timeoutMs = 10_000;
        boolean strictMocks, verbose, watch;
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out, System.err);
        } catch (UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("Run 'iscrule help' for usage.");
            code = 2;
        } catch (Exception e) {
            System.err.println("error: " + e);
            code = 2;
        }
        System.out.flush();
        System.exit(code); // also kills any runaway rule thread left behind by a timeout
    }

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        UsageException(String m) { super(m); }
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) { usage(out); return args.length == 0 ? 2 : 0; }
        if (args[0].equals("paste")) return run(RunPasted.toRunArgs(java.util.Arrays.copyOfRange(args, 1, args.length)), out, err);
        String cmd = args[0];
        Opts o = parseOpts(args);
        if (o.watch) {
            if (!List.of("validate", "test", "check", "run").contains(cmd)) throw new UsageException("--watch works with validate, test, check and run");
            return watch(cmd, o, out, err);
        }
        return dispatch(cmd, o, out);
    }

    private static int dispatch(String cmd, Opts o, PrintStream out) throws IOException {
        switch (cmd) {
            case "validate": return cmdValidate(o, out);
            case "test": return cmdTest(o, out, false);
            case "check", "run": return cmdTest(o, out, true);
            case "export": return cmdExport(o, out);
            case "scaffold": return cmdScaffold(o, out);
            case "types": return cmdTypes(out);
            default: throw new UsageException("unknown command '" + cmd + "'");
        }
    }

    /** Re-runs the command every time the rule file (or cases file) is saved. Stops on Ctrl+C. */
    private static int watch(String cmd, Opts o, PrintStream out, PrintStream err) {
        if (o.positional.isEmpty() || o.positional.get(0).equals("-")) throw new UsageException("--watch needs a rule file path (not standard input)");
        List<Path> files = new ArrayList<>();
        files.add(Path.of(o.positional.get(0)));
        if (o.positional.size() > 1) files.add(Path.of(o.positional.get(1)));
        long[] seen = stamps(files);
        while (true) {
            out.println("==== " + java.time.LocalTime.now().withNano(0) + "  " + cmd + " " + String.join(" ", o.positional) + " ====");
            try {
                dispatch(cmd, o, out);
            } catch (IOException | RuntimeException e) {
                err.println("error: " + e.getMessage()); // e.g. a half-saved file; keep watching
            }
            out.println();
            out.println("Watching for changes (Ctrl+C to stop)...");
            out.flush();
            while (true) {
                try { Thread.sleep(400); } catch (InterruptedException e) { return 0; }
                if (!java.util.Arrays.equals(seen, stamps(files))) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { return 0; } // let the editor finish writing
                    seen = stamps(files);
                    break;
                }
            }
        }
    }

    private static long[] stamps(List<Path> files) {
        long[] r = new long[files.size()];
        for (int i = 0; i < r.length; i++) {
            try { r[i] = Files.getLastModifiedTime(files.get(i)).toMillis(); } catch (IOException e) { r[i] = -1; }
        }
        return r;
    }

    private static Opts parseOpts(String[] a) {
        Opts o = new Opts();
        for (int i = 1; i < a.length; i++) {
            String s = a[i];
            switch (s) {
                case "--type", "-t" -> o.type = value(a, ++i, s);
                case "--format", "-f" -> o.format = value(a, ++i, s);
                case "--fail-on" -> o.failOn = value(a, ++i, s);
                case "--junit" -> o.junit = value(a, ++i, s);
                case "--out", "-o" -> o.out = value(a, ++i, s);
                case "--name" -> o.name = value(a, ++i, s);
                case "--desc" -> o.desc = value(a, ++i, s);
                case "--timeout-ms" -> {
                    try { o.timeoutMs = Long.parseLong(value(a, ++i, s)); } catch (NumberFormatException e) { throw new UsageException("--timeout-ms needs a number"); }
                }
                case "--strict-mocks" -> o.strictMocks = true;
                case "--watch", "-w" -> o.watch = true;
                case "--verbose", "-v" -> o.verbose = true;
                default -> {
                    if (s.startsWith("--")) throw new UsageException("unknown option " + s);
                    o.positional.add(s);
                }
            }
        }
        if (!List.of("text", "json").contains(o.format)) throw new UsageException("--format must be text or json");
        if (!List.of("error", "warn").contains(o.failOn)) throw new UsageException("--fail-on must be error or warn");
        return o;
    }

    private static String value(String[] a, int i, String opt) {
        if (i >= a.length) throw new UsageException(opt + " needs a value");
        return a[i];
    }

    // ---------------------------------------------------------------------------------------------------------------
    private record Loaded(String label, ParsedRule rule, boolean autoType) {}

    private static Loaded load(Opts o) throws IOException {
        if (o.positional.isEmpty()) throw new UsageException("missing <rule> file (use - for standard input)");
        String path = o.positional.get(0);
        String text = path.equals("-") ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8) : Files.readString(Path.of(path), StandardCharsets.UTF_8);
        RuleType cli = null;
        if (o.type != null) cli = RuleType.fromName(o.type).orElseThrow(() -> new UsageException("unknown rule type '" + o.type + "' (see: iscrule types)"));
        ParsedRule p = RuleParser.parse(text, cli);
        boolean auto = false;
        if (p.type == null && !p.typeFromXml) {
            if (cli != null) p.type = cli;
            else { p.type = RuleType.guess(p.xml ? p.source : text).orElse(null); auto = p.type != null; }
        }
        return new Loaded(path.equals("-") ? "<stdin>" : path, p, auto);
    }

    private static int cmdValidate(Opts o, PrintStream out) throws IOException {
        Loaded l = load(o);
        List<Finding> f = Validator.validate(l.rule());
        emitFindings(o, out, l, f);
        return failed(f, o.failOn) ? 1 : 0;
    }

    private static void emitFindings(Opts o, PrintStream out, Loaded l, List<Finding> f) {
        if (o.format.equals("json")) out.println(Json.pretty(Report.findingsJson(l.label(), l.rule(), f)));
        else Report.printFindings(out, l.label(), l.rule(), l.autoType(), f);
    }

    private static boolean failed(List<Finding> f, String failOn) {
        return Report.count(f, Severity.ERROR) > 0 || (failOn.equals("warn") && Report.count(f, Severity.WARN) > 0);
    }

    private static int cmdTest(Opts o, PrintStream out, boolean alsoValidate) throws IOException {
        Loaded l = load(o);
        List<TestSuite.Case> cases;
        boolean smoke = o.positional.size() < 2;
        if (smoke) {
            if (l.rule().type == null) throw new UsageException("no cases file given and the rule type is unknown: pass --type, or a cases file");
            cases = TestSuite.defaults(l.rule().type);
        } else {
            try {
                cases = TestSuite.load(Path.of(o.positional.get(1)));
            } catch (IOException | RuntimeException e) {
                throw new UsageException("cannot read test cases: " + e.getMessage());
            }
        }
        boolean json = o.format.equals("json");
        int exit = 0;
        List<Finding> findings = null;
        if (alsoValidate) {
            findings = Validator.validate(l.rule());
            if (!json) { emitFindings(o, out, l, findings); out.println(); }
            if (failed(findings, o.failOn)) exit = 1;
        }
        if (l.rule().type == null && !json) out.println("note: rule type unknown; pass --type for accurate input handling.");
        if (smoke && !json) {
            out.println("note: no cases file given. Smoke run with default " + l.rule().type.xmlName + " inputs; there are no assertions, so this only");
            out.println("      catches crashes, missing methods and null errors. Pass a cases file (iscrule scaffold " + l.rule().type.xmlName + ") to check results.");
            out.println();
        }

        List<TestSuite.Result> results = new ArrayList<>();
        for (TestSuite.Case c : cases) {
            RuleRunner.Outcome oc;
            try {
                oc = RuleRunner.run(l.rule(), c.inputs(), o.timeoutMs);
            } catch (RuntimeException e) { // bad fixture, e.g. invalid enum value
                oc = new RuleRunner.Outcome(false, null, false, "void", List.of(), List.of(), java.util.Map.of(),
                        new RuleRunner.ErrorInfo(e.getClass().getSimpleName(), String.valueOf(e.getMessage()), null, "Check the test case inputs."), 0);
            }
            TestSuite.Result r = TestSuite.evaluate(c, oc, o.strictMocks);
            results.add(r);
            if (!r.passed()) exit = 1;
            if (!json) Report.printResult(out, r, o.verbose);
        }
        long pass = results.stream().filter(TestSuite.Result::passed).count();
        if (json) {
            ObjectNode root = Json.MAPPER.createObjectNode();
            if (findings != null) root.set("validation", Report.findingsJson(l.label(), l.rule(), findings));
            root.set("results", Report.resultsJson(results));
            ObjectNode s = root.putObject("summary");
            s.put("total", results.size());
            s.put("passed", pass);
            s.put("failed", results.size() - pass);
            out.println(Json.pretty(root));
        } else {
            out.println();
            out.printf("%d of %d test case(s) passed%n", pass, results.size());
        }
        if (o.junit != null) Files.writeString(Path.of(o.junit), Report.junit(l.label(), results), StandardCharsets.UTF_8);
        return exit;
    }

    private static int cmdExport(Opts o, PrintStream out) throws IOException {
        Loaded l = load(o);
        ParsedRule p = l.rule();
        ClassUnwrapper.Result u = ClassUnwrapper.unwrap(p.source, p.type == null ? List.of() : p.type.inputs);
        String ruleName = o.name != null ? o.name : !p.name.isEmpty() ? p.name : u.className().isEmpty() ? "My Rule" : u.className();
        String xml = RuleXml.build(ruleName, p.type, o.desc != null ? o.desc : p.description, ClassUnwrapper.tidy(u.source()));
        if (o.out != null) {
            Files.writeString(Path.of(o.out), xml, StandardCharsets.UTF_8);
            out.println("Wrote " + o.out);
            if (!u.removed().isEmpty() || u.wrapped())
                out.println("Stripped " + (u.wrapped() ? "class wrapper '" + u.className() + "'" : "") + (u.removed().isEmpty() ? "" : " and " + u.removed().size() + " re-declared input(s)") + ".");
            out.println("Suggested file name: " + RuleXml.fileName(p.type, ruleName));
        } else {
            out.print(xml);
        }
        return 0;
    }

    private static int cmdScaffold(Opts o, PrintStream out) {
        if (o.positional.isEmpty()) throw new UsageException("usage: scaffold <type>   (see: iscrule types)");
        RuleType t = RuleType.fromName(o.positional.get(0)).orElseThrow(() -> new UsageException("unknown rule type '" + o.positional.get(0) + "'"));
        out.print(Scaffold.cases(t));
        return 0;
    }

    private static int cmdTypes(PrintStream out) {
        for (RuleType t : RuleType.values()) {
            out.printf("%-32s %s%n    inputs : %s%n    returns: %s%n", t.xmlName, t.label, String.join(", ", t.inputs),
                    t.returns == null ? "nothing (modifies plan in place)" : t.returns);
        }
        return 0;
    }

    private static void usage(PrintStream out) {
        out.println("""
                ISC Rule Kit: validate and test SailPoint Identity Security Cloud rules (BeanShell) from the command line.

                USAGE
                  iscrule validate <rule> [--type T] [--format text|json] [--fail-on error|warn]
                  iscrule test     <rule> <cases.json> [--type T] [--timeout-ms N] [--format text|json]
                                   [--junit FILE] [--strict-mocks] [--verbose]
                  iscrule check    <rule> <cases.json> [same options]     validate, then test
                  iscrule run      <rule> [cases.json] [same options]     validate + test; with no cases file, a smoke run
                                                                          using default inputs for the rule type
                  iscrule paste    [same options]                         run paste-here/Rule.java (+ Rule.cases.json if present)
                  (validate/test/check/run/paste accept --watch to re-run whenever the file is saved)
                  iscrule export   <rule> [--type T] [--name N] [--desc D] [-o FILE]   produce ISC-ready rule XML
                  iscrule scaffold <type>                                 print a starter cases.json
                  iscrule types                                           list rule types and their inputs

                <rule> is a rule XML file, a raw BeanShell/Java file (RDK class style is fine), or '-' to read stdin.
                Exit codes: 0 ok, 1 validation or test failures, 2 usage/I-O error.""");
    }
}
