package com.xmedigital.gradle.remotetc;

import java.io.Serial;

/** A named setup or run failure of the Tunnel. The message always contains the reason id. */
final class TunnelException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reasonId;

    TunnelException(String reasonId, String message) {
        super(message);
        this.reasonId = reasonId;
    }

    String reasonId() {
        return reasonId;
    }
}
