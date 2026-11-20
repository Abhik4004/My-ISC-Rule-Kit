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
        int max = 40;
        boolean strictMocks, verbose, watch, record;
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
        if (cmd.equals("suite") && !hasOpt(args, "--timeout-ms")) o.timeoutMs = 3_000; // a stuck rule should not stall the whole suite
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
            case "suite": return cmdSuite(o, out);
            case "analyze": return cmdAnalyze(o, out);
            case "add-test", "addtest", "wizard": return cmdAddTest(o, out);
            case "gen", "generate": return cmdGen(o, out);
            case "new": return cmdNew(o, out);
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
                case "--record" -> o.record = true;
                case "--dir" -> o.out = value(a, ++i, s);
                case "--max" -> {
                    try { o.max = Integer.parseInt(value(a, ++i, s)); } catch (NumberFormatException e) { throw new UsageException("--max needs a number"); }
                }
                case "--watch", "-w" -> o.watch = true;
                case "--verbose", "-v" -> o.verbose = true;
                case "--color" -> Ansi.on = true;
                case "--no-color" -> Ansi.on = false;
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

    private static boolean hasOpt(String[] a, String opt) {
        for (String s : a) if (s.equals(opt)) return true;
        return false;
    }

    private static String value(String[] a, int i, String opt) {
        if (i >= a.length) throw new UsageException(opt + " needs a value");
        return a[i];
    }

    // ---------------------------------------------------------------------------------------------------------------
    private static RuleLoader.Loaded load(Opts o) throws IOException {
        if (o.positional.isEmpty()) throw new UsageException("missing <rule> file (use - for standard input)");
        String path = o.positional.get(0);
        RuleType cli = null;
        if (o.type != null) cli = RuleType.fromName(o.type).orElseThrow(() -> new UsageException("unknown rule type '" + o.type + "' (see: iscrule types)"));
        if (path.equals("-")) return RuleLoader.fromText("<stdin>", new String(System.in.readAllBytes(), StandardCharsets.UTF_8), cli, null);
        return RuleLoader.fromFile(Path.of(path), cli);
    }

    private static int cmdValidate(Opts o, PrintStream out) throws IOException {
        RuleLoader.Loaded l = load(o);
        List<Finding> f = Validator.validate(l.rule());
        emitFindings(o, out, l, f);
        return failed(f, o.failOn) ? 1 : 0;
    }

    private static void emitFindings(Opts o, PrintStream out, RuleLoader.Loaded l, List<Finding> f) {
        if (o.format.equals("json")) out.println(Json.pretty(Report.findingsJson(l.label(), l.rule(), f)));
        else Report.printFindings(out, l.label(), l.rule(), l.autoType(), f, o.verbose);
    }

    private static boolean failed(List<Finding> f, String failOn) {
        return Report.count(f, Severity.ERROR) > 0 || (failOn.equals("warn") && Report.count(f, Severity.WARN) > 0);
    }

    private static int cmdTest(Opts o, PrintStream out, boolean alsoValidate) throws IOException {
        RuleLoader.Loaded l = load(o);
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
            if (!json) emitFindings(o, out, l, findings);
            if (failed(findings, o.failOn)) exit = 1;
        } else if (!json) {
            Report.printHeader(out, l.label(), l.rule(), l.autoType());
        }
        if (l.rule().type == null && !json) out.println("note: rule type unknown; pass --type for accurate input handling.");
        if (!json) Report.printTestsHeading(out, cases.size(), smoke
                ? "no cases file, so default " + l.rule().type.xmlName + " inputs and no pass/fail checks. To make real tests: iscrule add-test " + (o.positional.get(0).contains("paste-here") ? "" : o.positional.get(0)) + "  (asks what to test)"
                : o.positional.get(1));

        List<TestSuite.Result> results = new ArrayList<>();
        for (TestSuite.Case c : cases) {
            TestSuite.Result r = TestSuite.runCase(l.rule(), c, o.timeoutMs, o.strictMocks);
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
            Report.printSummary(out, results);
        }
        if (o.junit != null) Files.writeString(Path.of(o.junit), Report.junit(l.label(), results), StandardCharsets.UTF_8);
        return exit;
    }

    private static int cmdExport(Opts o, PrintStream out) throws IOException {
        RuleLoader.Loaded l = load(o);
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

    private static int cmdSuite(Opts o, PrintStream out) throws IOException {
        Path folder;
        if (!o.positional.isEmpty()) folder = Path.of(o.positional.get(0));
        else if (Files.isDirectory(Path.of("samples"))) folder = Path.of("samples");
        else if (Files.isDirectory(Path.of("isc-rule-kit", "samples"))) folder = Path.of("isc-rule-kit", "samples");
        else throw new UsageException("usage: suite <folder>   (no 'samples' folder here)");
        return Suite.run(folder, o.timeoutMs, o.verbose, o.junit, out);
    }

    /** The rule a command works on: the first positional argument, or paste-here/Rule.java. */
    private static Path rulePath(Opts o) throws IOException {
        if (o.positional.isEmpty()) {
            Path p = RunPasted.defaultRule();
            o.positional.add(p.toString());
            return p;
        }
        if (o.positional.get(0).equals("-")) throw new UsageException("this command needs a rule file, not standard input");
        return Path.of(o.positional.get(0));
    }

    private static Path casesPath(Opts o, Path rule) {
        return o.positional.size() > 1 ? Path.of(o.positional.get(1)) : RunPasted.casesFor(rule);
    }

    private static int cmdAnalyze(Opts o, PrintStream out) throws IOException {
        Path rule = rulePath(o);
        RuleLoader.Loaded l = load(o);
        CaseWizard.printAnalysis(out, rule.toString(), l.rule(), RuleAnalyzer.analyze(l.rule()));
        return 0;
    }

    private static int cmdAddTest(Opts o, PrintStream out) throws IOException {
        Path rule = rulePath(o);
        Path cases = casesPath(o, rule);
        RuleLoader.Loaded l = load(o);
        int r = new CaseWizard(CaseWizard.stdin(), out, o.timeoutMs).addTests(rule, cases, l);
        if (r != -1) return r;
        out.println();
        List<String> args = new ArrayList<>(List.of("run", rule.toString(), cases.toString()));
        if (o.type != null) { args.add("--type"); args.add(o.type); }
        if (o.verbose) args.add("--verbose");
        return dispatch("run", parseOpts(args.toArray(new String[0])), out);
    }

    private static int cmdGen(Opts o, PrintStream out) throws IOException {
        Path rule = rulePath(o);
        Path cases = casesPath(o, rule);
        RuleLoader.Loaded l = load(o);
        int n = CaseWizard.generate(l.rule(), cases, o.record, o.timeoutMs, o.max, out);
        out.println("Added " + n + " test case" + (n == 1 ? "" : "s") + " to " + cases
                + (o.record ? " with the current results recorded as expectations. Review them: a recorded bug is still a bug."
                            : " (inputs only, no pass/fail checks). Add checks with --record, or edit the file."));
        out.println("Run them:  iscrule run " + rule + " " + cases);
        return 0;
    }

    private static int cmdNew(Opts o, PrintStream out) throws IOException {
        if (o.positional.size() < 2) throw new UsageException("usage: new <type> <Name> [--dir folder]   (types: iscrule types)");
        RuleType t = RuleType.fromName(o.positional.get(0)).orElseThrow(() -> new UsageException("unknown rule type '" + o.positional.get(0) + "' (see: iscrule types)"));
        String name = o.positional.get(1).replaceAll("[^A-Za-z0-9_]", "");
        if (name.isEmpty()) throw new UsageException("the rule name needs letters or digits");
        Path dir = Path.of(o.out != null ? o.out : "rules").resolve(t.xmlName);
        Path rule = dir.resolve(name + ".java");
        if (Files.exists(rule)) throw new UsageException(rule + " already exists");
        Files.createDirectories(dir);
        Files.writeString(rule, Templates.rule(t, name), StandardCharsets.UTF_8);
        out.println("Created " + rule);
        out.println();
        out.println("Next:");
        out.println("  1. Write your logic in " + rule);
        out.println("  2. iscrule add-test " + rule + "      asks what to test, runs it, saves " + name + ".cases.json");
        out.println("  3. iscrule suite " + dir.getParent() + "            runs every rule and case in the folder");
        out.println("  4. iscrule export " + rule + " -o \"Rule - " + t.xmlName + " - " + name + ".xml\"");
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
                  iscrule suite    [folder] [--verbose] [--junit FILE]    validate + test every rule in a folder (default:
                                                                          samples), one line per rule; Foo.java uses
                                                                          Foo.cases.json; *.buggy.* files must be caught
                  (validate/test/check/run/paste accept --watch to re-run whenever the file is saved,
                   --verbose to expand every case, and --color / --no-color)
                  iscrule export   <rule> [--type T] [--name N] [--desc D] [-o FILE]   produce ISC-ready rule XML

                WRITING TESTS (<rule> defaults to paste-here/Rule.java, cases to <rule>.cases.json)
                  iscrule add-test [rule] [cases]                         asks what to test, runs the rule, shows the
                                                                          result, asks if it is right, saves the case
                  iscrule gen      [rule] [cases] [--record] [--max N]    writes a baseline case plus one per value the
                                                                          rule checks; --record saves results as checks
                  iscrule analyze  [rule]                                 what the rule reads and the values it checks
                  iscrule new      <type> <Name> [--dir rules]            start a new rule from a template

                  iscrule scaffold <type>                                 print a starter cases.json
                  iscrule types                                           list rule types and their inputs

                <rule> is a rule XML file, a raw BeanShell/Java file (RDK class style is fine), or '-' to read stdin.
                Exit codes: 0 ok, 1 validation or test failures, 2 usage/I-O error.""");
    }
}
