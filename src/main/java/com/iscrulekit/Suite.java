package com.iscrulekit;

import com.iscrulekit.Finding.Severity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code iscrule suite [folder]}: validates and tests every rule in a folder (recursively) and prints one line per rule.
 *
 * <ul>
 *   <li>A rule is any {@code .java}, {@code .bsh} or {@code .xml} file. Its cases are the file next to it with the same
 *       name and {@code .cases.json} ({@code Foo.java} uses {@code Foo.cases.json}). With no cases file it gets a smoke run.</li>
 *   <li>The rule type comes from the parent folder name when it is a rule type ({@code samples/Transform/...}),
 *       else it is auto-detected.</li>
 *   <li>Files named {@code *.buggy.*} are deliberately broken samples: they count as OK when the tester catches the bug
 *       (a failed case, an error, or a validation error) and as MISSED when everything passes.</li>
 * </ul>
 */
final class Suite {
    private Suite() {}

    private enum Verdict { PASS, FAIL, CAUGHT, MISSED }

    private record RuleRun(Path rule, Path cases, ParsedRule parsed, List<Finding> findings, List<TestSuite.Result> results,
                           boolean buggy, Verdict verdict, String problem) {}

    static int run(Path folder, long timeoutMs, boolean verbose, String junit, PrintStream out) throws IOException {
        if (!Files.isDirectory(folder)) throw new Main.UsageException("not a folder: " + folder);
        List<Path> rules;
        try (Stream<Path> s = Files.walk(folder)) {
            rules = s.filter(Files::isRegularFile).filter(Suite::isRule).sorted().toList();
        }
        if (rules.isEmpty()) {
            out.println("No rule files (.java, .bsh, .xml) under " + folder);
            return 2;
        }

        out.println(Ansi.bold("SUITE") + "  " + folder + Ansi.dim("  (" + rules.size() + " rule" + (rules.size() == 1 ? "" : "s") + ")"));
        out.println(Ansi.dim("-".repeat(Report.WIDTH)));
        List<RuleRun> runs = new ArrayList<>();
        int nameWidth = 0;
        for (Path r : rules) nameWidth = Math.max(nameWidth, folder.relativize(r).toString().length());
        nameWidth = Math.min(nameWidth, 60);

        for (Path r : rules) {
            RuleRun rr = runOne(folder, r, timeoutMs);
            runs.add(rr);
            printLine(out, folder, rr, nameWidth);
            boolean showDetail = verbose || rr.verdict() == Verdict.FAIL || rr.verdict() == Verdict.MISSED;
            if (showDetail) printDetail(out, rr, verbose);
        }

        out.println(Ansi.dim("-".repeat(Report.WIDTH)));
        long pass = runs.stream().filter(x -> x.verdict() == Verdict.PASS).count();
        long fail = runs.stream().filter(x -> x.verdict() == Verdict.FAIL).count();
        long caught = runs.stream().filter(x -> x.verdict() == Verdict.CAUGHT).count();
        long missed = runs.stream().filter(x -> x.verdict() == Verdict.MISSED).count();
        int cases = runs.stream().mapToInt(x -> x.results().size()).sum();
        List<String> parts = new ArrayList<>();
        parts.add(Ansi.green(pass + " passed"));
        if (fail > 0) parts.add(Ansi.red(fail + " failed"));
        if (caught > 0) parts.add(Ansi.green(caught + " buggy sample" + (caught == 1 ? "" : "s") + " caught"));
        if (missed > 0) parts.add(Ansi.red(missed + " buggy sample" + (missed == 1 ? "" : "s") + " missed"));
        out.println(Ansi.bold("RESULT") + "  " + String.join(", ", parts) + Ansi.dim("  (" + runs.size() + " rules, " + cases + " cases)"));

        if (junit != null) Files.writeString(Path.of(junit), junitXml(folder, runs), StandardCharsets.UTF_8);
        return fail + missed > 0 ? 1 : 0;
    }

    private static boolean isRule(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".cases.json")) return false;
        return n.endsWith(".java") || n.endsWith(".bsh") || n.endsWith(".xml");
    }

    static boolean isBuggy(Path p) { return p.getFileName().toString().toLowerCase().contains(".buggy."); }

    private static Path casesFor(Path rule) {
        String n = rule.getFileName().toString();
        Path c = rule.resolveSibling(n.substring(0, n.lastIndexOf('.')) + ".cases.json");
        return Files.isRegularFile(c) ? c : null;
    }

    private static RuleRun runOne(Path folder, Path rule, long timeoutMs) {
        Path cases = casesFor(rule);
        boolean buggy = isBuggy(rule);
        ParsedRule p;
        List<Finding> findings = List.of();
        List<TestSuite.Result> results = new ArrayList<>();
        String problem = null;
        try {
            p = RuleLoader.fromFile(rule, null).rule();
            findings = Validator.validate(p);
            if (p.type == null && cases == null) {
                problem = "rule type unknown and no cases file: put it in a folder named after its type";
            } else {
                List<TestSuite.Case> list = cases != null ? TestSuite.load(cases) : TestSuite.defaults(p.type);
                for (TestSuite.Case c : list) results.add(TestSuite.runCase(p, c, timeoutMs, false));
            }
        } catch (IOException | RuntimeException e) {
            return new RuleRun(rule, cases, null, findings, results, buggy, buggy ? Verdict.CAUGHT : Verdict.FAIL,
                    "cannot load: " + e.getMessage());
        }
        boolean bad = problem != null || Report.count(findings, Severity.ERROR) > 0 || results.stream().anyMatch(r -> !r.passed());
        Verdict v = buggy ? (bad ? Verdict.CAUGHT : Verdict.MISSED) : (bad ? Verdict.FAIL : Verdict.PASS);
        return new RuleRun(rule, cases, p, findings, results, buggy, v, problem);
    }

    private static void printLine(PrintStream out, Path folder, RuleRun rr, int nameWidth) {
        String tag = switch (rr.verdict()) {
            case PASS -> Ansi.green("PASS  ");
            case FAIL -> Ansi.red("FAIL  ");
            case CAUGHT -> Ansi.green("CAUGHT");
            case MISSED -> Ansi.red("MISSED");
        };
        String name = folder.relativize(rr.rule()).toString();
        long ok = rr.results().stream().filter(TestSuite.Result::passed).count();
        int errs = Report.count(rr.findings(), Severity.ERROR), warns = Report.count(rr.findings(), Severity.WARN);
        String cases = rr.cases() == null ? "smoke run" : ok + "/" + rr.results().size() + " cases";
        String val = (errs > 0 ? Ansi.red(errs + " error" + (errs == 1 ? "" : "s")) : "0 errors")
                + (warns > 0 ? ", " + warns + " warning" + (warns == 1 ? "" : "s") : "");
        out.println("  " + tag + "  " + pad(name, nameWidth) + "  " + pad(cases, 12) + "  " + Ansi.dim(val));
        if (rr.verdict() == Verdict.CAUGHT) {
            String why = firstProblem(rr);
            if (why != null) for (String l : Report.wrap("caught: " + why, Report.WIDTH - 12)) out.println("          " + Ansi.dim(l));
        }
        if (rr.verdict() == Verdict.MISSED) out.println("          " + Ansi.red("buggy sample passed every check: the tester (or its cases) did not catch the bug"));
        if (rr.problem() != null && rr.verdict() == Verdict.FAIL) out.println("          " + Ansi.red(rr.problem()));
    }

    /** One line saying how a buggy sample was caught. */
    private static String firstProblem(RuleRun rr) {
        if (rr.problem() != null) return rr.problem();
        for (Finding f : rr.findings()) if (f.severity() == Severity.ERROR)
            return "validation " + f.code() + (f.line() != null ? " at line " + f.line() : "") + ": " + f.message();
        for (TestSuite.Result r : rr.results()) {
            if (r.passed()) continue;
            String n = r.testCase().name();
            if (r.status() == TestSuite.Status.ERROR && r.outcome().error() != null)
                return "\"" + n + "\" threw " + r.outcome().error().type() + ": " + r.outcome().error().message();
            if (!r.failures().isEmpty()) return "\"" + n + "\" failed: " + r.failures().get(0).split("\n")[0];
        }
        return null;
    }

    private static void printDetail(PrintStream out, RuleRun rr, boolean verbose) {
        if (rr.parsed() == null) return;
        for (Finding f : rr.findings()) if (f.severity() == Severity.ERROR || (verbose && f.severity() == Severity.WARN)) {
            String tag = f.severity() == Severity.ERROR ? Ansi.red("ERROR") : Ansi.yellow("WARN ");
            out.println("          " + tag + "  " + (f.line() != null ? "line " + f.line() + "  " : "") + Ansi.dim(f.code()));
            for (String l : Report.wrap(f.message(), Report.WIDTH - 17)) out.println("                 " + l);
        }
        for (TestSuite.Result r : rr.results()) if (verbose || !r.passed()) Report.printResult(out, r, verbose);
        out.println();
    }

    private static String pad(String s, int w) { return s.length() >= w ? s : s + " ".repeat(w - s.length()); }

    private static String junitXml(Path folder, List<RuleRun> runs) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
        for (RuleRun rr : runs) {
            String xml = Report.junit(folder.relativize(rr.rule()).toString(), rr.results());
            int start = xml.indexOf("<testsuite");
            int end = xml.lastIndexOf("</testsuite>");
            if (start >= 0 && end > start) b.append(xml, start, end + "</testsuite>".length()).append('\n');
        }
        return b.append("</testsuites>\n").toString();
    }
}
