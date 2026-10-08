package com.xmedigital.gradle.remotetc;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildServiceSpec;
import org.gradle.api.tasks.TaskProvider;

/**
 * Apply to the root project. With {@code -PremoteTc} on the command line (or in an IDE run configuration)
 * the plugin registers the Tunnel service and the {@code remoteTcSetup} task. Without it, nothing.
 */
public class RemoteTcPlugin implements Plugin<Project> {

    static final String SWITCH = "remoteTc";
    static final String HOST_PROPERTY = "remoteTc.host";
    static final String SERVICE_NAME = "remoteTcTunnel";
    static final String SETUP_TASK = "remoteTcSetup";

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("not_root_project: apply the Remote Testcontainers plugin in the root project, "
                + "not in " + project.getPath());
        }
        if (!switchGiven(project)) {
            boolean onCommandLine = project.getGradle().getStartParameter().getProjectProperties().containsKey(SWITCH);
            if (!onCommandLine && project.getProviders().gradleProperty(SWITCH).isPresent()) {
                project.getLogger().warn("[remote-tc] " + SWITCH + " found in a project file or the environment is "
                    + "ignored: the switch counts only when given for the run, on the command line (-P" + SWITCH
                    + ") or in the IDE run configuration");
            }
            return;
        }
        Provider<String> host = project.getProviders().gradleProperty(HOST_PROPERTY);
        Provider<TunnelService> service = project.getGradle().getSharedServices().registerIfAbsent(
            SERVICE_NAME, TunnelService.class, (BuildServiceSpec<TunnelService.Params> spec) ->
                spec.getParameters().getHost().set(host));
        TaskProvider<RemoteTcSetupTask> setup = project.getTasks().register(SETUP_TASK, RemoteTcSetupTask.class, task -> {
            task.getService().set(service);
            task.usesService(service);
            task.getHost().set(host);
        });
        project.getAllprojects().forEach(p -> TestTaskWiring.wireProject(p, setup, service));
    }

    /** The switch is read from the start parameters, so it is a configuration cache input and a file cannot set it. */
    private static boolean switchGiven(Project project) {
        String value = project.getGradle().getStartParameter().getProjectProperties().get(SWITCH);
        return value != null && !value.equalsIgnoreCase("false");
    }
}
