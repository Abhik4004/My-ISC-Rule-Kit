package com.iscrulekit;

/**
 * Optional terminal colours. On automatically in terminals known to understand ANSI codes (Windows Terminal, VS Code,
 * any non-Windows terminal); off when output is redirected, when {@code NO_COLOR} is set, or with {@code --no-color}.
 * {@code --color} forces it on.
 */
final class Ansi {
    private Ansi() {}

    static boolean on = detect();

    private static boolean detect() {
        if (System.getenv("NO_COLOR") != null) return false;
        if (System.console() == null) return false; // piped or redirected
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return !windows || System.getenv("WT_SESSION") != null || System.getenv("TERM_PROGRAM") != null
                || System.getenv("ConEmuANSI") != null;
    }

    private static String c(String code, String s) { return on ? "\u001b[" + code + "m" + s + "\u001b[0m" : s; }

    static String red(String s) { return c("31", s); }
    static String green(String s) { return c("32", s); }
    static String yellow(String s) { return c("33", s); }
    static String cyan(String s) { return c("36", s); }
    static String dim(String s) { return c("2", s); }
    static String bold(String s) { return c("1", s); }
}
