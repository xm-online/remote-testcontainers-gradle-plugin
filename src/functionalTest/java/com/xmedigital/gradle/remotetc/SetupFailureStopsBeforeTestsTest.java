package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** AC-02, 03, 04, 05, 09, 14: when setup fails, the build stops before any Test task starts. */
class SetupFailureStopsBeforeTestsTest {

    private static void assertStoppedBeforeTests(RemoteTcRunner r, BuildResult result, String reason) {
        assertTrue(result.getOutput().contains(reason), result.getOutput());
        assertNull(result.task(":test"), "the Test task never started: " + result.getOutput());
        assertEquals(TaskOutcome.FAILED, result.task(":remoteTcSetup").getOutcome());
        assertFalse(Files.exists(r.project().resolve("build/env.txt")), "no Test JVM ran");
    }

    @Test
    void hostMissing() throws Exception {
        try (RemoteTcRunner r = RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK).withoutHost()) {
            assertStoppedBeforeTests(r, r.runAndFail("test"), "host_missing");
        }
    }

    @Test
    void hostInvalid() throws Exception {
        try (RemoteTcRunner r = RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK).host("tcp://box:2375")) {
            assertStoppedBeforeTests(r, r.runAndFail("test"), "host_invalid");
        }
    }

    @ParameterizedTest
    @EnumSource(value = FakeSsh.Mode.class, names = {"DENIED", "HOST_KEY", "UNRESOLVED"})
    void tunnelRefused(FakeSsh.Mode mode) throws Exception {
        try (RemoteTcRunner r = RemoteTcRunner.create(mode, FakeEngine.Mode.OK)) {
            String reason = switch (mode) {
                case DENIED -> "access_denied";
                case HOST_KEY -> "host_untrusted";
                default -> "host_unreachable";
            };
            assertStoppedBeforeTests(r, r.runAndFail("test"), reason);
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void engineSilent() throws Exception {
        try (RemoteTcRunner r = RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.SILENT).setupTimeoutSeconds(10)) {
            assertStoppedBeforeTests(r, r.runAndFail("test"), "engine_silent");
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }
}
