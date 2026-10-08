package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * A fake {@code ssh} executable. {@link #install} writes a launcher script into a directory that the
 * build under test puts first on its PATH. The launcher runs {@link Main}, which records its arguments and
 * behaves per mode. When the forward ({@code -L local:remote}) is accepted, it serves a {@link FakeEngine}
 * on the local socket path.
 */
final class FakeSsh {

    enum Mode { CONNECT_OK, DENIED, HOST_KEY, UNRESOLVED, TIMEOUT, REFUSED, HANG, EXIT_AFTER }

    /** Upper bound on how long any fake ssh process lives, so a broken plugin cannot hang the suite. */
    static final int LIFETIME_BOUND_SECONDS = 90;

    private final Path binDir;
    private final Path stateDir;

    private FakeSsh(Path binDir, Path stateDir) {
        this.binDir = binDir;
        this.stateDir = stateDir;
    }

    /** Installs into {@code baseDir/bin}; state (mode, calls, pids) lives in {@code baseDir/state}. */
    static FakeSsh install(Path baseDir, Mode mode, FakeEngine.Mode engine, int exitAfterSeconds) throws IOException {
        Path bin = Files.createDirectories(baseDir.resolve("bin"));
        Path state = Files.createDirectories(baseDir.resolve("state"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // the test worker's java.class.path is only the worker jar: use where this class was loaded from
        String classes = codeSource(FakeSsh.class);
        String script = "#!/bin/sh\nexec '" + java + "' -cp '" + classes
            + "' 'com.xmedigital.gradle.remotetc.FakeSsh$Main' '" + state + "' \"$@\"\n";
        Path ssh = bin.resolve("ssh");
        Files.writeString(ssh, script);
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwxr-xr-x"));
        FakeSsh fake = new FakeSsh(bin, state);
        fake.configure(mode, engine, exitAfterSeconds);
        return fake;
    }

    /** Where a class was loaded from (a classes directory or a jar). */
    static String codeSource(Class<?> type) throws IOException {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    void configure(Mode mode, FakeEngine.Mode engine, int exitAfterSeconds) throws IOException {
        Files.writeString(stateDir.resolve("config"), mode + "\n" + engine + "\n" + exitAfterSeconds + "\n");
    }

    Path binDir() {
        return binDir;
    }

    /** One entry per invocation, each a list of the arguments ssh was given. */
    List<List<String>> calls() throws IOException {
        Path log = stateDir.resolve("calls");
        List<List<String>> calls = new ArrayList<>();
        if (!Files.exists(log)) {
            return calls;
        }
        List<String> current = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.equals("--END--")) {
                calls.add(current);
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        return calls;
    }

    /** Pids of every fake ssh process that was started. */
    List<Long> pids() throws IOException {
        Path log = stateDir.resolve("pids");
        List<Long> pids = new ArrayList<>();
        if (Files.exists(log)) {
            for (String line : Files.readAllLines(log)) {
                if (!line.isBlank()) {
                    pids.add(Long.parseLong(line.strip()));
                }
            }
        }
        return pids;
    }

    /** Kills any fake ssh still alive (used by the harness when a test ends, whatever its outcome). */
    void killAll() throws IOException {
        for (long pid : pids()) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /** The process entry point of the fake. */
    public static final class Main {

        private Main() {}

        public static void main(String[] args) throws Exception {
            Path state = Path.of(args[0]);
            List<String> given = List.of(args).subList(1, args.length);
            List<String> config = Files.readAllLines(state.resolve("config"));
            Mode mode = Mode.valueOf(config.get(0));
            FakeEngine.Mode engine = FakeEngine.Mode.valueOf(config.get(1));
            int exitAfter = Integer.parseInt(config.get(2));

            Files.writeString(state.resolve("pids"), ProcessHandle.current().pid() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            StringBuilder log = new StringBuilder();
            for (String a : given) {
                log.append(a).append('\n');
            }
            log.append("--END--\n");
            Files.writeString(state.resolve("calls"), log.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            String target = given.get(given.size() - 1);
            switch (mode) {
                case DENIED -> fail(target + ": Permission denied (publickey,password).");
                case HOST_KEY -> fail("Host key verification failed.");
                case UNRESOLVED -> fail("ssh: Could not resolve hostname " + target + ": nodename nor servname provided, or not known");
                case TIMEOUT -> fail("ssh: connect to host " + target + " port 22: Operation timed out");
                case REFUSED -> fail("ssh: connect to host " + target + " port 22: Connection refused");
                case HANG -> Thread.sleep(LIFETIME_BOUND_SECONDS * 1000L);
                case CONNECT_OK, EXIT_AFTER -> connect(given, engine, mode == Mode.EXIT_AFTER ? exitAfter : -1);
            }
        }

        private static void fail(String text) {
            System.err.println(text);
            System.exit(255);
        }

        private static void connect(List<String> given, FakeEngine.Mode engine, int exitAfterSeconds) throws Exception {
            Path local = null;
            for (int i = 0; i < given.size() - 1; i++) {
                if (given.get(i).equals("-L")) {
                    local = Path.of(given.get(i + 1).substring(0, given.get(i + 1).indexOf(':')));
                }
            }
            if (local == null) {
                fail("fake ssh: no -L forward given");
                return;
            }
            FakeEngine server = new FakeEngine(local, engine);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // best effort, like ssh
                }
            }));
            long lifetime = exitAfterSeconds >= 0 ? exitAfterSeconds : LIFETIME_BOUND_SECONDS;
            Thread.sleep(lifetime * 1000L);
            if (exitAfterSeconds >= 0) {
                System.err.println("fake ssh: connection lost");
                System.exit(255);
            }
        }

    }
}
