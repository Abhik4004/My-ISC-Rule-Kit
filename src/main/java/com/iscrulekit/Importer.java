package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.iscrulekit.Finding.Severity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code iscrule import <file>}: takes rules exported from ISC and sets each one up for testing.
 * <ul>
 *   <li>Rule XML: one {@code <Rule>} or several (e.g. inside {@code <sailpoint>}), source in CDATA or escaped.</li>
 *   <li>ISC API / VS Code extension JSON: an object or array with {@code name}, {@code type}, {@code sourceCode.script}.</li>
 * </ul>
 * For each rule it writes {@code <dir>/<Type>/<Name>.java} (the code, with a header keeping the rule's name, type and
 * description so {@code export} can rebuild the XML), {@code <Name>.cases.json} (generated cases with today's results
 * recorded) and {@code <Name>Test.java} (a JUnit test class).
 */
final class Importer {
    private Importer() {}

    record Imported(String name, String type, String description, String source) {}

    static final String HEADER_MARK = "ISC rule imported by iscrule";

    static List<Imported> read(String text) throws IOException {
        if (text.startsWith("﻿")) text = text.substring(1);
        String t = text.trim();
        List<Imported> out = new ArrayList<>();
        if (t.startsWith("{") || t.startsWith("[")) {
            JsonNode root = Json.MAPPER.readTree(t);
            List<JsonNode> items = new ArrayList<>();
            if (root.isArray()) root.forEach(items::add);
            else if (root.has("objects") && root.get("objects").isArray()) root.get("objects").forEach(o -> items.add(o.has("object") ? o.get("object") : o));
            else items.add(root);
            for (JsonNode n : items) {
                String script = n.path("sourceCode").path("script").asText(n.path("script").asText(n.path("source").asText(null)));
                if (script == null || script.isEmpty()) continue;
                out.add(new Imported(n.path("name").asText("Imported Rule"), n.path("type").asText(""), n.path("description").asText(""), script));
            }
            if (out.isEmpty()) throw new Main.UsageException("no rule found in the JSON (expected sourceCode.script)");
            return out;
        }
        Matcher m = Pattern.compile("(?s)<Rule\\b.*?</Rule\\s*>").matcher(text);
        while (m.find()) {
            ParsedRule p = RuleParser.parse(m.group(), null);
            if (!p.xmlOk) {
                String why = p.findings.isEmpty() ? "not well-formed" : p.findings.get(0).message();
                throw new Main.UsageException("rule XML could not be read: " + why);
            }
            out.add(new Imported(p.name.isEmpty() ? "Imported Rule" : p.name, p.typeName == null ? "" : p.typeName, p.description, p.source));
        }
        if (out.isEmpty()) throw new Main.UsageException("no <Rule> element found");
        return out;
    }

    static int run(Path file, Path dir, boolean force, long timeoutMs, PrintStream out) throws IOException {
        List<Imported> rules = read(Files.readString(file, StandardCharsets.UTF_8));
        out.println(Ansi.bold("IMPORT") + "  " + file + Ansi.dim("  (" + rules.size() + " rule" + (rules.size() == 1 ? "" : "s") + ")"));
        int problems = 0;
        for (Imported r : rules) {
            RuleType type = RuleType.fromName(r.type()).orElse(null);
            if (type == null) type = RuleType.guess(r.source()).orElse(null);
            String folder = type != null ? type.xmlName : "Unknown";
            Path ruleFile = dir.resolve(folder).resolve(javaName(r.name()) + ".java");
            out.println();
            out.println("  " + Ansi.bold(r.name()) + Ansi.dim("  " + (type != null ? type.xmlName : "type unknown: " + r.type())));
            if (Files.exists(ruleFile) && !force) {
                out.println("    " + Ansi.yellow("skipped: " + ruleFile + " already exists (use --force to overwrite)"));
                continue;
            }
            Files.createDirectories(ruleFile.getParent());
            Files.writeString(ruleFile, header(r, type, file) + stripLeadingBlank(r.source()), StandardCharsets.UTF_8);
            out.println("    rule        " + ruleFile);
            if (type == null) {
                out.println("    " + Ansi.yellow("could not tell the rule type: move the file into a folder named after its type, then run iscrule gen"));
                problems++;
                continue;
            }

            ParsedRule parsed = RuleLoader.fromFile(ruleFile, type).rule();
            List<Finding> findings = Validator.validate(parsed);
            int errors = Report.count(findings, Severity.ERROR), warns = Report.count(findings, Severity.WARN);
            out.println("    validation  " + (errors > 0 ? Ansi.red(errors + " error" + (errors == 1 ? "" : "s")) : "0 errors") + ", " + warns + " warning" + (warns == 1 ? "" : "s")
                    + Ansi.dim("   (details: iscrule validate " + ruleFile + ")"));

            Path cases = RunPasted.casesFor(ruleFile);
            if (force) Files.deleteIfExists(cases);
            int n = CaseWizard.generate(parsed, cases, true, timeoutMs, 40, new PrintStream(java.io.OutputStream.nullOutputStream()));
            List<TestSuite.Case> list = TestSuite.load(cases);
            int crashed = 0;
            for (TestSuite.Case c : list) if (c.expectError()) crashed++;
            out.println("    cases       " + cases + Ansi.dim("   (" + n + " generated, today's results recorded"
                    + (crashed > 0 ? ", " : ")")) + (crashed > 0 ? Ansi.yellow(crashed + " where the rule throws") + Ansi.dim(")") : ""));
            Path test = JUnitGen.write(ruleFile, cases, type);
            out.println("    junit test  " + test);
            if (errors > 0 || crashed > 0) problems++;
        }
        out.println();
        out.println("Next:");
        out.println("  iscrule suite " + dir + "          run every imported rule against its cases");
        out.println("  iscrule junit " + dir + "          run the JUnit test classes");
        out.println("  iscrule add-test <rule file>    add a case you choose (asks what to test)");
        out.println(Ansi.dim("The recorded cases pin down what each rule does today, bugs included. Review the cases that throw,"));
        out.println(Ansi.dim("and use add-test for the behaviour you actually want."));
        return problems > 0 ? 1 : 0;
    }

    /** "UCSFMC AD Before-Provisioning" -> "UcsfmcAdBeforeProvisioning"; keeps mixedCase words as they are. */
    static String javaName(String ruleName) {
        StringBuilder b = new StringBuilder();
        for (String w : ruleName.split("[^A-Za-z0-9]+")) {
            if (w.isEmpty()) continue;
            boolean allCaps = w.equals(w.toUpperCase());
            b.append(Character.toUpperCase(w.charAt(0))).append(allCaps ? w.substring(1).toLowerCase() : w.substring(1));
        }
        String s = b.toString();
        if (s.isEmpty()) s = "ImportedRule";
        if (Character.isDigit(s.charAt(0))) s = "Rule" + s;
        return s;
    }

    private static String header(Imported r, RuleType type, Path from) {
        StringBuilder b = new StringBuilder("/*\n");
        b.append(" * ").append(HEADER_MARK).append(" from ").append(from.getFileName()).append(" on ").append(LocalDate.now()).append("\n");
        b.append(" * Rule name:   ").append(r.name()).append("\n");
        b.append(" * Rule type:   ").append(type != null ? type.xmlName + " (" + type.label + " rule)" : r.type()).append("\n");
        if (!r.description().isBlank()) b.append(" * Description: ").append(r.description().replaceAll("\\s+", " ").replace("*/", "* /")).append("\n");
        b.append(" * 'iscrule export' rebuilds the rule XML from this file, using the name and type above.\n");
        b.append(" */\n");
        return b.toString();
    }

    private static String stripLeadingBlank(String s) {
        return s.replaceFirst("^\\s*\\n", "").replaceAll("\\s+$", "") + "\n";
    }

    /** Name, type and description from an import header, so export can rebuild the XML. Null when there is no header. */
    static String[] headerInfo(String source) {
        if (!source.contains(HEADER_MARK)) return null;
        Matcher n = Pattern.compile("\\* Rule name:\\s*(.+)").matcher(source);
        Matcher t = Pattern.compile("\\* Rule type:\\s*(\\S+)").matcher(source);
        Matcher d = Pattern.compile("\\* Description:\\s*(.+)").matcher(source);
        return new String[] { n.find() ? n.group(1).trim() : null, t.find() ? t.group(1).trim() : null, d.find() ? d.group(1).trim() : null };
    }

    /** The source without the import header comment (for export). */
    static String withoutHeader(String source) {
        return source.replaceFirst("(?s)^\\s*/\\*\\s*\\n \\* " + Pattern.quote(HEADER_MARK) + ".*?\\*/\\s*\\n", "");
    }
}
