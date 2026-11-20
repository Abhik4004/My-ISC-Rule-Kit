package com.iscrulekit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Paste and press Run" entry point.
 * <p>
 * Paste your rule into {@code paste-here/Rule.java}, then run this class from your IDE (the green Run arrow next to
 * {@code main}) or {@code iscrule paste} from a terminal. It validates the rule and runs it, using
 * {@code paste-here/Rule.cases.json} as test cases when that file exists, otherwise default inputs (a smoke run).
 * Any arguments are passed through, e.g. {@code --watch}, {@code --verbose}, {@code --type Correlation}.
 */
public final class RunPasted {
    private RunPasted() {}

    static final String DIR = "paste-here";
    static final String RULE = "Rule.java";
    static final String CASES = "Rule.cases.json";

    public static void main(String[] args) {
        String[] all = new String[args.length + 1];
        all[0] = "paste";
        System.arraycopy(args, 0, all, 1, args.length);
        Main.main(all);
    }

    /** Turns {@code paste [options]} into {@code run paste-here/Rule.java [paste-here/Rule.cases.json] [options]}. */
    static String[] toRunArgs(String[] rest) throws IOException {
        Path rule = defaultRule();
        List<String> out = new ArrayList<>();
        out.add("run");
        out.add(rule.toString());
        Path cases = rule.resolveSibling(CASES);
        if (Files.exists(cases) && !Files.readString(cases, StandardCharsets.UTF_8).isBlank()) out.add(cases.toString());
        out.addAll(List.of(rest));
        return out.toArray(new String[0]);
    }

    /** paste-here/Rule.java, checked to contain a rule (used when a command gets no rule file). */
    static Path defaultRule() throws IOException {
        Path rule = findDir().resolve(RULE);
        if (!Files.exists(rule)) {
            Files.writeString(rule, template(), StandardCharsets.UTF_8);
            throw new Main.UsageException("created " + rule + ". Paste your rule into it, save, and run again.");
        }
        if (isEmpty(Files.readString(rule, StandardCharsets.UTF_8)))
            throw new Main.UsageException(rule + " has no rule in it yet. Paste your rule into it, save, and run again.");
        return rule;
    }

    /** Foo.java -> Foo.cases.json next to it. */
    static Path casesFor(Path rule) {
        String n = rule.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return rule.resolveSibling((dot > 0 ? n.substring(0, dot) : n) + ".cases.json");
    }

    /** paste-here next to the working directory, or inside isc-rule-kit/ when run from the parent folder. */
    private static Path findDir() throws IOException {
        for (Path p : List.of(Path.of(DIR), Path.of("isc-rule-kit", DIR))) if (Files.isDirectory(p)) return p;
        Path p = Path.of(DIR);
        Files.createDirectories(p);
        return p;
    }

    /** True when the file holds nothing but comments and whitespace (e.g. the untouched template). */
    static boolean isEmpty(String text) {
        String s = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        return s.isBlank();
    }

    static String template() {
        return """
                // Paste your ISC rule here, replacing these comment lines, and save.
                // Any form works: RDK-style "public class X { ... }", raw BeanShell, or full <Rule> XML.
                // Then run com.iscrulekit.RunPasted from your IDE, or: iscrule paste   (add --watch to re-run on save)
                // For pass/fail checks:  iscrule add-test   (asks what you want to test, runs it, saves Rule.cases.json)
                //                   or:  iscrule gen        (writes test variations automatically)
                """;
    }
}
