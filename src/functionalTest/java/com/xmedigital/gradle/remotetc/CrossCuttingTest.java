package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CrossCuttingTest {

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static RemoteTcRunner runner() throws IOException {
        return RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK);
    }

    private static boolean closed(Path tmp) throws IOException {
        try (Stream<Path> entries = Files.list(tmp)) {
            return entries.noneMatch(p -> p.getFileName().toString().startsWith(Leftovers.SOCKET_DIR_PREFIX));
        }
    }

    /** AC-11: two builds at once, each on its own Tunnel; ending one does not break the other. */
    @Test
    void twoBuildsAtTheSameTimeAreIndependent() throws Exception {
        try (RemoteTcRunner a = runner(); RemoteTcRunner b = runner()) {
            // B's test waits until build A has ended (its Tunnel closed), then records whether its socket still exists
            String waitForA = "for (int i = 0; i < 600 && !java.nio.file.Files.exists(java.nio.file.Path.of(\""
                + a.project() + "/build/env.txt\")); i++) Thread.sleep(100);\n"
                + "        for (int i = 0; i < 600 && java.nio.file.Files.list(java.nio.file.Path.of(\"" + a.tmpDir()
                + "\")).anyMatch(p -> p.getFileName().toString().startsWith(\"rtc-\")); i++) Thread.sleep(100);\n        ";
            b.file("src/test/java/SampleTest.java", RemoteTcRunner.SAMPLE_TEST.replace(
                "String ds = System.getenv", waitForA + "String ds = System.getenv"));
            CompletableFuture<String> runB = CompletableFuture.supplyAsync(() -> {
                try {
                    return b.run("test").getOutput();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            String outA = a.run("test").getOutput();
            String outB = runB.get(180, TimeUnit.SECONDS);
            assertEquals(1, count(outA, "[remote-tc] 1 Test task(s) ran"), outA);
            assertEquals(1, count(outB, "[remote-tc] 1 Test task(s) ran"), outB);
            assertNotEquals(a.recordedEnvironment("").get("DOCKER_HOST"), b.recordedEnvironment("").get("DOCKER_HOST"));
            assertEquals("true", b.recordedEnvironment("").get("exists"),
                "B's Tunnel was still up after A ended and closed its own");
            Leftovers.assertNone(a.ssh(), a.tmpDir());
            Leftovers.assertNone(b.ssh(), b.tmpDir());
        }
    }

    /** AC-12: the Developer interrupts the build (Ctrl-C); nothing is left behind. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingTheBuildLeavesNoSshProcessAndNoSocketFile(boolean withDaemon) throws Exception {
        Path distribution = findGradle();
        Assumptions.assumeTrue(distribution != null, "no unpacked Gradle distribution found in ~/.gradle/wrapper/dists");
        try (RemoteTcRunner r = runner()) {
            Path started = r.project().resolve("build/started");
            r.file("src/test/java/SampleTest.java", RemoteTcRunner.SAMPLE_TEST.replace(
                "String ds = System.getenv",
                "Files.createDirectories(Path.of(\"build\"));\n        Files.writeString(Path.of(\"build/started\"), \"x\");\n"
                    + "        Thread.sleep(120_000);\n        String ds = System.getenv"));
            r.runner("help"); // writes the per-user settings into the user home
            r.file("build.gradle", "buildscript { dependencies { classpath files('" + FakeSsh.codeSource(RemoteHostAddress.class)
                + "') } }\napply plugin: 'java'\napply plugin: com.xmedigital.gradle.remotetc.RemoteTcPlugin\n"
                + Files.readString(r.project().resolve("build.gradle")).replaceAll("(?s)^.*?dependencies \\{", "dependencies {"));
            Path userHome = r.project().getParent().resolve("user-home");
            String path = r.ssh().binDir() + File.pathSeparator + System.getenv("PATH");
            ProcessBuilder pb = new ProcessBuilder(distribution.resolve("bin/gradle").toString(), "-g", userHome.toString(),
                "-p", r.project().toString(), withDaemon ? "--daemon" : "--no-daemon", "-PremoteTc", "test", "--console=plain");
            pb.environment().put("PATH", path);
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
            pb.redirectErrorStream(true).redirectOutput(r.project().getParent().resolve("cancel.log").toFile());
            Process build = pb.start();
            try {
                for (int i = 0; i < 600 && !Files.exists(started) && build.isAlive(); i++) {
                    Thread.sleep(100);
                }
                assertTrue(Files.exists(started), "build did not reach the tests:\n"
                    + Files.readString(r.project().getParent().resolve("cancel.log")));
                assertFalse(r.ssh().pids().isEmpty());
                new ProcessBuilder("kill", "-INT", String.valueOf(build.pid())).start().waitFor();
                assertTrue(build.waitFor(90, TimeUnit.SECONDS), "build did not stop after the interrupt");
            } finally {
                build.destroyForcibly();
            }
            try {
                for (int i = 0; i < 300 && !Leftovers.find(r.ssh(), r.tmpDir()).isEmpty(); i++) {
                    Thread.sleep(100);
                }
                Leftovers.assertNone(r.ssh(), r.tmpDir());
            } catch (AssertionError e) {
                Process ps = new ProcessBuilder("sh", "-c", "ps -eo pid,ppid,etime,command | grep -i -E 'gradle|FakeSsh' | grep -v grep | cut -c1-220").start();
                String procs = new String(ps.getInputStream().readAllBytes());
                throw new AssertionError(e.getMessage() + "\nPROCS:\n" + procs + "\n" + Files.readString(r.project().getParent().resolve("cancel.log")), e);
            } finally {
                if (withDaemon) {
                    new ProcessBuilder(distribution.resolve("bin/gradle").toString(), "-g", userHome.toString(), "--stop")
                        .redirectErrorStream(true).start().waitFor();
                }
            }
        }
    }

    private static Path findGradle() throws IOException {
        Path dists = Path.of(System.getProperty("user.home"), ".gradle/wrapper/dists");
        if (!Files.isDirectory(dists)) {
            return null;
        }
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(dists, "gradle-9.2.1-*")) {
            for (Path version : versions) {
                try (Stream<Path> walk = Files.walk(version, 4)) {
                    List<Path> found = walk.filter(p -> p.endsWith("bin/gradle")).toList();
                    if (!found.isEmpty()) {
                        return found.get(0).getParent().getParent();
                    }
                }
            }
        }
        return null;
    }

    /** AC-13 and the Gradle floor: first run, then every Test task up to date, configuration cache on. */
    @ParameterizedTest
    @ValueSource(strings = {"8.1", "9.2.1"})
    void skippedRunsOnTheLowestAndTheCurrentGradle(String gradleVersion) throws Exception {
        try (RemoteTcRunner r = runner().gradleVersion(gradleVersion).configurationCache(true)) {
            String first = r.run("test").getOutput();
            String second = r.run("test").getOutput();
            String third = r.run("test").getOutput();
            assertEquals(1, count(first, "[remote-tc] 1 Test task(s) ran"), first);
            for (String out : List.of(second, third)) {
                assertEquals(1, count(out, "[remote-tc] Container target: remote " + RemoteTcRunner.DEFAULT_HOST), out);
                assertEquals(1, count(out, "[remote-tc] No Test task ran, no container started"), out);
            }
            assertTrue(third.contains("Reusing configuration cache"), third);
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    /** AC-06 echo: whatever the outcome, the project tree never holds the address. */
    @Test
    void noRunLeavesTheAddressInTheProjectTree() throws Exception {
        String address = "ssh://dev@secret-box.example.test";
        try (RemoteTcRunner r = runner().host(address)) {
            r.run("test");
            r.ssh().configure(FakeSsh.Mode.DENIED, FakeEngine.Mode.OK, 2);
            r.runAndFail("test");
            try (Stream<Path> walk = Files.walk(r.project())) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(f -> !f.toString().contains("/.gradle/") && !f.endsWith("build/env.txt"))::iterator) {
                    String content = Files.readString(p, java.nio.charset.StandardCharsets.ISO_8859_1);
                    assertFalse(content.contains("secret-box.example.test"), p + " holds the address");
                }
            }
        }
    }

    @Test
    void theSocketDirectoryCannotBeOpenedByOtherAccounts() throws Exception {
        try (RemoteTcRunner r = runner().subproject("a")) {
            r.file("a/src/test/java/SampleTest.java", RemoteTcRunner.SAMPLE_TEST.replace(
                "String ds = System.getenv",
                "Files.writeString(Path.of(\"build-perms.txt\"), Files.getPosixFilePermissions(Path.of(System.getenv(\"DOCKER_HOST\")"
                    + ".substring(7)).getParent()).toString());\n        String ds = System.getenv"));
            r.run("a:test");
            String perms = Files.readString(r.project().resolve("a/build-perms.txt"));
            assertEquals("[OWNER_READ, OWNER_WRITE, OWNER_EXECUTE]", perms.replaceAll("\\s+", " ").trim().replace("[OWNER_WRITE, OWNER_READ", "[OWNER_READ, OWNER_WRITE"));
            Map<String, String> env = r.recordedEnvironment("a");
            assertTrue(env.get("DOCKER_HOST").startsWith("unix://"));
        }
    }
}
