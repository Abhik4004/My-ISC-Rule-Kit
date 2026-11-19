package com.iscrulekit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RDK / Eclipse style rules are written as {@code public class X { ... }} with the ISC-injected inputs re-declared as
 * fields. ISC itself wants a plain script. This strips the class wrapper and those re-declared inputs while keeping every
 * line number unchanged (removed text is replaced by spaces).
 */
public final class ClassUnwrapper {
    private ClassUnwrapper() {}

    public record Removed(String name, int line) {}

    public record Result(String source, boolean wrapped, String className, int classLine, List<Removed> removed) {}

    private static final Pattern CLASS_HEADER = Pattern.compile(
            "(^|\\n)([ \\t]*(?:(?:public|final|abstract)\\s+)*class\\s+(\\w+)(?:\\s+extends\\s+[\\w.]+)?(?:\\s+implements\\s+[\\w.,\\s]+)?\\s*\\{)");

    public static Result unwrap(String src, List<String> inputs) {
        String blank = Scan.blank(Scan.tokenize(src));
        String text = src;
        boolean wrapped = false;
        String className = "";
        int classLine = 0, bodyFirst = 0, bodyLast = 0;

        Matcher m = CLASS_HEADER.matcher(blank);
        if (m.find()) {
            int a = m.start() + m.group(1).length();
            int open = a + m.group(2).length() - 1;
            int depth = 0, j = open;
            for (; j < blank.length(); j++) {
                char c = blank.charAt(j);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            if (j < blank.length()) {
                wrapped = true;
                className = m.group(3);
                classLine = Scan.lineOf(blank, a);
                text = Scan.blankRange(Scan.blankRange(text, a, open + 1), j, j + 1);
                blank = Scan.blankRange(Scan.blankRange(blank, a, open + 1), j, j + 1);
                bodyFirst = Scan.lineOf(blank, open);
                bodyLast = Scan.lineOf(blank, j);
            }
        }

        List<Removed> removed = new ArrayList<>();
        if (!inputs.isEmpty()) {
            Pattern re = Pattern.compile("^[ \\t]*(?:(?:public|private|protected|static|final)\\s+)*[A-Za-z_][\\w.]*(?:<[^;=()]*?>)?(?:\\[\\])*[ \\t]+("
                    + String.join("|", inputs) + ")[ \\t]*(?:=[^;]*)?;", Pattern.MULTILINE);
            Matcher r = re.matcher(blank);
            int depth = 0, pos = 0;
            while (r.find()) {
                for (; pos < r.start(); pos++) {
                    char c = blank.charAt(pos);
                    if (c == '{') depth++; else if (c == '}') depth--;
                }
                if (depth == 0) {
                    removed.add(new Removed(r.group(1), Scan.lineOf(blank, r.start())));
                    text = Scan.blankRange(text, r.start(), r.end());
                }
            }
        }

        if (wrapped) { // dedent the former class body
            String[] ls = text.split("\n", -1);
            int min = Integer.MAX_VALUE;
            for (int i = bodyFirst; i < bodyLast - 1 && i < ls.length; i++) {
                if (!ls[i].isBlank()) min = Math.min(min, indent(ls[i]));
            }
            if (min != Integer.MAX_VALUE && min > 0) {
                for (int i = bodyFirst; i < bodyLast - 1 && i < ls.length; i++) {
                    ls[i] = ls[i].substring(Math.min(min, indent(ls[i])));
                }
            }
            text = String.join("\n", ls);
        }
        return new Result(text, wrapped, className, classLine, removed);
    }

    private static int indent(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    /** Trims trailing whitespace, leading/trailing blank lines and collapses runs of blank lines. */
    public static String tidy(String s) {
        List<String> lines = new ArrayList<>();
        for (String l : s.split("\n", -1)) lines.add(l.replaceAll("\\s+$", ""));
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.isBlank() && !out.isEmpty() && out.get(out.size() - 1).isBlank()) continue;
            out.add(l);
        }
        return String.join("\n", out);
    }
}
