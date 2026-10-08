package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestTaskWiringTest {

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static RemoteTcRunner runner() throws IOException {
        return RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK);
    }

    @Test
    void singleProjectGetsTheEngineEnvironmentTheTargetLineAndTheEndLine() throws Exception {
        try (RemoteTcRunner r = runner()) {
            String out = r.run("test").getOutput();
            Map<String, String> env = r.recordedEnvironment("");
            assertTrue(env.get("DOCKER_HOST").startsWith("unix://"), env.toString());
            assertTrue(env.get("DOCKER_HOST").contains("/" + Leftovers.SOCKET_DIR_PREFIX), env.toString());
            assertEquals("/var/run/docker.sock", env.get("SOCKET_OVERRIDE"));
            assertEquals("fake-host", env.get("HOST_OVERRIDE"));
            assertEquals("true", env.get("exists"), "the private socket existed while the tests ran");
            String target = "[remote-tc] Container target: remote " + RemoteTcRunner.DEFAULT_HOST;
            assertEquals(1, count(out, target), out);
            assertTrue(out.indexOf(target) < out.indexOf("> Task :test"), "target before any test: " + out);
            assertEquals(1, count(out, "[remote-tc] 1 Test task(s) ran"), out);
            assertEquals(1, r.ssh().calls().size());
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void multiProjectBuildSharesOneTunnel() throws Exception {
        try (RemoteTcRunner r = runner().subproject("a").subproject("b")) {
            String out = r.run("test").getOutput();
            assertEquals(1, r.ssh().calls().size(), "one Tunnel for the whole build");
            Map<String, String> a = r.recordedEnvironment("a");
            Map<String, String> b = r.recordedEnvironment("b");
            Map<String, String> root = r.recordedEnvironment("");
            assertEquals(a.get("DOCKER_HOST"), b.get("DOCKER_HOST"));
            assertEquals(a.get("DOCKER_HOST"), root.get("DOCKER_HOST"));
            assertEquals(1, count(out, "Container target"), out);
            assertEquals(1, count(out, "[remote-tc] 3 Test task(s) ran"), out);
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void everyTestTaskUpToDateMeansNoClaimOfAContainer() throws Exception {
        try (RemoteTcRunner r = runner().configurationCache(true)) {
            r.run("test");
            String second = r.run("test").getOutput();
            String third = r.run("test").getOutput();
            for (String out : new String[] {second, third}) {
                assertEquals(1, count(out, "[remote-tc] Container target: remote " + RemoteTcRunner.DEFAULT_HOST), out);
                assertEquals(1, count(out, "[remote-tc] No Test task ran, no container started"), out);
                assertFalse(out.contains("Test task(s) ran"), out);
            }
            assertEquals(3, r.ssh().calls().size(), "setup checks ran at the start of every build");
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void secondRunWithTheConfigurationCacheStillSetsAFreshEnvironmentAndPrintsTheEndLine() throws Exception {
        try (RemoteTcRunner r = runner().configurationCache(true)) {
            String first = r.run("cleanTest", "test").getOutput();
            String firstSocket = r.recordedEnvironment("").get("DOCKER_HOST");
            String second = r.run("cleanTest", "test").getOutput();
            String secondSocket = r.recordedEnvironment("").get("DOCKER_HOST");
            assertTrue(second.contains("Reusing configuration cache"), second);
            assertEquals(1, count(first, "[remote-tc] 1 Test task(s) ran"), first);
            assertEquals(1, count(second, "[remote-tc] 1 Test task(s) ran"), second);
            assertNotEquals(firstSocket, secondSocket, "a socket per build, never shared");
        }
    }

    @Test
    void aLostTunnelStopsTheRemainingTestTasks() throws Exception {
        try (RemoteTcRunner r = RemoteTcRunner.create(FakeSsh.Mode.EXIT_AFTER, FakeEngine.Mode.OK)
                .subproject("a").subproject("b")) {
            r.ssh().configure(FakeSsh.Mode.EXIT_AFTER, FakeEngine.Mode.OK, 2);
            r.file("a/src/test/java/SampleTest.java", RemoteTcRunner.SAMPLE_TEST.replace(
                "Files.createDirectories", "Thread.sleep(6000);\n        Files.createDirectories"));
            r.file("b/build.gradle", Files.readString(r.project().resolve("b/build.gradle"))
                + "test.dependsOn(':a:test')\n");
            String out = r.runAndFail("b:test").getOutput();
            assertTrue(out.contains("tunnel_lost"), out);
            assertTrue(out.contains(RemoteTcRunner.DEFAULT_HOST), out);
            assertTrue(Files.exists(r.project().resolve("a/build/env.txt")), "the running test was not killed");
            assertFalse(Files.exists(r.project().resolve("b/build/env.txt")), "the next Test task did not start");
            assertEquals(1, r.ssh().calls().size(), "no silent reconnect");
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void excludingSetupFailsWithANamedReasonAndNothingRuns() throws Exception {
        try (RemoteTcRunner r = runner()) {
            String out = r.runAndFail("test", "-x", "remoteTcSetup").getOutput();
            assertTrue(out.contains("setup_not_run"), out);
            assertFalse(out.contains("NullPointerException"), out);
            assertFalse(java.nio.file.Files.exists(r.project().resolve("build/env.txt")), "no Test JVM ran");
            assertEquals(0, r.ssh().calls().size());
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    /** A Kotlin compile task is not an AbstractCompile; it must still wait for setup. */
    @Test
    void kotlinCompileTasksAreOrderedAfterSetup() throws Exception {
        try (RemoteTcRunner r = runner()) {
            r.file("build.gradle", r.project().resolve("build.gradle").toFile().exists()
                ? java.nio.file.Files.readString(r.project().resolve("build.gradle"))
                    + "\nclass KotlinCompile extends DefaultTask {\n"
                    + "    @TaskAction void go() { println 'KOTLIN-COMPILE-RAN' }\n}\n"
                    + "tasks.register('kc', KotlinCompile)\n"
                : "");
            String out = r.run("kc", "remoteTcSetup").getOutput();
            int target = out.indexOf("Container target");
            int kotlin = out.indexOf("KOTLIN-COMPILE-RAN");
            assertTrue(target >= 0 && kotlin >= 0, out);
            assertTrue(target < kotlin, "setup before the Kotlin compile: " + out);
        }
    }
}
