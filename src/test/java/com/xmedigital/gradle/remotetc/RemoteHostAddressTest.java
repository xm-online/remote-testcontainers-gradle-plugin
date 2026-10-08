package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteHostAddressTest {

    @Test
    void parsesUserAndHost() {
        RemoteHostAddress a = RemoteHostAddress.parse("ssh://dev@build-1.example.com");
        assertEquals("dev@build-1.example.com", a.destination());
        assertEquals("build-1.example.com", a.hostPart());
        assertEquals(List.of(), a.portOption());
        assertEquals("ssh://dev@build-1.example.com", a.display());
        assertFalse(a.ipv6());
    }

    @Test
    void parsesPort() {
        RemoteHostAddress a = RemoteHostAddress.parse("ssh://dev@host:2222");
        assertEquals(List.of("-p", "2222"), a.portOption());
        assertEquals("ssh://dev@host:2222", a.display());
        assertEquals("host", a.hostPart());
    }

    @Test
    void acceptsPortBounds() {
        assertEquals(List.of("-p", "1"), RemoteHostAddress.parse("ssh://a@b:1").portOption());
        assertEquals(List.of("-p", "65535"), RemoteHostAddress.parse("ssh://a@b:65535").portOption());
    }

    @Test
    void parsesBracketedIpv6() {
        RemoteHostAddress a = RemoteHostAddress.parse("ssh://dev@[2001:db8::1]:22");
        assertTrue(a.ipv6());
        assertEquals("dev@2001:db8::1", a.destination());
        assertEquals("2001:db8::1", a.hostPart());
        assertEquals("ssh://dev@[2001:db8::1]:22", a.display());
        assertEquals("ssh://dev@[::1]", RemoteHostAddress.parse("ssh://dev@[::1]").display());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "dev@host",
        "myhost",
        "tcp://host:2375",
        "tls://host:2376",
        "http://dev@host",
        "ssh://host",
        "ssh://@host",
        "ssh://dev@",
        "ssh://",
        "",
        "ssh://-dev@host",
        "ssh://dev@-host",
        "ssh://de v@host",
        "ssh://dev@ho$t",
        "ssh://dev@host;rm",
        "ssh://dev@host:0",
        "ssh://dev@host:65536",
        "ssh://dev@host:abc",
        "ssh://dev@host:",
        "ssh://dev@::1",
        "ssh://dev@2001:db8::1",
        "ssh://dev@[::1",
        "ssh://dev@[]",
        "ssh://dev@[zz]",
        "ssh://dev@host/path",
        "ssh://dev@host extra",
    })
    void rejectsEverythingElse(String value) {
        HostAddressException e = assertThrows(HostAddressException.class, () -> RemoteHostAddress.parse(value));
        assertTrue(e.getMessage().contains("\"" + value + "\""), e.getMessage());
        assertTrue(e.getMessage().contains("ssh://user@host"), e.getMessage());
    }

    @Test
    void nullIsRejected() {
        assertThrows(HostAddressException.class, () -> RemoteHostAddress.parse(null));
    }

    @Test
    void plainNetworkAddressesAreSaidToBeRefused() {
        HostAddressException e = assertThrows(HostAddressException.class, () -> RemoteHostAddress.parse("tcp://host:2375"));
        assertTrue(e.getMessage().contains("plain network addresses are refused"), e.getMessage());
    }
}
