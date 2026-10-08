package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A Unix-socket server that answers {@code GET /_ping} like a container engine. Modes: ok, silent. */
final class FakeEngine implements AutoCloseable {

    enum Mode { OK, SILENT }

    private final Path socket;
    private final ServerSocketChannel server;

    FakeEngine(Path socket, Mode mode) throws IOException {
        this.socket = socket;
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.server.bind(UnixDomainSocketAddress.of(socket));
        Thread acceptor = new Thread(() -> serve(mode), "fake-engine");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void serve(Mode mode) {
        while (server.isOpen()) {
            try {
                SocketChannel c = server.accept();
                Thread t = new Thread(() -> handle(c, mode), "fake-engine-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void handle(SocketChannel c, Mode mode) {
        try (c) {
            ByteBuffer in = ByteBuffer.allocate(1024);
            c.read(in);
            if (mode == Mode.SILENT) {
                Thread.sleep(120_000);
                return;
            }
            c.write(ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII)));
        } catch (IOException | InterruptedException ignored) {
            // connection or server closed
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        Files.deleteIfExists(socket);
    }
}
