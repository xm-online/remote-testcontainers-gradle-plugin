package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineProbeTest {

    private static final String HOST = "ssh://dev@box";

    private Path dir;
    private Path socket;
    private ServerSocketChannel server;
    private Thread acceptor;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("ep");
        socket = dir.resolve("e.sock");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }

    private void serve(String answer) throws IOException {
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        acceptor = new Thread(() -> {
            try (SocketChannel c = server.accept()) {
                ByteBuffer in = ByteBuffer.allocate(256);
                c.read(in);
                if (answer == null) {
                    Thread.sleep(10_000);
                } else {
                    c.write(ByteBuffer.wrap(answer.getBytes(StandardCharsets.US_ASCII)));
                }
            } catch (Exception ignored) {
                // server closed by the test
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @Test
    void answeredWith200Passes() throws Exception {
        serve("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
        assertDoesNotThrow(() -> EngineProbe.ping(HOST, socket, Duration.ofSeconds(5)));
    }

    @Test
    void silentEngineFailsAtTheDeadline() throws Exception {
        serve(null);
        long start = System.nanoTime();
        EngineSilentException e = assertThrows(EngineSilentException.class,
            () -> EngineProbe.ping(HOST, socket, Duration.ofMillis(500)));
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(ms < 2000, "returned after " + ms + " ms");
        assertTrue(e.getMessage().contains("engine_silent"));
        assertTrue(e.getMessage().contains(HOST));
    }

    @Test
    void missingSocketFailsAtOnce() {
        long start = System.nanoTime();
        EngineSilentException e = assertThrows(EngineSilentException.class,
            () -> EngineProbe.ping(HOST, socket, Duration.ofSeconds(5)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        assertTrue(e.getMessage().contains(HOST));
    }

    @Test
    void refusedConnectionFailsAtOnce() throws Exception {
        serve("HTTP/1.1 200 OK\r\n\r\n");
        server.close();
        assertThrows(EngineSilentException.class, () -> EngineProbe.ping(HOST, socket, Duration.ofSeconds(5)));
    }

    @Test
    void non200IsNotAnswering() throws Exception {
        serve("HTTP/1.1 500 Internal Server Error\r\n\r\n");
        assertThrows(EngineSilentException.class, () -> EngineProbe.ping(HOST, socket, Duration.ofSeconds(5)));
    }

    @Test
    void noRemainingTimeFails() {
        assertThrows(EngineSilentException.class, () -> EngineProbe.ping(HOST, socket, Duration.ZERO));
    }
}
