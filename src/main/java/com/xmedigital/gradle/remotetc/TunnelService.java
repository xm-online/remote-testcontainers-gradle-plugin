package com.xmedigital.gradle.remotetc;

import java.nio.file.Path;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * The build's one Tunnel. Created per build, closed by Gradle at build end whether the build passed, failed
 * or was cancelled. Persists nothing.
 */
public abstract class TunnelService implements BuildService<TunnelService.Params>, AutoCloseable {

    /** Parameters: the raw Remote host address, validated when the Tunnel opens (not at creation). */
    public interface Params extends BuildServiceParameters {
        Property<String> getHost();
    }

    private static final Logger LOGGER = Logging.getLogger(TunnelService.class);

    private final TunnelCounter counter = new TunnelCounter();
    private volatile Tunnel tunnel;
    private volatile RemoteHostAddress address;
    private volatile boolean targetNamed;

    /** Opens the Tunnel within the deadline; the address must already have been validated. */
    synchronized Path open(RemoteHostAddress address, Deadline deadline) {
        this.address = address;
        tunnel = new Tunnel(address, "ssh", Path.of(System.getProperty("java.io.tmpdir")));
        tunnel.open(deadline);
        return tunnel.socket();
    }

    /** The private local socket of this build, once the Tunnel is open. */
    Path socket() {
        return tunnel.socket();
    }

    /** The validated Remote host address, once the Tunnel is open. */
    RemoteHostAddress address() {
        return address;
    }

    /** Fails with {@code tunnel_lost} when ssh exited unexpectedly. Called by every Test task before it starts. */
    void checkAlive() {
        Tunnel t = tunnel;
        if (t == null) {
            throw new TunnelException("setup_not_run", "setup_not_run: remoteTcSetup did not run, so no Tunnel is open. "
                + "Do not exclude it with -x remoteTcSetup");
        }
        t.checkAlive();
    }

    /** Setup finished: the target was named, so the end line is owed at build end. */
    void markTargetNamed() {
        targetNamed = true;
    }

    void countRan() {
        counter.countRan();
    }

    int ranCount() {
        return counter.ranCount();
    }

    @Override
    public synchronized void close() {
        if (tunnel != null) {
            tunnel.close();
        }
        if (targetNamed) {
            int n = counter.ranCount();
            LOGGER.lifecycle(n > 0 ? "[remote-tc] " + n + " Test task(s) ran"
                : "[remote-tc] No Test task ran, no container started");
        }
    }
}
