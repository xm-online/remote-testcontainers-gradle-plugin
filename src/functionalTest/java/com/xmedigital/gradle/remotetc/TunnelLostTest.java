package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TunnelLostTest {

    private static final String HOST = "ssh://dev@fake-host";

    @TempDir
    Path base;

    private FakeSsh ssh;

    @AfterEach
    void tearDown() throws IOException {
        if (ssh != null) {
            ssh.killAll();
        }
    }

    private Tunnel open(FakeSsh.Mode mode, int exitAfterSeconds) throws IOException {
        ssh = FakeSsh.install(base.resolve("fake"), mode, FakeEngine.Mode.OK, exitAfterSeconds);
        Path tmp = Files.createDirectories(base.resolve("t"));
        Tunnel t = new Tunnel(RemoteHostAddress.parse(HOST), ssh.binDir().resolve("ssh").toString(), tmp);
        t.open(Deadline.after(Duration.ofSeconds(20)));
        return t;
    }

    private static void awaitLost(Tunnel t) throws InterruptedException {
        for (int i = 0; i < 200 && !t.isLost(); i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void unexpectedExitSetsTheLostFlagAndTheNextCheckNamesTheHost() throws Exception {
        Tunnel t = open(FakeSsh.Mode.EXIT_AFTER, 1);
        assertDoesNotThrow(t::checkAlive);
        awaitLost(t);
        assertTrue(t.isLost());
        TunnelException e = assertThrows(TunnelException.class, t::checkAlive);
        assertEquals("tunnel_lost", e.reasonId());
        assertTrue(e.getMessage().contains("tunnel_lost"));
        assertTrue(e.getMessage().contains(HOST), e.getMessage());
        assertTrue(e.getMessage().contains("lost"));
        t.close();
        Leftovers.assertNone(ssh, base.resolve("t"));
    }

    @Test
    void noReconnectAfterTheLoss() throws Exception {
        Tunnel t = open(FakeSsh.Mode.EXIT_AFTER, 1);
        awaitLost(t);
        Thread.sleep(500);
        assertEquals(1, ssh.calls().size(), "ssh started exactly once");
        t.close();
    }

    @Test
    void deliberateCloseDoesNotSetTheFlag() throws Exception {
        Tunnel t = open(FakeSsh.Mode.CONNECT_OK, 2);
        t.close();
        Thread.sleep(300);
        assertFalse(t.isLost());
        assertDoesNotThrow(t::checkAlive);
    }

    @Test
    void serviceCountsRunsFromManyThreads() throws Exception {
        TunnelCounter counter = new TunnelCounter();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread th = new Thread(() -> {
                for (int j = 0; j < 500; j++) {
                    counter.countRan();
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(4000, counter.ranCount());
    }
}
