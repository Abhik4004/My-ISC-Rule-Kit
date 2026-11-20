package com.iscrulekit;

import com.iscrulekit.Finding.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@code iscrule rdk [rule] [cases]}: puts a rule into a SailPoint Rule Development Kit checkout and tests it there.
 * <ol>
 *   <li>validates the rule (pasted code, a rule file, or an exported XML);</li>
 *   <li>writes the deployable XML to {@code <rdk>/src/main/resources/rules/Rule - <Type> - <Name>.xml};</li>
 *   <li>uses the rule's cases file, or generates one (today's results recorded) when there is none;</li>
 *   <li>writes {@code <rdk>/src/test/java/sailpoint/<Name>Test.java} (JUnit 5 + Mockito, RDK style);</li>
 *   <li>runs {@code mvn test -Dtest=<Name>Test} in the RDK and prints one line per test.</li>
 * </ol>
 */
final class Rdk {
    private Rdk() {}

    static int run(Path rule, Path cases, RuleType type, String rdkOption, String nameOption, boolean noRun, boolean force, long timeoutMs, PrintStream out)
            throws IOException {
        Path rdk = findRdk(rdkOption);
        RuleLoader.Loaded l = RuleLoader.fromFile(rule, type);
        ParsedRule p = l.rule();
        if (p.type == null) throw new Main.UsageException("could not tell the rule type of " + rule + "; pass --type (see: iscrule types)");

        RuleXml.Built built = RuleXml.fromRule(p, nameOption, null);
        String base = rule.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        if (nameOption == null && built.name().equals("My Rule") && !base.equalsIgnoreCase("Rule")) {
            built = RuleXml.fromRule(p, base, null); // a named file: use its name
        } else if (nameOption == null && built.name().equals("My Rule")) { // pasted code has no name: ask (Enter = default)
            String dflt = "Pasted " + p.type.xmlName + " Rule";
            out.print("The rule has no name yet. It becomes the rule name in ISC and the XML file name. Name [" + dflt + "]: ");
            out.flush();
            String typed = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            typed = typed == null ? "" : typed.replace("﻿", "").trim();
            built = RuleXml.fromRule(p, typed.isEmpty() ? dflt : typed, null);
            out.println();
        }
        String javaName = Importer.javaName(built.name());
        String className = javaName + "Test";
        String xmlName = RuleXml.fileName(p.type, built.name());
        Path xmlFile = rdk.resolve("src/main/resources/rules").resolve(xmlName);
        Path testFile = rdk.resolve("src/test/java/sailpoint").resolve(className + ".java");

        out.println(Ansi.bold("RDK") + "  " + rule + Ansi.dim("  ->  " + rdk));
        out.println();

        // 1. validate
        List<Finding> findings = Validator.validate(p);
        int errors = Report.count(findings, Severity.ERROR), warns = Report.count(findings, Severity.WARN);
        out.println("  " + pad("validation") + (errors > 0 ? Ansi.red(errors + " error" + (errors == 1 ? "" : "s")) : Ansi.green("0 errors")) + ", "
                + (warns > 0 ? Ansi.yellow(warns + " warning" + (warns == 1 ? "" : "s")) : "0 warnings"));
        for (Finding f : findings) if (f.severity() == Severity.ERROR || f.severity() == Severity.WARN && f.code().startsWith("BUG"))
            out.println("  " + pad("") + (f.severity() == Severity.ERROR ? Ansi.red("ERROR") : Ansi.yellow("WARN ")) + " " + Ansi.dim(f.code())
                    + (f.line() != null ? " line " + f.line() : "") + ": " + firstSentence(f.message()));
        if (warns > 0 || errors > 0) out.println("  " + pad("") + Ansi.dim("all details: iscrule validate " + rule));

        // 2. the deployable XML
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, built.xml(), StandardCharsets.UTF_8);
        out.println("  " + pad("rule XML") + rdk.relativize(xmlFile) + Ansi.dim("   (this is the file you deploy)"));

        // 3. cases
        boolean generated = false;
        if (cases == null) cases = RunPasted.casesFor(rule);
        if (!Files.exists(cases)) {
            CaseWizard.generate(p, cases, true, timeoutMs, 40, new PrintStream(java.io.OutputStream.nullOutputStream()));
            generated = true;
        }
        List<TestSuite.Case> list = TestSuite.load(cases);
        out.println("  " + pad("test cases") + cases + Ansi.dim("   (" + list.size() + (generated ? " generated, today's results recorded)" : ")")));

        // 4. the test class
        boolean handMade = Files.exists(testFile) && !Files.readString(testFile, StandardCharsets.UTF_8).contains(RdkGen.MARKER);
        if (handMade && !force) {
            out.println("  " + pad("test class") + Ansi.yellow(rdk.relativize(testFile) + " was not made by iscrule (or was edited by hand),"));
            out.println("  " + pad("") + Ansi.yellow("so it was left alone. To replace it, run again with --force (the old file is kept as .java.bak)."));
        } else {
            if (handMade) { // --force: keep the old file, with an extension Maven does not compile
                Path bak = testFile.resolveSibling(testFile.getFileName() + ".bak");
                Files.move(testFile, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                out.println("  " + pad("") + Ansi.dim("old test class saved as " + rdk.relativize(bak)));
            }
            String ruleXmlRel = rdk.relativize(xmlFile).toString().replace('\\', '/');
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, RdkGen.testClass(rdk, className, ruleXmlRel, p.type, list), StandardCharsets.UTF_8);
            out.println("  " + pad("test class") + rdk.relativize(testFile) + Ansi.dim("   (" + list.size() + " @Test methods)"));
        }
        if (noRun) {
            out.println();
            out.println("Run the tests:  cd \"" + rdk + "\"  then  mvn test -Dtest=" + className);
            return errors > 0 ? 1 : 0;
        }

        // 5. mvn test
        out.println();
        int rc = mavenTest(rdk, className, out);
        out.println();
        if (rc == 0 && errors == 0) {
            out.println(Ansi.green("Ready to deploy: ") + xmlFile);
        } else if (rc == 0) {
            out.println(Ansi.yellow("Tests pass, but validation found errors: fix them before deploying ") + xmlFile);
        } else {
            out.println(Ansi.red("Not ready: fix the failing tests (or update the case if the new behaviour is right), then run this again."));
        }
        return rc == 0 && errors == 0 ? 0 : 1;
    }

    private static String pad(String s) { return s + " ".repeat(Math.max(1, 12 - s.length())); }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot + 1) : s;
    }

    /** --rdk, else ISC_RDK, else a rule-development-kit folder next to (or above) the working directory. */
    static Path findRdk(String option) {
        List<Path> tries = new ArrayList<>();
        if (option != null) tries.add(Path.of(option));
        String env = System.getenv("ISC_RDK");
        if (env != null && !env.isBlank()) tries.add(Path.of(env));
        Path here = Path.of("").toAbsolutePath();
        for (Path d = here; d != null; d = d.getParent()) {
            tries.add(d.resolve("rule-development-kit"));
            if (d.getFileName() != null && d.getFileName().toString().equals("rule-development-kit")) tries.add(d);
        }
        for (Path t : tries) if (Files.isRegularFile(t.resolve("pom.xml")) && Files.isDirectory(t.resolve("lib/sailpoint"))) return t.toAbsolutePath().normalize();
        throw new Main.UsageException("Rule Development Kit not found. Clone it next to this folder:\n"
                + "  git clone https://github.com/sailpoint-oss/rule-development-kit.git\n"
                + "or pass --rdk <folder> (or set the ISC_RDK environment variable).");
    }

    /** mvn on PATH, else MAVEN_HOME, else a tools/apache-maven-* folder next to the RDK or the working directory. */
    static String findMaven(Path rdk) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String exe = win ? "mvn.cmd" : "mvn";
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) if (Files.isRegularFile(Path.of(d, exe))) return Path.of(d, exe).toString();
        String home = System.getenv("MAVEN_HOME");
        if (home != null && Files.isRegularFile(Path.of(home, "bin", exe))) return Path.of(home, "bin", exe).toString();
        for (Path base : List.of(rdk.getParent() == null ? rdk : rdk.getParent(), Path.of("").toAbsolutePath(), Path.of("").toAbsolutePath().getParent())) {
            if (base == null) continue;
            Path tools = base.resolve("tools");
            if (!Files.isDirectory(tools)) continue;
            try (Stream<Path> s = Files.list(tools)) {
                Path m = s.filter(Files::isDirectory).filter(x -> x.getFileName().toString().startsWith("apache-maven-"))
                        .filter(x -> Files.isRegularFile(x.resolve("bin").resolve(exe))).sorted().reduce((a, c) -> c).orElse(null);
                if (m != null && Files.isRegularFile(m.resolve("bin").resolve(exe))) return m.resolve("bin").resolve(exe).toString();
            } catch (IOException ignored) {
                // keep looking
            }
        }
        throw new Main.UsageException("Maven not found. Install Maven 3.9+ (or unzip it into a 'tools' folder next to the RDK), "
                + "or use --no-run and run 'mvn test' yourself.");
    }

    static int mavenTest(Path rdk, String className, PrintStream out) throws IOException {
        String mvn = findMaven(rdk);
        Path report = rdk.resolve("target/surefire-reports/TEST-sailpoint." + className + ".xml");
        Files.deleteIfExists(report);
        Path log = rdk.resolve("target/iscrule-mvn.log");
        Files.createDirectories(log.getParent());
        out.println("  " + pad("mvn test") + Ansi.dim("-Dtest=" + className + "   (first run downloads libraries; log: " + rdk.relativize(log) + ")"));
        out.flush();
        ProcessBuilder pb = new ProcessBuilder(mvn, "-B", "test", "-Dtest=" + className, "-Dsurefire.failIfNoSpecifiedTests=false")
                .directory(rdk.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().merge("MAVEN_OPTS", "--enable-native-access=ALL-UNNAMED", (a, b) -> a + " " + b);
        int rc;
        try {
            Process proc = pb.start();
            if (!proc.waitFor(15, TimeUnit.MINUTES)) { proc.destroyForcibly(); throw new Main.UsageException("mvn test took longer than 15 minutes"); }
            rc = proc.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
        if (!Files.exists(report)) {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            if (!summarizeCompileErrors(rdk, lines, out)) {
                out.println("  " + Ansi.red("Maven did not run the tests (exit " + rc + "). The important lines from the log:"));
                int shown = 0;
                for (String line : lines)
                    if (line.startsWith("[ERROR]") && !line.contains("-> [Help") && !line.contains("Re-run Maven") && !line.contains("For more information")
                            && !line.trim().equals("[ERROR]") && shown++ < 25)
                        out.println("    " + line.replace(rdk.toString() + File.separator, ""));
            }
            return rc == 0 ? 1 : rc;
        }
        return printReport(report, out);
    }

    /**
     * Java compile errors from the Maven log, grouped per file: which symbols are missing, and what to do. Maven compiles
     * every test class, so one broken file (often an old or hand-made one) stops all tests. Returns false when there
     * are no compile errors in the log.
     */
    static boolean summarizeCompileErrors(Path rdk, List<String> log, PrintStream out) {
        java.util.regex.Pattern loc = java.util.regex.Pattern.compile("^\\[ERROR\\] (/?.+?\\.java):\\[(\\d+),\\d+\\] (.+)$");
        Map<String, java.util.Set<String>> problems = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<String>> lines = new java.util.LinkedHashMap<>();
        String current = null;
        for (String l : log) {
            java.util.regex.Matcher m = loc.matcher(l);
            if (m.find()) {
                current = m.group(1);
                lines.computeIfAbsent(current, k -> new java.util.TreeSet<>(java.util.Comparator.comparingInt(Integer::parseInt))).add(m.group(2));
                if (!m.group(3).startsWith("cannot find symbol")) problems.computeIfAbsent(current, k -> new java.util.LinkedHashSet<>()).add(m.group(3).trim());
                continue;
            }
            if (current != null && l.startsWith("[ERROR]   symbol:"))
                problems.computeIfAbsent(current, k -> new java.util.LinkedHashSet<>()).add("cannot find " + l.substring(l.indexOf(':') + 1).trim());
        }
        if (lines.isEmpty()) return false;
        out.println("  " + Ansi.red("Maven could not compile the test classes, so no test ran. Maven compiles every test class, so one broken"));
        out.println("  " + Ansi.red("file stops all of them."));
        for (Map.Entry<String, java.util.Set<String>> e : lines.entrySet()) {
            Path file = Path.of(e.getKey().replaceFirst("^/(?=[A-Za-z]:)", ""));
            String shown;
            try { shown = rdk.relativize(file).toString(); } catch (IllegalArgumentException ex) { shown = file.toString(); }
            boolean ours = false;
            try { ours = Files.readString(file, StandardCharsets.UTF_8).contains(RdkGen.MARKER); } catch (IOException ignored) { /* unreadable */ }
            out.println();
            out.println("    " + Ansi.bold(shown) + Ansi.dim("  (lines " + String.join(", ", e.getValue().stream().limit(8).toList())
                    + (e.getValue().size() > 8 ? ", ..." : "") + ")"));
            for (String p : problems.getOrDefault(e.getKey(), java.util.Set.of())) out.println("      " + p);
            String name = file.getFileName().toString();
            if (!ours) {
                out.println("      " + Ansi.cyan("This file was not made by iscrule. If it belongs to this rule, run again with --force to replace it"));
                out.println("      " + Ansi.cyan("(the old file is kept as " + name + ".bak). Otherwise fix it, or move it out of src\\test\\java."));
            } else {
                out.println("      " + Ansi.cyan("This file was made by iscrule: run 'iscrule rdk' for its rule again to regenerate it."));
            }
        }
        return true;
    }

    /** One line per test from the surefire XML report, with the failure message for failed tests. */
    static int printReport(Path report, PrintStream out) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList tcs = f.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("testcase");
            int pass = 0, fail = 0;
            List<Element> sorted = new ArrayList<>();
            for (int k = 0; k < tcs.getLength(); k++) sorted.add((Element) tcs.item(k));
            sorted.sort(java.util.Comparator.comparing(x -> x.getAttribute("name")));
            boolean missingMethod = false;
            for (Element tc : sorted) {
                NodeList bad = tc.getElementsByTagName("failure");
                if (bad.getLength() == 0) bad = tc.getElementsByTagName("error");
                String name = tc.getAttribute("name");
                if (bad.getLength() == 0) {
                    pass++;
                    out.println("    " + Ansi.green("PASS") + "  " + name);
                } else {
                    fail++;
                    Element e = (Element) bad.item(0);
                    String msg = e.getAttribute("message");
                    if (msg.isBlank()) msg = e.getTextContent().split("\n")[0];
                    msg = msg.replaceFirst("(?s)^Sourced file: inline evaluation of: ``.*?'' : ", "");
                    out.println("    " + Ansi.red("FAIL") + "  " + name);
                    for (String line : msg.split("\n")) for (String w : Report.wrap(line, Report.WIDTH - 12)) out.println("            " + w);
                    if (msg.contains("not found in class")) missingMethod = true;
                }
            }
            if (missingMethod) {
                out.println();
                for (String w : Report.wrap("Hint: \"Method ... not found in class 'sailpoint...'\" means the rule calls a method that SailPoint's "
                        + "published rule API (the class stubs in the RDK) does not have. Either it is a typo, or the method exists in "
                        + "IdentityIQ but is not part of the documented ISC rule API. Use a documented method instead, e.g. "
                        + "Attributes.putAll(map) instead of setMap(map).", Report.WIDTH - 4)) out.println("  " + Ansi.cyan(w));
            }
            out.println();
            out.println(Ansi.bold("RESULT") + "  " + Ansi.green(pass + " passed") + (fail > 0 ? ", " + Ansi.red(fail + " failed") : "")
                    + Ansi.dim("  (" + (pass + fail) + " RDK tests)"));
            return fail > 0 ? 1 : 0;
        } catch (Exception e) {
            out.println("  could not read " + report + ": " + e.getMessage());
            return 1;
        }
    }
}
