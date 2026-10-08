package com.xmedigital.gradle.remotetc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/**
 * Runs a fixture project with a Test task under Gradle TestKit, with a fake ssh first on the PATH.
 * The Remote host address is supplied through the test's own Gradle user home (per-user settings), the
 * switch on the command line. Each runner has its own state directory, so parallel tests do not collide.
 */
final class RemoteTcRunner implements AutoCloseable {

    static final String PLUGIN_ID = "com.xmedigital.gradle.remotetc";
    static final String DEFAULT_HOST = "ssh://dev@fake-host";

    /** A test that records what the Test JVM sees, into build/env.txt of its own project. */
    static final String SAMPLE_TEST = """
        import java.nio.file.Files;
        import java.nio.file.Path;
        import org.junit.jupiter.api.Test;
        class SampleTest {
            @Test void runs() throws Exception {
                String ds = System.getenv("DOCKER_HOST");
                boolean exists = ds != null && ds.startsWith("unix://") && Files.exists(Path.of(ds.substring(7)));
                Files.createDirectories(Path.of("build"));
                Files.writeString(Path.of("build/env.txt"), "DOCKER_HOST=" + ds
                    + "\\nSOCKET_OVERRIDE=" + System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")
                    + "\\nHOST_OVERRIDE=" + System.getenv("TESTCONTAINERS_HOST_OVERRIDE")
                    + "\\nexists=" + exists + "\\n");
            }
        }
        """;

    private final Path base;
    private final Path project;
    private final Path userHome;
    private final Path tmpDir;
    private final FakeSsh ssh;

    private String host = DEFAULT_HOST;
    private boolean remoteSwitch = true;
    private boolean configurationCache;
    private String gradleVersion;
    private boolean sshOnPath = true;
    private Integer setupTimeoutSeconds;
    private final List<String> extraArguments = new ArrayList<>();
    private final Map<String, String> extraEnvironment = new HashMap<>();

    private RemoteTcRunner(Path base, FakeSsh ssh) throws IOException {
        this.base = base;
        this.project = Files.createDirectories(base.resolve("project"));
        this.userHome = Files.createDirectories(base.resolve("user-home"));
        this.tmpDir = Files.createDirectories(base.resolve("tmp"));
        this.ssh = ssh;
    }

    static RemoteTcRunner create(FakeSsh.Mode mode, FakeEngine.Mode engine) throws IOException {
        // short path: Unix socket paths are limited to about 100 bytes
        Path base = Files.createTempDirectory(Path.of("/tmp"), "rtch-");
        RemoteTcRunner runner = new RemoteTcRunner(base, FakeSsh.install(base.resolve("fake"), mode, engine, 2));
        runner.writeFixture();
        return runner;
    }

    RemoteTcRunner host(String value) {
        this.host = value;
        return this;
    }

    RemoteTcRunner withoutHost() {
        this.host = null;
        return this;
    }

    RemoteTcRunner withoutSwitch() {
        this.remoteSwitch = false;
        return this;
    }

    RemoteTcRunner configurationCache(boolean on) {
        this.configurationCache = on;
        return this;
    }

    RemoteTcRunner gradleVersion(String version) {
        this.gradleVersion = version;
        return this;
    }

    RemoteTcRunner withoutSshOnPath() {
        this.sshOnPath = false;
        return this;
    }

    /** Shortens the setup time limit through the test-only system property of the Gradle daemon. */
    RemoteTcRunner setupTimeoutSeconds(int seconds) {
        this.setupTimeoutSeconds = seconds;
        return this;
    }

    RemoteTcRunner arguments(String... args) {
        extraArguments.addAll(List.of(args));
        return this;
    }

    RemoteTcRunner environment(String name, String value) {
        extraEnvironment.put(name, value);
        return this;
    }

    FakeSsh ssh() {
        return ssh;
    }

    Path project() {
        return project;
    }

    Path tmpDir() {
        return tmpDir;
    }

    /** Replaces a file of the fixture project (relative path). */
    RemoteTcRunner file(String relative, String content) throws IOException {
        Path target = project.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return this;
    }

    /** Adds a subproject with its own Test task and the sample test. */
    RemoteTcRunner subproject(String name) throws IOException {
        Path settings = project.resolve("settings.gradle");
        Files.writeString(settings, Files.readString(settings) + "include '" + name + "'\n");
        file(name + "/build.gradle", "plugins { id 'java' }\nrepositories { mavenCentral() }\n"
            + "dependencies {\n    testImplementation files(" + jarList() + ")\n    testRuntimeOnly files(" + jarList() + ")\n}\n"
            + "test { useJUnitPlatform() }\n");
        file(name + "/src/test/java/SampleTest.java", SAMPLE_TEST);
        return this;
    }

    /** The environment the Test JVM of a project recorded, as name to value. */
    java.util.Map<String, String> recordedEnvironment(String projectDir) throws IOException {
        java.util.Map<String, String> env = new java.util.TreeMap<>();
        Path file = project.resolve(projectDir.isEmpty() ? "build/env.txt" : projectDir + "/build/env.txt");
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            env.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return env;
    }

    /** Replaces the fixture's build script (the default applies the plugin by id). */
    RemoteTcRunner buildScript(String content) throws IOException {
        return file("build.gradle", "buildscript { dependencies { classpath files('" + FakeSsh.codeSource(RemoteHostAddress.class)
            + "') } }\nplugins { id 'java' }\nrepositories { mavenCentral() }\n"
            + "dependencies {\n    testImplementation files(" + jarList() + ")\n    testRuntimeOnly files(" + jarList() + ")\n}\n"
            + "test { useJUnitPlatform() }\n" + content);
    }

    BuildResult run(String... tasks) throws IOException {
        return runner(tasks).build();
    }

    BuildResult runAndFail(String... tasks) throws IOException {
        return runner(tasks).buildAndFail();
    }

    GradleRunner runner(String... tasks) throws IOException {
        writeUserHome();
        List<String> args = new ArrayList<>(List.of(tasks));
        args.add("--stacktrace");
        if (remoteSwitch) {
            args.add("-PremoteTc");
        }
        args.add(configurationCache ? "--configuration-cache" : "--no-configuration-cache");
        args.addAll(extraArguments);
        Map<String, String> env = new HashMap<>(System.getenv());
        String path = env.getOrDefault("PATH", "/usr/bin:/bin");
        env.put("PATH", sshOnPath ? ssh.binDir() + ":" + path : path.replace("/usr/bin", "/nonexistent-usr-bin"));
        env.putAll(extraEnvironment);
        GradleRunner runner = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withTestKitDir(userHome.toFile())
            .withPluginClasspath()
            .withEnvironment(env)
            .withArguments(args);
        if (gradleVersion != null) {
            runner.withGradleVersion(gradleVersion);
        }
        return runner;
    }

    /** Per-user settings: the host address, and a private temp directory so leftovers can be found. */
    private void writeUserHome() throws IOException {
        StringBuilder props = new StringBuilder();
        props.append("org.gradle.jvmargs=-Xmx512m -Djava.io.tmpdir=").append(tmpDir);
        if (setupTimeoutSeconds != null) {
            props.append(" -D").append(RemoteTcSetupTask.TIMEOUT_PROPERTY).append('=').append(setupTimeoutSeconds);
        }
        props.append('\n');
        if (host != null) {
            props.append("remoteTc.host=").append(host).append('\n');
        }
        Files.writeString(userHome.resolve("gradle.properties"), props.toString());
    }

    private void writeFixture() throws IOException {
        String jars = jarList();
        file("settings.gradle", "rootProject.name = 'fixture'\n");
        file("build.gradle", "plugins {\n    id 'java'\n    id '" + PLUGIN_ID + "'\n}\n"
            + "repositories { mavenCentral() }\n"
            + "dependencies {\n    testImplementation files(" + jars + ")\n    testRuntimeOnly files(" + jars + ")\n}\n"
            + "test { useJUnitPlatform() }\n");
        file("src/test/java/SampleTest.java", SAMPLE_TEST);
    }

    private static String jarList() throws IOException {
        return junitJars().stream().map(j -> "'" + j + "'").collect(Collectors.joining(", "));
    }

    /** The JUnit jars the functional tests themselves run on, handed to the fixture so it needs no download. */
    private static List<String> junitJars() throws IOException {
        List<String> jars = new ArrayList<>();
        for (String name : List.of("org.junit.jupiter.api.Test", "org.junit.jupiter.engine.JupiterTestEngine",
            "org.junit.platform.engine.TestEngine", "org.junit.platform.commons.JUnitException",
            "org.junit.platform.launcher.Launcher", "org.opentest4j.AssertionFailedError",
            "org.junit.jupiter.params.ParameterizedTest")) {
            try {
                String jar = FakeSsh.codeSource(Class.forName(name));
                if (!jars.contains(jar)) {
                    jars.add(jar);
                }
            } catch (ClassNotFoundException e) {
                throw new IOException("JUnit class not on the functional test classpath: " + name, e);
            }
        }
        return jars;
    }

    @Override
    public void close() throws IOException {
        ssh.killAll();
        try (var walk = Files.walk(base)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
