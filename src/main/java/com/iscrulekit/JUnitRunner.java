package com.iscrulekit;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code iscrule junit [folder]}: compiles every {@code *Test.java} under the folder against the kit and JUnit 4, then
 * runs each @Test method and prints one line per test. JUnit is found in ./lib or the local Maven repository, so no
 * build tool or IDE setup is needed. Uses reflection so the kit itself does not depend on JUnit.
 */
final class JUnitRunner {
    private JUnitRunner() {}

    static int run(Path folder, PrintStream out) throws IOException {
        List<Path> tests;
        if (Files.isRegularFile(folder)) tests = List.of(folder);
        else try (Stream<Path> s = Files.walk(folder)) {
            tests = s.filter(Files::isRegularFile).filter(JUnitGen::isTestFile).sorted().toList();
        }
        if (tests.isEmpty()) {
            out.println("No *Test.java files under " + folder + ". Create them with: iscrule import <rule.xml>  or  iscrule junit-gen <rule>");
            return 2;
        }
        List<Path> jars = junitJars();
        if (jars.isEmpty()) throw new Main.UsageException("JUnit 4 not found. Put junit-4.x.jar and hamcrest-core-1.3.jar in the lib folder, "
                + "or run 'mvn dependency:get -Dartifact=junit:junit:4.13.2' once.");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new Main.UsageException("no Java compiler available: run the kit with a JDK, not a JRE");

        Path classes = Files.createTempDirectory("iscrule-junit");
        String cp = System.getProperty("java.class.path") + File.pathSeparator
                + String.join(File.pathSeparator, jars.stream().map(Path::toString).toList());
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", cp, "-encoding", "UTF-8", "-nowarn", "-proc:none"));
        tests.forEach(t -> args.add(t.toString()));
        ByteArrayOutputStream errs = new ByteArrayOutputStream();
        int rc = javac.run(null, errs, errs, args.toArray(new String[0]));
        if (rc != 0) {
            out.println(Ansi.red("The test classes do not compile:"));
            out.println(errs.toString(StandardCharsets.UTF_8));
            return 1;
        }

        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (Path j : jars) urls.add(j.toUri().toURL());
        int pass = 0, fail = 0;
        out.println(Ansi.bold("JUNIT") + "  " + folder + Ansi.dim("  (" + tests.size() + " test class" + (tests.size() == 1 ? "" : "es") + ")"));
        out.println(Ansi.dim("-".repeat(Report.WIDTH)));
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), JUnitRunner.class.getClassLoader())) {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> testAnn = (Class<? extends Annotation>) cl.loadClass("org.junit.Test");
            Class<?> core = cl.loadClass("org.junit.runner.JUnitCore");
            Class<?> request = cl.loadClass("org.junit.runner.Request");
            Method method = request.getMethod("method", Class.class, String.class);
            Method runReq = core.getMethod("run", request);
            Object junit = core.getConstructor().newInstance();
            for (Path t : tests) {
                String name = t.getFileName().toString().replace(".java", "");
                Class<?> cls = cl.loadClass(packageOf(t) + name);
                out.println("  " + Ansi.bold(name));
                List<Method> ms = new ArrayList<>();
                for (Method m : cls.getMethods()) if (m.isAnnotationPresent(testAnn)) ms.add(m);
                ms.sort(Comparator.comparing(Method::getName));
                for (Method m : ms) {
                    Object result = runReq.invoke(junit, method.invoke(null, cls, m.getName()));
                    int failures = (int) result.getClass().getMethod("getFailureCount").invoke(result);
                    if (failures == 0) {
                        pass++;
                        out.println("    " + Ansi.green("PASS") + "  " + m.getName());
                    } else {
                        fail++;
                        out.println("    " + Ansi.red("FAIL") + "  " + m.getName());
                        @SuppressWarnings("unchecked")
                        List<Object> fl = (List<Object>) result.getClass().getMethod("getFailures").invoke(result);
                        for (Object f : fl) {
                            String msg = String.valueOf(f.getClass().getMethod("getMessage").invoke(f));
                            for (String line : msg.split("\n")) for (String l : Report.wrap(line, Report.WIDTH - 10)) out.println("          " + l);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not run JUnit: " + e, e);
        } finally {
            deleteTree(classes);
        }
        out.println(Ansi.dim("-".repeat(Report.WIDTH)));
        out.println(Ansi.bold("RESULT") + "  " + Ansi.green(pass + " passed") + (fail > 0 ? ", " + Ansi.red(fail + " failed") : "")
                + Ansi.dim("  (" + (pass + fail) + " tests)"));
        return fail > 0 ? 1 : 0;
    }

    private static String packageOf(Path javaFile) throws IOException {
        for (String line : Files.readAllLines(javaFile, StandardCharsets.UTF_8)) {
            String l = line.trim();
            if (l.startsWith("package ")) return l.substring(8).replace(";", "").trim() + ".";
            if (l.startsWith("import ") || l.startsWith("public ")) break;
        }
        return "";
    }

    /** junit 4.x and hamcrest-core from ./lib, then from the local Maven repository (newest 4.x). */
    static List<Path> junitJars() throws IOException {
        List<Path> roots = new ArrayList<>(List.of(Path.of("lib"), Path.of("isc-rule-kit", "lib")));
        List<Path> found = new ArrayList<>();
        for (Path lib : roots) {
            if (!Files.isDirectory(lib)) continue;
            try (Stream<Path> s = Files.list(lib)) {
                s.filter(p -> p.getFileName().toString().matches("(junit-4\\..*|hamcrest-core-.*)\\.jar")).forEach(found::add);
            }
        }
        if (found.stream().anyMatch(p -> p.getFileName().toString().startsWith("junit"))) return found;
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path junit = newest(m2.resolve("junit/junit"), "junit-", "4.");
        Path ham = newest(m2.resolve("org/hamcrest/hamcrest-core"), "hamcrest-core-", "");
        List<Path> r = new ArrayList<>();
        if (junit != null) r.add(junit);
        if (ham != null) r.add(ham);
        return junit == null ? List.of() : r;
    }

    private static Path newest(Path dir, String prefix, String versionPrefix) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).map(v -> v.getFileName().toString())
                    .filter(v -> v.startsWith(versionPrefix))
                    .sorted(Comparator.comparing(JUnitRunner::versionKey).reversed())
                    .map(v -> dir.resolve(v).resolve(prefix + v + ".jar"))
                    .filter(Files::isRegularFile).findFirst().orElse(null);
        }
    }

    private static String versionKey(String v) {
        StringBuilder b = new StringBuilder();
        for (String part : v.split("\\D+")) if (!part.isEmpty()) b.append(String.format("%06d", Integer.parseInt(part)));
        return b.toString();
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException ignored) {
            // temp folder; leaving it behind is harmless
        }
    }
}
