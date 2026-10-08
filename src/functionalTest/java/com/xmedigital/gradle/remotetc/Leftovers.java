package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Asserts that a run left no ssh process and no private socket directory behind. */
final class Leftovers {

    /** Prefix of the plugin's private socket directories inside the build's temp directory. */
    static final String SOCKET_DIR_PREFIX = "rtc-";

    private Leftovers() {}

    static List<String> find(FakeSsh ssh, Path tmpDir) throws IOException {
        List<String> found = new ArrayList<>();
        for (long pid : ssh.pids()) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                found.add("ssh process still alive: pid " + pid);
            }
        }
        if (Files.isDirectory(tmpDir)) {
            try (Stream<Path> entries = Files.list(tmpDir)) {
                entries.filter(p -> p.getFileName().toString().startsWith(SOCKET_DIR_PREFIX))
                    .forEach(p -> found.add("socket directory left: " + p));
            }
        }
        return found;
    }

    static void assertNone(FakeSsh ssh, Path tmpDir) throws IOException {
        List<String> found = find(ssh, tmpDir);
        if (!found.isEmpty()) {
            throw new AssertionError("Leftovers after the run: " + found);
        }
    }
}
