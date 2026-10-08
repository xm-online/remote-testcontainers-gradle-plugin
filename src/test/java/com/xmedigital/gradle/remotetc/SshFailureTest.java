package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SshFailureTest {

    private static final String HOST = "ssh://dev@box.example.com";

    @Test
    void permissionDeniedIsAccessDenied() {
        SshFailure f = SshFailure.classify(HOST, "dev@box.example.com: Permission denied (publickey,password).");
        assertEquals(SshFailure.Reason.ACCESS_DENIED, f.reason());
        assertEquals("access_denied", f.reason().id());
        assertTrue(f.message().contains(HOST));
        assertTrue(f.message().contains("access refused by the Remote host"));
    }

    @Test
    void hostKeyIsUntrustedAndShowsSshText() {
        String text = "Host key verification failed.";
        SshFailure f = SshFailure.classify(HOST, text);
        assertEquals(SshFailure.Reason.HOST_UNTRUSTED, f.reason());
        assertTrue(f.message().contains(text));
        assertTrue(f.message().contains(HOST));
        assertFalse(f.message().toLowerCase().contains("accept-new"));
        assertFalse(f.message().contains("StrictHostKeyChecking"));
    }

    @Test
    void changedHostKeyIsUntrusted() {
        SshFailure f = SshFailure.classify(HOST, "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!\nHost key verification failed.");
        assertEquals(SshFailure.Reason.HOST_UNTRUSTED, f.reason());
    }

    @Test
    void unresolvedIsUnreachable() {
        SshFailure f = SshFailure.classify(HOST, "ssh: Could not resolve hostname box.example.com: nodename nor servname provided, or not known");
        assertEquals(SshFailure.Reason.HOST_UNREACHABLE, f.reason());
        assertEquals("host_unreachable", f.reason().id());
        assertTrue(f.message().contains(HOST));
        assertTrue(f.message().contains("could not be resolved"));
    }

    @Test
    void timeoutIsUnreachable() {
        SshFailure f = SshFailure.classify(HOST, "ssh: connect to host box.example.com port 22: Operation timed out");
        assertEquals(SshFailure.Reason.HOST_UNREACHABLE, f.reason());
        assertTrue(f.message().contains("timed out"));
    }

    @Test
    void connectionTimedOutLinuxWording() {
        SshFailure f = SshFailure.classify(HOST, "ssh: connect to host box port 22: Connection timed out");
        assertEquals(SshFailure.Reason.HOST_UNREACHABLE, f.reason());
        assertTrue(f.message().contains("timed out"));
    }

    @Test
    void refusedIsUnreachable() {
        SshFailure f = SshFailure.classify(HOST, "ssh: connect to host box.example.com port 22: Connection refused");
        assertEquals(SshFailure.Reason.HOST_UNREACHABLE, f.reason());
        assertTrue(f.message().contains("refused"));
        assertTrue(f.message().contains(HOST));
    }

    @Test
    void notInstalledIsSignalledByTheCaller() {
        SshFailure f = SshFailure.notInstalled(HOST);
        assertEquals(SshFailure.Reason.SSH_NOT_INSTALLED, f.reason());
        assertEquals("ssh_not_installed", f.reason().id());
        assertTrue(f.message().contains("ssh is not installed"));
        assertTrue(f.message().contains(HOST));
    }

    @Test
    void unknownTextIsShownRaw() {
        SshFailure f = SshFailure.classify(HOST, "something odd happened");
        assertEquals(SshFailure.Reason.SSH_FAILED, f.reason());
        assertEquals("ssh_failed", f.reason().id());
        assertTrue(f.message().contains("ssh failed: something odd happened"));
        assertTrue(f.message().contains(HOST));
    }

    @Test
    void emptyTextIsUnknown() {
        assertEquals(SshFailure.Reason.SSH_FAILED, SshFailure.classify(HOST, "").reason());
        assertEquals(SshFailure.Reason.SSH_FAILED, SshFailure.classify(HOST, null).reason());
    }

    @Test
    void everyMessageNamesTheReasonId() {
        for (String text : new String[] {"Permission denied", "Host key verification failed.", "Connection refused", "x"}) {
            SshFailure f = SshFailure.classify(HOST, text);
            assertTrue(f.message().contains(f.reason().id()), f.message());
        }
    }
}
