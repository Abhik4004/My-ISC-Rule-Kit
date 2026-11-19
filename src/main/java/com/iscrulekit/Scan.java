package com.iscrulekit;

import java.util.ArrayList;
import java.util.List;

/** Splits Java/BeanShell source into code, string and comment tokens so checks never match inside strings or comments. */
public final class Scan {
    private Scan() {}

    public enum Kind { CODE, STRING, COMMENT }

    public record Tok(Kind kind, String text, int line, char quote, boolean unterminated) {}

    private static final class Builder {
        final List<Tok> toks = new ArrayList<>();
        final StringBuilder buf = new StringBuilder();
        int bufLine = 1;

        void flush() {
            if (buf.length() > 0) {
                toks.add(new Tok(Kind.CODE, buf.toString(), bufLine, '\0', false));
                buf.setLength(0);
            }
        }
    }

    public static List<Tok> tokenize(String src) {
        Builder b = new Builder();
        int i = 0, n = src.length(), line = 1;
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && d == '/') {
                b.flush();
                int j = src.indexOf('\n', i);
                if (j < 0) j = n;
                b.toks.add(new Tok(Kind.COMMENT, src.substring(i, j), line, '\0', false));
                i = j;
                continue;
            }
            if (c == '/' && d == '*') {
                b.flush();
                int j = src.indexOf("*/", i + 2);
                boolean unterminated = false;
                if (j < 0) { j = n; unterminated = true; } else { j += 2; }
                String s = src.substring(i, j);
                b.toks.add(new Tok(Kind.COMMENT, s, line, '\0', unterminated));
                line += count(s, '\n');
                i = j;
                continue;
            }
            if (c == '"' || c == '\'') {
                b.flush();
                int j = i + 1;
                boolean ok = false;
                while (j < n) {
                    char e = src.charAt(j);
                    if (e == '\\') { j += 2; continue; }
                    if (e == c) { ok = true; break; }
                    if (e == '\n') break;
                    j++;
                }
                int end = ok ? j + 1 : Math.min(j, n);
                b.toks.add(new Tok(Kind.STRING, src.substring(i, end), line, c, !ok));
                i = end;
                continue;
            }
            if (b.buf.length() == 0) b.bufLine = line;
            if (c == '\n') line++;
            b.buf.append(c);
            i++;
        }
        b.flush();
        return b.toks;
    }

    /** Same length and line structure as the source, but comments and string contents are replaced by spaces. */
    public static String blank(List<Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (Tok k : toks) {
            switch (k.kind()) {
                case CODE -> sb.append(k.text());
                case COMMENT -> sb.append(k.text().replaceAll("[^\\n]", " "));
                case STRING -> {
                    String t = k.text();
                    sb.append(k.quote());
                    int innerEnd = k.unterminated() ? t.length() : t.length() - 1;
                    sb.append(t.substring(1, Math.max(1, innerEnd)).replaceAll("[^\\n]", " "));
                    if (!k.unterminated()) sb.append(k.quote());
                }
            }
        }
        return sb.toString();
    }

    /** Source with comments blanked but string literals preserved. */
    public static String noComments(List<Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (Tok k : toks) sb.append(k.kind() == Kind.COMMENT ? k.text().replaceAll("[^\\n]", " ") : k.text());
        return sb.toString();
    }

    public static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** 1-based line of the character at index {@code idx}. */
    public static int lineOf(String s, int idx) {
        int n = 1;
        int end = Math.min(idx, s.length());
        for (int i = 0; i < end; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /** Overwrites [a,b) with spaces, keeping newlines, so line numbers stay stable. */
    public static String blankRange(String s, int a, int b) {
        return s.substring(0, a) + s.substring(a, b).replaceAll("[^\\n]", " ") + s.substring(b);
    }
}
