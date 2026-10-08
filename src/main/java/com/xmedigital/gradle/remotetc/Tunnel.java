package com.xmedigital.gradle.remotetc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * One ssh process that forwards the engine socket of the Remote host to a private local socket.
 * Owns a private directory (mode 0700, fresh per build, short name) and removes everything on {@link #close()}.
 */
final class Tunnel {

    static final String REMOTE_SOCKET = "/var/run/docker.sock";
    static final String DIR_PREFIX = "rtc-";

    /** Unix socket paths are limited to about 100 bytes; fall back to /tmp when the temp directory is long. */
    private static final int MAX_BASE_LENGTH = 70;
    private static final int MAX_CONNECT_TIMEOUT_SECONDS = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RemoteHostAddress address;
    private final String sshProgram;
    private final Path tmpBase;

    private Path dir;
    private Path socket;
    private Process process;
    private volatile boolean closing;
    private volatile boolean lost;
    private Thread shutdownHook;

    Tunnel(RemoteHostAddress address, String sshProgram, Path tmpBase) {
        this.address = address;
        this.sshProgram = sshProgram;
        this.tmpBase = tmpBase.toString().length() > MAX_BASE_LENGTH ? Path.of("/tmp") : tmpBase;
    }

    Path socket() {
        return socket;
    }

    RemoteHostAddress address() {
        return address;
    }

    /** Starts ssh and waits until the private socket is ready, ssh exits, or the deadline passes. */
    synchronized void open(Deadline deadline) {
        if (process != null) {
            throw new IllegalStateException("Tunnel already opened");
        }
        try {
            dir = createPrivateDirectory();
        } catch (IOException e) {
            throw new TunnelException("ssh_failed", "ssh_failed: " + address.display()
                + ": could not create the private socket directory: " + e.getMessage());
        }
        socket = dir.resolve("s");
        Path log = dir.resolve("ssh.log");
        try {
            process = new ProcessBuilder(command(deadline))
                .redirectInput(new File("/dev/null"))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        } catch (IOException e) {
            close();
            throw new TunnelException(SshFailure.Reason.SSH_NOT_INSTALLED.id(),
                SshFailure.notInstalled(address.display()).message());
        }
        // a build JVM stopped by a signal (Ctrl-C with --no-daemon, IDE stop) still removes ssh and the socket
        shutdownHook = new Thread(this::close, "remote-tc-tunnel-close");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            while (true) {
                if (Files.exists(socket)) {
                    watch(process);
                    return;
                }
                if (!process.isAlive()) {
                    SshFailure failure = SshFailure.classify(address.display(), readLog(log));
                    throw new TunnelException(failure.reason().id(), failure.message());
                }
                if (deadline.expired()) {
                    throw new TunnelException("setup_timeout", "setup_timeout: " + address.display()
                        + ": setup did not finish within " + deadline.limit().toSeconds() + " s");
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new TunnelException("setup_timeout", "setup_timeout: " + address.display() + ": setup was interrupted");
        } catch (TunnelException e) {
            close();
            throw e;
        }
    }

    /** An exit that nobody asked for marks the Tunnel as lost. Nothing reconnects. */
    private void watch(Process p) {
        p.onExit().thenRun(() -> {
            if (!closing) {
                lost = true;
            }
        });
    }

    boolean isLost() {
        return lost;
    }

    /** Throws {@code tunnel_lost} naming the Remote host when ssh exited without being closed. */
    void checkAlive() {
        if (lost) {
            throw new TunnelException("tunnel_lost", "tunnel_lost: the Tunnel to " + address.display()
                + " was lost; the remaining Test tasks are stopped and nothing continues on this machine");
        }
    }

    private List<String> command(Deadline deadline) {
        long connectTimeout = Math.max(1, Math.min(MAX_CONNECT_TIMEOUT_SECONDS, deadline.remaining().toSeconds()));
        List<String> cmd = new ArrayList<>(List.of(sshProgram, "-N",
            "-o", "BatchMode=yes",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "ConnectTimeout=" + connectTimeout,
            "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=3",
            "-o", "ControlMaster=no",
            "-o", "ControlPath=none"));
        cmd.addAll(address.portOption());
        cmd.add("-L");
        cmd.add(socket + ":" + REMOTE_SOCKET);
        cmd.add("--");
        cmd.add(address.destination());
        return cmd;
    }

    private Path createPrivateDirectory() throws IOException {
        while (true) {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            Path candidate = tmpBase.resolve(DIR_PREFIX + HexFormat.of().formatHex(bytes));
            try {
                return Files.createDirectory(candidate, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException e) {
                // a stale or foreign entry is never reused: draw another name
            }
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Stops ssh and deletes the socket and the directory. Safe to call twice. */
    synchronized void close() {
        closing = true;
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // the JVM is already shutting down: the hook is running or about to
            }
            shutdownHook = null;
        }
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (dir != null) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                // nothing more can be done at build end
            }
        }
    }
}
