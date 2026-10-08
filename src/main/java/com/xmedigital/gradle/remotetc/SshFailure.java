package com.xmedigital.gradle.remotetc;

import java.util.List;
import java.util.Locale;

/** Names an ssh failure for the Developer. Table-driven: a new pattern is one row in {@link #PATTERNS}. */
final class SshFailure {

    enum Reason {
        SSH_NOT_INSTALLED("ssh_not_installed"),
        HOST_UNREACHABLE("host_unreachable"),
        ACCESS_DENIED("access_denied"),
        HOST_UNTRUSTED("host_untrusted"),
        SSH_FAILED("ssh_failed");

        private final String id;

        Reason(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    private record Pattern(String needle, Reason reason, String plain) {}

    /** Lower-case needle, reason, plain wording. First match wins; order matters (host key before denied). */
    private static final List<Pattern> PATTERNS = List.of(
        new Pattern("host key verification failed", Reason.HOST_UNTRUSTED, null),
        new Pattern("remote host identification has changed", Reason.HOST_UNTRUSTED, null),
        new Pattern("permission denied", Reason.ACCESS_DENIED, "access refused by the Remote host"),
        new Pattern("could not resolve hostname", Reason.HOST_UNREACHABLE, "the host name could not be resolved"),
        new Pattern("name or service not known", Reason.HOST_UNREACHABLE, "the host name could not be resolved"),
        new Pattern("timed out", Reason.HOST_UNREACHABLE, "the connection timed out"),
        new Pattern("connection refused", Reason.HOST_UNREACHABLE, "the connection was refused"),
        new Pattern("no route to host", Reason.HOST_UNREACHABLE, "no route to the host"),
        new Pattern("network is unreachable", Reason.HOST_UNREACHABLE, "the network is unreachable"));

    private final Reason reason;
    private final String message;

    private SshFailure(Reason reason, String message) {
        this.reason = reason;
        this.message = message;
    }

    static SshFailure notInstalled(String host) {
        return new SshFailure(Reason.SSH_NOT_INSTALLED,
            Reason.SSH_NOT_INSTALLED.id() + ": ssh is not installed, cannot reach " + host);
    }

    static SshFailure classify(String host, String sshOutput) {
        String text = sshOutput == null ? "" : sshOutput.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern p : PATTERNS) {
            if (lower.contains(p.needle())) {
                String detail = p.plain() != null ? p.plain() : text;
                return new SshFailure(p.reason(), p.reason().id() + ": " + host + ": " + detail);
            }
        }
        return new SshFailure(Reason.SSH_FAILED, Reason.SSH_FAILED.id() + ": " + host + ": ssh failed: " + text);
    }

    Reason reason() {
        return reason;
    }

    String message() {
        return message;
    }
}
