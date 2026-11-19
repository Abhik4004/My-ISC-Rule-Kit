package com.iscrulekit;

/** One validator result. {@code line} is the 1-based line in the file you passed in, or null if not line-specific. */
public record Finding(Severity severity, String code, Integer line, String message) {
    public enum Severity { ERROR, WARN, INFO }
}
