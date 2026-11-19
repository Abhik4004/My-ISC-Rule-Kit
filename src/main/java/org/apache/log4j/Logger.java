package org.apache.log4j;

import java.util.function.BiConsumer;

/**
 * Minimal stand-in for log4j 1.x's Logger (the type of the ISC {@code log} variable). Messages go to a sink so the test
 * report can show them. Loggers created via getLogger() are detached: creating your own logger is blocked in ISC anyway.
 */
public class Logger {
    private final String name;
    private final BiConsumer<String, String> sink;

    public Logger(String name, BiConsumer<String, String> sink) {
        this.name = name;
        this.sink = sink;
    }

    public static Logger getLogger(Class<?> c) { return new Logger(c == null ? "" : c.getName(), (l, m) -> { }); }
    public static Logger getLogger(String n) { return new Logger(n, (l, m) -> { }); }

    public String getName() { return name; }

    private void emit(String level, Object msg, Throwable t) {
        sink.accept(level, String.valueOf(msg) + (t == null ? "" : " | " + t));
    }

    public void trace(Object m) { emit("TRACE", m, null); }
    public void trace(Object m, Throwable t) { emit("TRACE", m, t); }
    public void debug(Object m) { emit("DEBUG", m, null); }
    public void debug(Object m, Throwable t) { emit("DEBUG", m, t); }
    public void info(Object m) { emit("INFO", m, null); }
    public void info(Object m, Throwable t) { emit("INFO", m, t); }
    public void warn(Object m) { emit("WARN", m, null); }
    public void warn(Object m, Throwable t) { emit("WARN", m, t); }
    public void error(Object m) { emit("ERROR", m, null); }
    public void error(Object m, Throwable t) { emit("ERROR", m, t); }
    public void fatal(Object m) { emit("FATAL", m, null); }
    public void fatal(Object m, Throwable t) { emit("FATAL", m, t); }

    public boolean isTraceEnabled() { return true; }
    public boolean isDebugEnabled() { return true; }
    public boolean isInfoEnabled() { return true; }
}
