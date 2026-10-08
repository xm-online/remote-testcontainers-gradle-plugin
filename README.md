# remote-testcontainers-gradle-plugin

Runs your Testcontainers-based Gradle tests against a container engine on a remote host, over your own `ssh`. One switch turns it on. Without the switch the plugin does nothing.

## Why

Testcontainers needs a local Docker engine. That is a problem when:

- the laptop is too small for heavy containers (databases, Kafka, Elasticsearch);
- Docker Desktop is not allowed, or licensing makes it costly;
- the machine is Apple Silicon and the images are amd64-only;
- the team already owns a shared or personal build server.

Existing options need a Docker API exposed on the network (`tcp://`, TLS) or a cloud service. This plugin needs only what you already have: key-based `ssh` access to a host running Docker. No daemon port is opened, no credentials are stored, no ssh trust settings are changed.

## Who it is for

Any Gradle project (Java, Kotlin, Spring Boot, Quarkus) that uses Testcontainers for Java and wants its tests to run containers on a remote machine. Nothing in the plugin is specific to one company or framework.

## Usage

1. Apply the plugin in the **root** project (applying it to a subproject stops the build with `not_root_project`).

   ```kotlin
   plugins {
       id("com.xmedigital.gradle.remotetc")
   }
   ```

2. Put the Remote host in your **per-user** Gradle settings, `~/.gradle/gradle.properties`. Never in the project:

   ```properties
   remoteTc.host=ssh://user@host.example.test
   ```

   Accepted form: `ssh://user@host[:port]`. User and host use `[A-Za-z0-9._-]` and do not start with `-`. Port 1 to 65535. IPv6 host in brackets, `ssh://user@[2001:db8::1]:22`. Not accepted: `user@host` without the scheme, ssh-config aliases, plain network addresses such as `tcp://host:2375`.

3. Run with the switch on the command line (or in the IDE's Gradle run configuration):

   ```
   ./gradlew test -PremoteTc
   ```

The switch counts only when given for the run. A `remoteTc` value found in a project file or the environment is ignored with one warning.

## What it does

- Opens one private ssh Tunnel per build to the engine socket on the Remote host. The plugin starts your own `ssh` in batch mode: no password is asked or stored, and your ssh trust settings are never changed.
- Every Test task in every project gets `DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` and `TESTCONTAINERS_HOST_OVERRIDE` in its environment. Nothing is written into the project.
- Adds a hidden task `remoteTcSetup` that every Test task depends on. It validates, opens the Tunnel and checks that the engine answers, within 30 s.
- Prints `[remote-tc] Container target: remote ssh://user@host` before any test result, and at the end `[remote-tc] N Test task(s) ran` or `[remote-tc] No Test task ran, no container started`. Only a Test task that executed counts: up-to-date, from-cache and no-source do not.
- Closes the Tunnel at build end (pass, fail or cancel). No ssh process or socket file is left behind. Two builds at once each get their own Tunnel.

When setup is wrong the build stops before any test with a named reason: `host_missing`, `host_invalid`, `ssh_not_installed`, `host_unreachable`, `access_denied`, `host_untrusted`, `ssh_failed`, `engine_silent`, `setup_timeout`, `setup_not_run`. If the Tunnel is lost during the run, the remaining Test tasks stop with `tunnel_lost`; nothing reconnects silently and nothing continues on your machine.

## Requirements

- Gradle 8.1 or newer, JDK 17 or newer. macOS and Linux (Unix-domain sockets, `ssh` on the `PATH`).
- OpenSSH 6.7 (October 2014) or newer on your machine, and on the Remote host. The plugin forwards a local Unix-domain socket to the engine socket (`ssh -L <local socket>:/var/run/docker.sock`), which OpenSSH supports since 6.7. Check with `ssh -V`.
- On the Remote host, `sshd` must allow Unix-domain socket forwarding: `AllowStreamLocalForwarding` is `yes` by default and must not be `no` or `remote`; `local` is enough. `AllowTcpForwarding no` also disables it, and `PermitOpen` or a `restrict` option on the key can block it. A refused forward is not reported by ssh when the Tunnel opens, so it typically shows up as `engine_silent`.
- The Remote user must be allowed to use the engine socket (for Docker: member of the `docker` group, or root).
- Testcontainers for Java on the test runtime classpath. The plugin only sets the environment of Test tasks (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, `TESTCONTAINERS_HOST_OVERRIDE`); Testcontainers itself must honour them. The Testcontainers documentation lists all three, but no minimum version is documented, and this plugin's tests use a fake engine, not a real Testcontainers run. The 1.x series is the expected target. Version 2.0.2 has an open upstream report that it ignores these overrides ([testcontainers-java#11254](https://github.com/testcontainers/testcontainers-java/issues/11254)); that is not verified here, so check your version first (see below).
- Key-based access to the Remote host that works without a prompt, and a host key your own ssh setup already trusts (`ssh user@host true` works from your shell).
- A container engine on the Remote host with its socket at `/var/run/docker.sock`. The path is fixed in v1 (rootless Docker and Podman are not supported yet).
- The ports of started containers must be reachable from your machine at the host part of the address (VPN, firewall). This is not checked yet: a blocked port shows up as a test failure, not a named reason.

## Checking your Testcontainers version

Run a small test that starts any container with `./gradlew test -PremoteTc`. It works when the container is started on the Remote host (`docker ps` there shows it) and the build prints `[remote-tc] Container target: remote ...`. If the container starts on your own machine, or the test fails with a message about `/var/run/docker.sock` not being found, your Testcontainers version ignores the overrides: try another version.

## Known limits

- Run tests with the **Gradle** runner. The IDE's own test runner bypasses the plugin and runs on your machine; the plugin cannot detect that.
- No Local mode enforcement, no project policy and no doctor task yet.
- A Test JVM already running when the Tunnel is lost is not killed; its tests fail on their own. A silent link loss is noticed after about 45 s (ssh keepalive).
- The plugin cannot tell where `remoteTc.host` came from: the standard Gradle property lookup applies, so a project file or `-P` can also supply it. The plugin itself never writes the address into any file.
- Not supported in v1: Windows, included builds, a configurable ssh program, ssh-config aliases, tcp and TLS addresses.

## License

Apache License 2.0, see [LICENSE](LICENSE).
