---
status: Living
updated_at: "2026-10-07"
---

# Domain Context: remote-testcontainers-gradle-plugin

## Glossary

- Developer: a person who runs a project's container-based tests from their own machine and owns the remote host they use. NOT the project maintainer, who applies the plugin and sets project policy.
- Local mode: the default run, where test containers start on the Developer's own machine. NOT a fallback; remote mode never degrades into it.
- Remote host: the Developer's own machine, reachable over ssh, running the container engine used in remote mode. NOT a managed cloud service and not shared between Developers.
- Remote mode: a run the Developer explicitly selects with the build switch, where test containers start on the remote host; never automatic.
- Test task: a Gradle task that runs a project's tests; in remote mode every Test task is routed to the remote host unless excluded. NOT the build as a whole.
- Tunnel: an ssh connection owned by the build for one run that forwards the remote host's engine socket to a local socket. NOT the path tests use to reach container ports, which is direct.
- Per-user settings: the Developer's own Gradle user home properties file, outside any project, where the remote host address is kept. NOT the project's gradle.properties or any other file in the repository.
