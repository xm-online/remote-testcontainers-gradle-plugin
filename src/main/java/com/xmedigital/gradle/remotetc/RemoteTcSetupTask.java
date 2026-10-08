package com.xmedigital.gradle.remotetc;

import java.time.Duration;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

/**
 * Validates the Remote host, opens the Tunnel, checks that the engine answers and names the target, all
 * within one deadline. Never up to date: it must run on every build that has a Test task.
 */
public abstract class RemoteTcSetupTask extends DefaultTask {

    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** Test-only: shortens the setup time limit through a system property of the Gradle daemon. */
    static final String TIMEOUT_PROPERTY = "remotetc.test.setupTimeoutSeconds";

    /** Creates the task; it is registered by {@link RemoteTcPlugin}, not by hand. */
    public RemoteTcSetupTask() {
        doNotTrackState("the Tunnel is per build, so setup must run every time");
    }

    /** @return the build's Tunnel service that this task opens */
    @Internal
    public abstract Property<TunnelService> getService();

    /** @return the raw Remote host address, {@code ssh://user@host[:port]}, from {@code remoteTc.host} */
    @Input
    @Optional
    public abstract Property<String> getHost();

    /** The fixed limit; the test-only property can only shorten it, never lengthen it. */
    private static int timeoutSeconds() {
        return Math.max(1, Math.min(DEFAULT_TIMEOUT_SECONDS, Integer.getInteger(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS)));
    }

    /** Validates the host, opens the Tunnel and checks that the engine answers; fails with a named reason. */
    @TaskAction
    public void setup() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(timeoutSeconds()));
        String raw = getHost().getOrNull();
        if (raw == null || raw.isBlank()) {
            throw new GradleException("host_missing: the Remote host setting remoteTc.host is missing. Put it in your "
                + "per-user settings (~/.gradle/gradle.properties), not in the project: "
                + "remoteTc.host=ssh://user@host");
        }
        RemoteHostAddress address;
        try {
            address = RemoteHostAddress.parse(raw);
        } catch (HostAddressException e) {
            throw new GradleException(e.getMessage(), e);
        }
        TunnelService service = getService().get();
        try {
            service.open(address, deadline);
            if (deadline.expired()) {
                throw new TunnelException("setup_timeout", "setup_timeout: " + address.display()
                    + ": setup did not finish within " + deadline.limit().toSeconds() + " s");
            }
            EngineProbe.ping(address.display(), service.socket(), deadline.remaining());
        } catch (TunnelException | EngineSilentException e) {
            throw new GradleException(e.getMessage(), e);
        }
        service.markTargetNamed();
        getLogger().lifecycle("[remote-tc] Container target: remote " + address.display());
    }
}
