package com.iscrulekit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code iscrule rdk-update}: installs or updates SailPoint's Rule Development Kit with git, keeping your work.
 * <ul>
 *   <li>No RDK yet: clones it into {@code ./rule-development-kit} (inside the kit folder).</li>
 *   <li>A git clone: {@code git pull} (fast-forward; other local changes are stashed and re-applied).</li>
 *   <li>A zip download (no .git): clones a fresh copy, copies every file of yours that upstream does not have
 *       (generated tests, rule XMLs, .bak files), and keeps the old folder as {@code <name>-before-update-<date>}.</li>
 * </ul>
 * Then it makes the RDK work on JDK 17 to 25 (Mockito 5.20.0 / Byte Buddy 1.17.7 when upstream pins older versions;
 * never downgrades) and runs the whole RDK test suite.
 */
final class RdkUpdate {
    private RdkUpdate() {}

    static final String REPO = "https://github.com/sailpoint-oss/rule-development-kit.git";

    static int run(String rdkOption, boolean test, PrintStream out) throws IOException {
        git(List.of("--version"), null, out, false);
        Path target;
        try {
            target = Rdk.findRdk(rdkOption);
        } catch (Main.UsageException e) {
            target = Path.of(rdkOption != null ? rdkOption : "rule-development-kit").toAbsolutePath().normalize();
        }
        out.println(Ansi.bold("RDK UPDATE") + "  " + target);
        Path pomPath = target.resolve("pom.xml");
        String pomBefore = Files.exists(pomPath) ? Files.readString(pomPath, StandardCharsets.UTF_8) : null;

        if (!Files.exists(target)) {
            out.println("  not installed yet: cloning " + REPO);
            git(List.of("clone", REPO, target.toString()), null, out, true);
        } else if (Files.isDirectory(target.resolve(".git"))) {
            pull(target, out);
        } else {
            convertZipDownload(target, out);
        }

        List<String> changes = patchPom(pomPath);
        if (changes.isEmpty() || Files.readString(pomPath, StandardCharsets.UTF_8).equals(pomBefore)) {
            out.println("  pom.xml     already set up for JDK 17-25");
            Files.deleteIfExists(target.resolve("pom.xml.before-update"));
        } else {
            for (String c : changes) out.println("  pom.xml     " + c);
        }

        if (!test) {
            out.println();
            out.println(Ansi.green("RDK is up to date: ") + target);
            return 0;
        }
        out.println();
        int rc = mavenAll(target, out);
        out.println();
        out.println(rc == 0 ? Ansi.green("RDK is up to date and all its tests pass: ") + target
                : Ansi.red("RDK updated, but some tests fail (see above). Your own rules' tests may need 'iscrule sync'."));
        return rc;
    }

    private static void pull(Path t, PrintStream out) throws IOException {
        String before = git(List.of("rev-parse", "--short", "HEAD"), t, out, false).trim();
        String pomStatus = git(List.of("status", "--porcelain", "--", "pom.xml"), t, out, false).trim();
        if (!pomStatus.isEmpty()) { // our Mockito fix: drop it (it is re-applied after the pull); keep a copy just in case
            Files.copy(t.resolve("pom.xml"), t.resolve("pom.xml.before-update"), StandardCopyOption.REPLACE_EXISTING);
            git(List.of("checkout", "--", "pom.xml"), t, out, true);
        }
        out.println("  git pull    " + REPO);
        String o = git(List.of("pull", "--ff-only", "--autostash"), t, out, true);
        String after = git(List.of("rev-parse", "--short", "HEAD"), t, out, false).trim();
        if (before.equals(after)) {
            out.println("              already up to date (" + after + ")");
        } else {
            out.println("              updated " + before + " -> " + after + ":");
            for (String line : git(List.of("log", "--oneline", before + ".." + after), t, out, false).split("\\R"))
                if (!line.isBlank()) out.println("                " + line);
        }
        if (o.contains("CONFLICT")) throw new Main.UsageException("git reported a conflict in " + t + ". Resolve it there (git status), then run rdk-update again.");
    }

    /** A zip download cannot be pulled: clone fresh, carry over the user's files, keep the old folder. */
    private static void convertZipDownload(Path old, PrintStream out) throws IOException {
        out.println("  " + old.getFileName() + " is not a git clone (probably a zip download): turning it into one");
        Path fresh = old.resolveSibling(old.getFileName() + ".updating");
        deleteTree(fresh);
        git(List.of("clone", REPO, fresh.toString()), null, out, true);
        int copied = 0;
        try (Stream<Path> s = Files.walk(old)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                Path rel = old.relativize(f);
                String first = rel.getName(0).toString();
                if (first.equals("target") || first.equals(".git") || rel.toString().equals("pom.xml")) continue;
                Path dest = fresh.resolve(rel);
                if (Files.exists(dest)) continue; // upstream has it: take upstream's version
                Files.createDirectories(dest.getParent());
                Files.copy(f, dest);
                copied++;
                if (copied <= 15) out.println("              kept " + rel);
            }
        }
        if (copied > 15) out.println("              ... and " + (copied - 15) + " more");
        Path backup = old.resolveSibling(old.getFileName() + "-before-update-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.move(old, backup);
        Files.move(fresh, old);
        out.println("              " + copied + " file(s) of yours kept; old folder saved as " + backup.getFileName());
        out.println("              next time, rdk-update just runs git pull");
    }

    // ---------------------------------------------------------------------------------------------------------------
    /** Raises Mockito / Byte Buddy to versions that run on JDK 17-25 (only when older), and configures surefire. */
    static List<String> patchPom(Path pom) throws IOException {
        String s = Files.readString(pom, StandardCharsets.UTF_8);
        List<String> changes = new ArrayList<>();
        s = atLeast(s, "mockito-core", "5.20.0", changes);
        s = atLeast(s, "byte-buddy", "1.17.7", changes);
        if (!s.contains("maven-surefire-plugin")) {
            String plugin = """
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>3.2.5</version>
                            <configuration>
                                <!-- added by iscrule: lets Mockito attach its agent on newer JDKs -->
                                <argLine>-XX:+EnableDynamicAgentLoading -Xshare:off</argLine>
                            </configuration>
                        </plugin>
                """;
            if (s.contains("<plugins>")) s = s.replaceFirst("<plugins>", "<plugins>\n" + Matcher.quoteReplacement(plugin));
            else s = s.replaceFirst("</project>", Matcher.quoteReplacement("    <build>\n        <plugins>\n" + plugin + "        </plugins>\n    </build>\n</project>"));
            changes.add("surefire configured for newer JDKs");
        }
        if (!changes.isEmpty()) Files.writeString(pom, s, StandardCharsets.UTF_8);
        return changes;
    }

    private static String atLeast(String pom, String artifact, String min, List<String> changes) {
        Matcher m = Pattern.compile("(<artifactId>" + Pattern.quote(artifact) + "</artifactId>\\s*<version>)([^<]+)(</version>)").matcher(pom);
        if (!m.find()) return pom;
        String v = m.group(2).trim();
        if (v.startsWith("${")) { // a property: change the property's value
            String prop = v.substring(2, v.length() - 1);
            Matcher pm = Pattern.compile("(<" + Pattern.quote(prop) + ">)([^<]+)(</" + Pattern.quote(prop) + ">)").matcher(pom);
            if (!pm.find() || compare(pm.group(2).trim(), min) >= 0) return pom;
            changes.add(artifact + " " + pm.group(2).trim() + " -> " + min);
            return pom.substring(0, pm.start(2)) + min + pom.substring(pm.end(2));
        }
        if (compare(v, min) >= 0) return pom;
        changes.add(artifact + " " + v + " -> " + min + " (works on JDK 17-25)");
        return pom.substring(0, m.start(2)) + min + pom.substring(m.end(2));
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\D+"), y = b.split("\\D+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------------------
    private static int mavenAll(Path rdk, PrintStream out) throws IOException {
        String mvn = Rdk.findMaven(rdk);
        Path log = rdk.resolve("target/iscrule-mvn.log");
        Files.createDirectories(log.getParent());
        out.println("  mvn test    all RDK tests " + Ansi.dim("(log: " + rdk.relativize(log) + ")"));
        out.flush();
        ProcessBuilder pb = new ProcessBuilder(mvn, "-B", "test").directory(rdk.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().merge("MAVEN_OPTS", "--enable-native-access=ALL-UNNAMED", (x, y) -> x + " " + y);
        int rc;
        try {
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new Main.UsageException("mvn test took longer than 20 minutes"); }
            rc = p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        if (Rdk.summarizeCompileErrors(rdk, lines, out)) return 1;
        Pattern perClass = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+).* in sailpoint\\.(\\w+)");
        for (String l : lines) {
            Matcher m = perClass.matcher(l);
            if (!m.find()) continue;
            boolean ok = m.group(2).equals("0") && m.group(3).equals("0");
            out.println("    " + (ok ? Ansi.green("PASS") : Ansi.red("FAIL")) + "  " + m.group(4) + Ansi.dim("  (" + m.group(1) + " tests"
                    + (ok ? "" : ", " + (Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3))) + " failing") + ")"));
        }
        for (String l : lines) if (l.matches(".*Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+\\s*$")) out.println("  " + l.replace("[INFO] ", "").replace("[ERROR] ", "").trim());
        return rc;
    }

    /** Runs git; returns its output. With {@code show}, failures stop the command with git's message. */
    private static String git(List<String> args, Path dir, PrintStream out, boolean show) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new Main.UsageException("git is not installed (or not on PATH). Install Git for Windows from https://git-scm.com and try again.");
        }
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0 && show) throw new Main.UsageException("git " + String.join(" ", args) + " failed:\n" + text.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return text;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                x.toFile().setWritable(true);
                Files.delete(x);
            }
        }
    }
}
