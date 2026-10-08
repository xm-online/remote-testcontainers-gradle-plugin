package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The setup task alone, registered by hand in the fixture (the plugin entry is tested in PluginEntryTest). */
class SetupTaskTest {

    static final String SETUP_ONLY = """
        def svc = gradle.sharedServices.registerIfAbsent('remoteTcTunnel', com.xmedigital.gradle.remotetc.TunnelService) {
            parameters.host = providers.gradleProperty('remoteTc.host')
        }
        tasks.register('remoteTcSetup', com.xmedigital.gradle.remotetc.RemoteTcSetupTask) {
            service = svc
            usesService(svc)
            host = providers.gradleProperty('remoteTc.host')
        }
        """;

    private static RemoteTcRunner runner(FakeSsh.Mode mode, FakeEngine.Mode engine) throws IOException {
        return RemoteTcRunner.create(mode, engine).setupTimeoutSeconds(4).buildScript(SETUP_ONLY);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void hostMissingStopsAndSaysItBelongsInPerUserSettings() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK).withoutHost()) {
            BuildResult result = r.runAndFail("remoteTcSetup");
            String out = result.getOutput();
            assertTrue(out.contains("host_missing"), out);
            assertTrue(out.contains("remoteTc.host"), out);
            assertTrue(out.contains("per-user settings"), out);
            assertTrue(out.contains("not in the project"), out);
            assertEquals(0, r.ssh().calls().size(), "no ssh started");
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void hostInvalidShowsTheValueAndTheAcceptedForm() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK).host("tcp://box:2375")) {
            String out = r.runAndFail("remoteTcSetup").getOutput();
            assertTrue(out.contains("host_invalid"), out);
            assertTrue(out.contains("tcp://box:2375"), out);
            assertTrue(out.contains("ssh://user@host"), out);
            assertEquals(0, r.ssh().calls().size());
        }
    }

    @Test
    void engineSilentStopsWithinTheLimitAndNamesTheHost() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.SILENT)) {
            long start = System.nanoTime();
            String out = r.runAndFail("remoteTcSetup").getOutput();
            assertTrue(out.contains("engine_silent"), out);
            assertTrue(out.contains(RemoteTcRunner.DEFAULT_HOST), out);
            assertTrue(out.contains("container service is not running or does not answer"), out);
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 60);
            assertEquals(0, count(out, "Container target"), "no target line when setup failed");
        }
    }

    @Test
    void tunnelFailureNamesTheHost() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.DENIED, FakeEngine.Mode.OK)) {
            String out = r.runAndFail("remoteTcSetup").getOutput();
            assertTrue(out.contains("access_denied"), out);
            assertTrue(out.contains(RemoteTcRunner.DEFAULT_HOST), out);
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void slowSshStopsWithSetupTimeout() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.HANG, FakeEngine.Mode.OK)) {
            String out = r.runAndFail("remoteTcSetup").getOutput();
            assertTrue(out.contains("setup_timeout"), out);
            assertTrue(out.contains(RemoteTcRunner.DEFAULT_HOST), out);
            assertTrue(out.contains("4 s"), out);
            Thread.sleep(300);
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void successPrintsTheTargetLineExactlyOnce() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK)) {
            String out = r.run("remoteTcSetup").getOutput();
            assertEquals(1, count(out, "[remote-tc] Container target: remote " + RemoteTcRunner.DEFAULT_HOST), out);
            assertEquals(1, r.ssh().calls().size());
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void alwaysRunsAndNeverUpToDate() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK)) {
            r.run("remoteTcSetup");
            String second = r.run("remoteTcSetup").getOutput();
            assertEquals(1, count(second, "Container target"), second);
            assertFalse(second.contains("UP-TO-DATE"), second);
            assertEquals(2, r.ssh().calls().size());
        }
    }

    @Test
    void taskHasNoGroup() throws Exception {
        try (RemoteTcRunner r = runner(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK)) {
            String out = r.run("tasks", "--all").getOutput();
            assertTrue(out.contains("remoteTcSetup"), out);
            String visible = r.run("tasks").getOutput();
            assertFalse(visible.contains("remoteTcSetup"), "hidden from the default task list: " + visible);
        }
    }
}
