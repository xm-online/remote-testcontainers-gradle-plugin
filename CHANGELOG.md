# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.2]

### Added
- Domain verification procedure (com.xmedigital).

## [0.1.1]

### Added
- Gradle plugin `com.xmedigital.gradle.remotetc`: runs Testcontainers-based tests against a container engine on a remote host over the user's own `ssh`.
- Opt-in switch `-PremoteTc`; without it the plugin does nothing. Remote host is read from `remoteTc.host` (`ssh://user@host[:port]`) in per-user Gradle settings.
- One private ssh Tunnel per build, closed at build end (pass, fail or cancel).
- `DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` and `TESTCONTAINERS_HOST_OVERRIDE` set for every Test task.
- Hidden `remoteTcSetup` task that validates the host, opens the Tunnel and checks the engine within 30 s.
- Named failure reasons (`host_missing`, `host_invalid`, `access_denied`, `engine_silent`, `tunnel_lost` and others); tests never fall back to the local machine.
- Configuration cache support. Requires Gradle 8.1+, JDK 17+, macOS or Linux.

### Known limits
- No Windows, included builds, ssh-config aliases, tcp/TLS addresses, rootless Docker or Podman.
