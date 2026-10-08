package com.xmedigital.gradle.remotetc;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.tasks.testing.Test;
import org.jspecify.annotations.NonNull;

/** Routes every Test task of every project through setup, the engine environment and the lost-Tunnel guard. */
final class TestTaskWiring {

    static final String DOCKER_HOST = "DOCKER_HOST";
    static final String SOCKET_OVERRIDE = "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE";
    static final String HOST_OVERRIDE = "TESTCONTAINERS_HOST_OVERRIDE";

    private TestTaskWiring() {}

    /** Wires the Test tasks and compile tasks of {@code project}, including ones added later. */
    static void wireProject(Project project, TaskProvider<RemoteTcSetupTask> setup, Provider<TunnelService> service) {
        project.getTasks().withType(Test.class).configureEach(test -> wire(test, setup, service));
        // ordering only, so setup is not scheduled for builds that have no Test task
        project.getTasks().withType(AbstractCompile.class).configureEach(compile -> compile.mustRunAfter(setup));
        // Kotlin compile tasks do not extend AbstractCompile; matched by name so there is no Kotlin dependency
        project.getTasks().configureEach(task -> {
            if (isKotlinCompile(task.getClass())) {
                task.mustRunAfter(setup);
            }
        });
    }

    private static boolean isKotlinCompile(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name.startsWith("Kotlin") && name.endsWith("Compile")) {
                return true;
            }
        }
        return false;
    }

    static void wire(Test test, TaskProvider<RemoteTcSetupTask> setup, Provider<TunnelService> service) {
        test.dependsOn(setup);
        test.usesService(service);
        // execution time, so the socket is the one of this build and the configuration cache stays valid
        test.doFirst(new EngineEnvironment(service));
    }

    private static final class EngineEnvironment implements Action<Task> {

        private final Provider<TunnelService> service;

        EngineEnvironment(Provider<TunnelService> service) {
            this.service = service;
        }

        @Override
        public void execute(@NonNull Task task) {
            TunnelService tunnel = service.get();
            tunnel.checkAlive();
            Test test = (Test) task;
            test.environment(DOCKER_HOST, "unix://" + tunnel.socket());
            test.environment(SOCKET_OVERRIDE, Tunnel.REMOTE_SOCKET);
            test.environment(HOST_OVERRIDE, tunnel.address().hostPart());
            tunnel.countRan();
        }
    }
}
