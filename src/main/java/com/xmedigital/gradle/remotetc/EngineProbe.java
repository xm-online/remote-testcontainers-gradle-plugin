package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/** Checks that the container engine answers {@code GET /_ping} over the forwarded socket. */
final class EngineProbe {

    private static final byte[] REQUEST =
        "GET /_ping HTTP/1.0\r\nHost: docker\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** The socket file can appear a moment before ssh listens on it: retry a refused connect for this long. */
    private static final long CONNECT_GRACE_NANOS = 500_000_000L;

    private EngineProbe() {}

    /** A failed connect closes the channel, so every attempt opens a new one. */
    private static SocketChannel connect(Path socket, long giveUpAt) throws IOException {
        while (true) {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(UnixDomainSocketAddress.of(socket));
                return channel;
            } catch (IOException e) {
                channel.close();
                if (System.nanoTime() >= giveUpAt) {
                    throw e;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Returns when the engine answers 200. Any other outcome inside {@code remaining} (no socket, refused,
     * silent, not 200) throws {@link EngineSilentException}: a non-200 answer is treated as not answering,
     * because a Test JVM could not use that engine either. Never blocks past {@code remaining}.
     */
    static void ping(String host, Path socket, Duration remaining) {
        long deadline = System.nanoTime() + Math.max(0L, remaining.toNanos());
        long giveUpAt = Math.min(deadline, System.nanoTime() + CONNECT_GRACE_NANOS);
        try (SocketChannel channel = connect(socket, giveUpAt); Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            ByteBuffer out = ByteBuffer.wrap(REQUEST);
            while (out.hasRemaining()) {
                channel.write(out);
                if (System.nanoTime() >= deadline) {
                    throw silent(host, "no answer in time");
                }
            }
            channel.register(selector, SelectionKey.OP_READ);
            ByteBuffer in = ByteBuffer.allocate(512);
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw silent(host, "no answer in time");
                }
                selector.select(Math.max(1L, left / 1_000_000L));
                selector.selectedKeys().clear();
                int n = channel.read(in);
                String head = new String(in.array(), 0, in.position(), StandardCharsets.US_ASCII);
                int eol = head.indexOf('\n');
                if (eol >= 0) {
                    if (head.startsWith("HTTP/") && head.split(" ", 3).length > 1 && head.split(" ", 3)[1].equals("200")) {
                        return;
                    }
                    throw silent(host, "unexpected answer: " + head.substring(0, eol).strip());
                }
                if (n < 0 || !in.hasRemaining()) {
                    throw silent(host, "connection closed without an answer");
                }
            }
        } catch (IOException e) {
            throw silent(host, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private static EngineSilentException silent(String host, String detail) {
        return new EngineSilentException("engine_silent: " + host
            + ": the container service is not running or does not answer (" + detail + ")");
    }
}
