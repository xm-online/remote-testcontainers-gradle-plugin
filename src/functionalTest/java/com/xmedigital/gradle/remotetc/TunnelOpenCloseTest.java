package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Tunnel against a fake ssh: success, every failure mode, leftovers, permissions, two at once. */
class TunnelOpenCloseTest {

    private static final String HOST = "ssh://dev@fake-host";

    @TempDir
    Path base;

    private Path tmp;
    private FakeSsh ssh;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createDirectory(base.resolve("t"));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (ssh != null) {
            ssh.killAll();
        }
    }

    private Tunnel tunnel(FakeSsh.Mode mode, String host) throws IOException {
        ssh = FakeSsh.install(base.resolve("fake" + System.nanoTime()), mode, FakeEngine.Mode.OK, 2);
        return new Tunnel(RemoteHostAddress.parse(host), ssh.binDir().resolve("ssh").toString(), tmp);
    }

    @Test
    void opensAndClosesLeavingNothing() throws Exception {
        Tunnel t = tunnel(FakeSsh.Mode.CONNECT_OK, HOST);
        t.open(Deadline.after(Duration.ofSeconds(20)));
        assertTrue(Files.exists(t.socket()), "socket ready");
        t.close();
        t.close(); // safe twice
        assertFalse(Files.exists(t.socket()));
        Leftovers.assertNone(ssh, tmp);
    }

    @Test
    void sshGetsTheLedgerFlags() throws Exception {
        Tunnel t = tunnel(FakeSsh.Mode.CONNECT_OK, "ssh://dev@fake-host:2222");
        t.open(Deadline.after(Duration.ofSeconds(20)));
        List<String> args = ssh.calls().get(0);
        t.close();
        assertEquals("-N", args.get(0));
        for (String o : List.of("BatchMode=yes", "ExitOnForwardFailure=yes", "ServerAliveInterval=15",
            "ServerAliveCountMax=3", "ControlMaster=no", "ControlPath=none")) {
            assertTrue(args.contains(o), o + " in " + args);
        }
        assertTrue(args.stream().anyMatch(a -> a.matches("ConnectTimeout=([1-9]|1[0-9]|20)")), args.toString());
        assertTrue(args.stream().noneMatch(a -> a.contains("StrictHostKeyChecking")));
        assertEquals(List.of("-p", "2222"), args.subList(args.indexOf("-p"), args.indexOf("-p") + 2));
        int dashes = args.indexOf("--");
        assertEquals(args.size() - 2, dashes, "-- right before the destination: " + args);
        assertEquals("dev@fake-host", args.get(args.size() - 1));
        int l = args.indexOf("-L");
        assertEquals(t.socket() + ":/var/run/docker.sock", args.get(l + 1));
    }

    @Test
    void socketDirectoryIsPrivate() throws Exception {
        Tunnel t = tunnel(FakeSsh.Mode.CONNECT_OK, HOST);
        t.open(Deadline.after(Duration.ofSeconds(20)));
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(t.socket().getParent());
        t.close();
        assertEquals(PosixFilePermissions.fromString("rwx------"), perms);
        assertTrue(t.socket().getParent().getFileName().toString().startsWith(Leftovers.SOCKET_DIR_PREFIX));
        assertTrue(t.socket().toString().length() < 100, "short socket path: " + t.socket());
    }

    private void assertFailure(FakeSsh.Mode mode, String reasonId, String... mustContain) throws Exception {
        Tunnel t = tunnel(mode, HOST);
        TunnelException e = assertThrows(TunnelException.class, () -> t.open(Deadline.after(Duration.ofSeconds(20))));
        assertEquals(reasonId, e.reasonId());
        assertTrue(e.getMessage().contains(reasonId), e.getMessage());
        assertTrue(e.getMessage().contains(HOST), e.getMessage());
        for (String s : mustContain) {
            assertTrue(e.getMessage().contains(s), e.getMessage());
        }
        t.close();
        Leftovers.assertNone(ssh, tmp);
    }

    @Test
    void accessRefused() throws Exception {
        assertFailure(FakeSsh.Mode.DENIED, "access_denied", "access refused by the Remote host");
    }

    @Test
    void hostNotTrusted() throws Exception {
        assertFailure(FakeSsh.Mode.HOST_KEY, "host_untrusted", "Host key verification failed");
    }

    @Test
    void unresolved() throws Exception {
        assertFailure(FakeSsh.Mode.UNRESOLVED, "host_unreachable");
    }

    @Test
    void timedOut() throws Exception {
        assertFailure(FakeSsh.Mode.TIMEOUT, "host_unreachable", "timed out");
    }

    @Test
    void refused() throws Exception {
        assertFailure(FakeSsh.Mode.REFUSED, "host_unreachable", "refused");
    }

    @Test
    void sshNotInstalled() throws Exception {
        Tunnel t = new Tunnel(RemoteHostAddress.parse(HOST), base.resolve("nowhere/ssh").toString(), tmp);
        TunnelException e = assertThrows(TunnelException.class, () -> t.open(Deadline.after(Duration.ofSeconds(20))));
        assertEquals("ssh_not_installed", e.reasonId());
        assertTrue(e.getMessage().contains(HOST));
        try (var left = Files.list(tmp)) {
            assertEquals(0, left.count(), "directory removed after a failed open");
        }
    }

    @Test
    void slowSshStopsAtTheDeadlineAndIsKilled() throws Exception {
        Tunnel t = tunnel(FakeSsh.Mode.HANG, HOST);
        long start = System.nanoTime();
        TunnelException e = assertThrows(TunnelException.class, () -> t.open(Deadline.after(Duration.ofSeconds(2))));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
        assertEquals("setup_timeout", e.reasonId());
        assertTrue(e.getMessage().contains(HOST));
        assertTrue(e.getMessage().contains("2 s"), e.getMessage());
        Thread.sleep(300);
        Leftovers.assertNone(ssh, tmp);
    }

    @Test
    void twoTunnelsAreIndependent() throws Exception {
        FakeSsh shared = FakeSsh.install(base.resolve("shared"), FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK, 2);
        ssh = shared;
        Tunnel a = new Tunnel(RemoteHostAddress.parse(HOST), shared.binDir().resolve("ssh").toString(), tmp);
        Tunnel b = new Tunnel(RemoteHostAddress.parse(HOST), shared.binDir().resolve("ssh").toString(), tmp);
        a.open(Deadline.after(Duration.ofSeconds(20)));
        b.open(Deadline.after(Duration.ofSeconds(20)));
        assertNotEquals(a.socket().getParent(), b.socket().getParent());
        a.close();
        assertFalse(Files.exists(a.socket()));
        assertTrue(Files.exists(b.socket()), "closing one build's Tunnel does not break the other");
        b.close();
        Leftovers.assertNone(shared, tmp);
    }
}
