package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class HarnessSelfTest {

    @TempDir
    Path dir;

    private Process runFakeSsh(FakeSsh ssh, String... args) throws IOException {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ssh.binDir().resolve("ssh").toString());
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    private static String ping(Path socket) throws IOException {
        try (SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            c.connect(UnixDomainSocketAddress.of(socket));
            c.write(ByteBuffer.wrap("GET /_ping HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            ByteBuffer in = ByteBuffer.allocate(256);
            c.read(in);
            return new String(in.array(), 0, in.position(), StandardCharsets.US_ASCII);
        }
    }

    private static void waitFor(Path file) throws InterruptedException {
        for (int i = 0; i < 100 && !Files.exists(file); i++) {
            Thread.sleep(100);
        }
    }

    @Test
    @SuppressWarnings("try")
    void fakeEngineAnswersPing() throws Exception {
        Path sock = dir.resolve("e.sock");
        try (FakeEngine ignored = new FakeEngine(sock, FakeEngine.Mode.OK)) {
            assertTrue(ping(sock).startsWith("HTTP/1.1 200"));
        }
        assertFalse(Files.exists(sock));
    }

    @Test
    void fakeSshRecordsEveryFlagAndForwardsTheSocket() throws Exception {
        FakeSsh ssh = FakeSsh.install(dir, FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK, 2);
        Path sock = dir.resolve("s.sock");
        List<String> args = List.of("-N", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes", "-o", "ConnectTimeout=20",
            "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-o", "ControlMaster=no", "-o", "ControlPath=none",
            "-L", sock + ":/var/run/docker.sock", "--", "dev@fake-host");
        Process p = runFakeSsh(ssh, args.toArray(new String[0]));
        try {
            waitFor(sock);
            assertTrue(ping(sock).startsWith("HTTP/1.1 200"));
            assertEquals(1, ssh.calls().size());
            assertEquals(args, ssh.calls().get(0));
            assertEquals(1, ssh.pids().size());
            assertEquals(p.pid(), ssh.pids().get(0));
        } finally {
            p.destroy();
            p.waitFor();
        }
        assertFalse(Files.exists(sock), "socket removed when terminated");
    }

    @Test
    void fakeSshFailureModesPrintSshText() throws Exception {
        String[][] cases = {
            {"DENIED", "Permission denied"}, {"HOST_KEY", "Host key verification failed"},
            {"UNRESOLVED", "Could not resolve hostname"}, {"TIMEOUT", "timed out"}, {"REFUSED", "Connection refused"}};
        for (String[] c : cases) {
            FakeSsh ssh = FakeSsh.install(Files.createTempDirectory(dir, "m"), FakeSsh.Mode.valueOf(c[0]), FakeEngine.Mode.OK, 2);
            Process p = runFakeSsh(ssh, "-N", "--", "dev@fake-host");
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(255, p.waitFor(), c[0]);
            assertTrue(out.contains(c[1]), c[0] + ": " + out);
        }
    }

    @Test
    void exitAfterModeExitsOnItsOwn() throws Exception {
        FakeSsh ssh = FakeSsh.install(dir, FakeSsh.Mode.EXIT_AFTER, FakeEngine.Mode.OK, 1);
        Process p = runFakeSsh(ssh, "-L", dir.resolve("x.sock") + ":/var/run/docker.sock", "--", "dev@h");
        assertTrue(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(255, p.exitValue());
    }

    @Test
    void hangModeIsKilledByTheHarness() throws Exception {
        FakeSsh ssh = FakeSsh.install(dir, FakeSsh.Mode.HANG, FakeEngine.Mode.OK, 2);
        Process p = runFakeSsh(ssh, "-N", "--", "dev@h");
        Thread.sleep(500);
        assertTrue(p.isAlive());
        ssh.killAll();
        assertTrue(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void leftoverCheckIsCleanWhenNothingIsLeft() throws Exception {
        FakeSsh ssh = FakeSsh.install(dir, FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK, 2);
        assertDoesNotThrow(() -> Leftovers.assertNone(ssh, Files.createDirectories(dir.resolve("tmp"))));
    }

    @Test
    void leftoverCheckFailsOnAPlantedProcessAndSocketDirectory() throws Exception {
        FakeSsh ssh = FakeSsh.install(dir, FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK, 2);
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Process planted = new ProcessBuilder("sleep", "60").start();
        try {
            Files.writeString(dir.resolve("state").resolve("pids"), planted.pid() + "\n", StandardOpenOption.CREATE);
            Files.createDirectory(tmp.resolve(Leftovers.SOCKET_DIR_PREFIX + "planted"));
            AssertionError e = assertThrows(AssertionError.class, () -> Leftovers.assertNone(ssh, tmp));
            assertTrue(e.getMessage().contains("ssh process still alive"), e.getMessage());
            assertTrue(e.getMessage().contains("socket directory left"), e.getMessage());
        } finally {
            planted.destroyForcibly();
        }
    }

    @Test
    void runnerWritesPerUserSettingsAndNotTheProject() throws Exception {
        try (RemoteTcRunner r = RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK)) {
            r.runner("help");
            String props = Files.readString(r.project().getParent().resolve("user-home/gradle.properties"));
            assertTrue(props.contains("remoteTc.host=" + RemoteTcRunner.DEFAULT_HOST));
            assertFalse(Files.exists(r.project().resolve("gradle.properties")));
            assertFalse(Files.readString(r.project().resolve("build.gradle")).contains("remoteTc"));
        }
    }

    @Test
    void runnersDoNotShareState() throws Exception {
        try (RemoteTcRunner a = RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK);
             RemoteTcRunner b = RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK)) {
            assertNotEquals(a.project(), b.project());
            assertNotEquals(a.tmpDir(), b.tmpDir());
        }
    }
}
