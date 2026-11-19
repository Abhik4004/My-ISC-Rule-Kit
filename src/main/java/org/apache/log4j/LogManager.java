package org.apache.log4j;

/** Minimal stand-in so RDK-style {@code LogManager.getLogger(X.class)} field initialisers resolve. */
public final class LogManager {
    private LogManager() {}

    public static Logger getLogger(Class<?> c) { return Logger.getLogger(c); }
    public static Logger getLogger(String name) { return Logger.getLogger(name); }
    public static Logger getRootLogger() { return Logger.getLogger("root"); }
}
