package com.xmedigital.gradle.remotetc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed {@code ssh://user@host[:port]}. Pure: no Gradle imports. */
final class RemoteHostAddress {

    static final String ACCEPTED_FORM = "ssh://user@host[:port] (IPv6 host in brackets)";

    private static final String NAME = "[A-Za-z0-9._][A-Za-z0-9._-]*";
    private static final Pattern ADDRESS = Pattern.compile(
        "ssh://(?<user>" + NAME + ")@(?:(?<host>" + NAME + ")|\\[(?<v6>[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*)])(?::(?<port>[0-9]{1,5}))?");

    private final String user;
    private final String host;
    private final Integer port;
    private final boolean ipv6;

    private RemoteHostAddress(String user, String host, Integer port, boolean ipv6) {
        this.user = user;
        this.host = host;
        this.port = port;
        this.ipv6 = ipv6;
    }

    static RemoteHostAddress parse(String value) {
        if (value == null) {
            throw reject("null");
        }
        Matcher m = ADDRESS.matcher(value);
        if (!m.matches()) {
            throw reject(value);
        }
        boolean v6 = m.group("v6") != null;
        Integer port = null;
        if (m.group("port") != null) {
            port = Integer.valueOf(m.group("port"));
            if (port < 1 || port > 65535) {
                throw reject(value);
            }
        }
        return new RemoteHostAddress(m.group("user"), v6 ? m.group("v6") : m.group("host"), port, v6);
    }

    private static HostAddressException reject(String value) {
        return new HostAddressException(
            "host_invalid: remoteTc.host is \"" + value + "\", accepted form is " + ACCEPTED_FORM
                + "; plain network addresses are refused");
    }

    String destination() {
        return user + "@" + host;
    }

    /** Extra ssh arguments for the port, empty when none was given. */
    List<String> portOption() {
        return port == null ? List.of() : List.of("-p", String.valueOf(port));
    }

    String hostPart() {
        return host;
    }

    boolean ipv6() {
        return ipv6;
    }

    String display() {
        String h = ipv6 ? "[" + host + "]" : host;
        return "ssh://" + user + "@" + h + (port == null ? "" : ":" + port);
    }
}
