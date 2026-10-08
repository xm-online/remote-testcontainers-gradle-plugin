package com.xmedigital.gradle.remotetc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;

class PluginEntryTest {

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static RemoteTcRunner runner() throws IOException {
        return RemoteTcRunner.create(FakeSsh.Mode.CONNECT_OK, FakeEngine.Mode.OK);
    }

    /** Every file of the project except Gradle's own work directories, with its content. */
    private static Map<String, String> snapshot(Path project) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(project)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = project.relativize(p).toString();
                if (!rel.startsWith("build" + java.io.File.separator) && !rel.startsWith(".gradle")) {
                    files.put(rel, Files.readString(p));
                }
            }
        }
        return files;
    }

    @Test
    void withoutTheSwitchNothingIsRegisteredOrPrinted() throws Exception {
        try (RemoteTcRunner r = runner().withoutSwitch()) {
            BuildResult result = r.run("test");
            assertFalse(result.getOutput().contains("[remote-tc]"), result.getOutput());
            assertFalse(r.run("tasks", "--all").getOutput().contains("remoteTcSetup"));
            assertEquals(0, r.ssh().calls().size(), "no Tunnel opened");
        }
    }

    @Test
    void explicitFalseOnTheCommandLineIsSilent() throws Exception {
        try (RemoteTcRunner r = runner().withoutSwitch().arguments("-PremoteTc=false")) {
            String out = r.run("tasks", "--all").getOutput();
            assertFalse(out.contains("[remote-tc]"), out);
            assertFalse(out.contains("remoteTcSetup"), out);
            assertEquals(0, r.ssh().calls().size());
        }
    }

    @Test
    void switchOnlyInAProjectFileIsIgnoredWithOneWarning() throws Exception {
        try (RemoteTcRunner r = runner().withoutSwitch()) {
            r.file("gradle.properties", "remoteTc=true\n");
            String out = r.run("tasks", "--all").getOutput();
            assertEquals(1, count(out, "[remote-tc]"), out);
            assertTrue(out.contains("ignored"), out);
            assertTrue(out.contains("command line"), out);
            assertFalse(out.contains("remoteTcSetup"));
            assertEquals(0, r.ssh().calls().size());
        }
    }

    @Test
    void switchOnlyInTheEnvironmentIsIgnoredWithOneWarning() throws Exception {
        try (RemoteTcRunner r = runner().withoutSwitch().environment("ORG_GRADLE_PROJECT_remoteTc", "true")) {
            String out = r.run("tasks", "--all").getOutput();
            assertEquals(1, count(out, "[remote-tc]"), out);
            assertTrue(out.contains("ignored"), out);
            assertFalse(out.contains("remoteTcSetup"));
        }
    }

    @Test
    void appliedToASubprojectStopsWithANamedReason() throws Exception {
        try (RemoteTcRunner r = runner().withoutSwitch()) {
            r.file("settings.gradle", "rootProject.name = 'fixture'\ninclude 'sub'\n");
            r.file("sub/build.gradle", "plugins { id '" + RemoteTcRunner.PLUGIN_ID + "' }\n");
            String out = r.runAndFail("help").getOutput();
            assertTrue(out.contains("not_root_project"), out);
            assertTrue(out.contains("root project"), out);
        }
    }

    @Test
    void withTheSwitchTheSetupTaskIsRegisteredAndRuns() throws Exception {
        try (RemoteTcRunner r = runner()) {
            String out = r.run("remoteTcSetup").getOutput();
            assertEquals(1, count(out, "[remote-tc] Container target: remote " + RemoteTcRunner.DEFAULT_HOST), out);
            assertEquals(1, r.ssh().calls().size());
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @Test
    void hostMissingNamesTheSettingAndThePerUserHome() throws Exception {
        try (RemoteTcRunner r = runner().withoutHost()) {
            String out = r.runAndFail("remoteTcSetup").getOutput();
            assertTrue(out.contains("host_missing"), out);
            assertTrue(out.contains("per-user settings"), out);
        }
    }

    @Test
    void theProjectTreeIsUnchangedAndNeverHoldsTheAddress() throws Exception {
        try (RemoteTcRunner r = runner()) {
            Map<String, String> before = snapshot(r.project());
            r.run("remoteTcSetup");
            Map<String, String> after = snapshot(r.project());
            assertEquals(before, after);
            for (String content : after.values()) {
                assertFalse(content.contains("fake-host"));
            }
        }
    }

    @Test
    void worksWithTheConfigurationCacheOnTwoRunsInARow() throws Exception {
        try (RemoteTcRunner r = runner().configurationCache(true)) {
            String first = r.run("remoteTcSetup").getOutput();
            String second = r.run("remoteTcSetup").getOutput();
            assertEquals(1, count(first, "Container target"), first);
            assertEquals(1, count(second, "Container target"), second);
            assertTrue(second.contains("Reusing configuration cache"), second);
            assertEquals(2, r.ssh().calls().size());
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"8.1", "9.2.1"})
    void smokeRunOnTheLowestSupportedAndTheCurrentGradle(String gradleVersion) throws Exception {
        try (RemoteTcRunner r = runner().gradleVersion(gradleVersion).configurationCache(true)) {
            String first = r.run("remoteTcSetup").getOutput();
            String second = r.run("remoteTcSetup").getOutput();
            assertEquals(1, count(first, "Container target"), first);
            assertEquals(1, count(second, "Container target"), second);
            assertTrue(second.contains("Reusing configuration cache"), second);
            Leftovers.assertNone(r.ssh(), r.tmpDir());
        }
    }
}
