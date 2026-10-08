package com.xmedigital.gradle.remotetc;

import java.io.Serial;

/** The Remote host address is not of the accepted form. Carries the reason id {@code host_invalid}. */
final class HostAddressException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    HostAddressException(String message) {
        super(message);
    }
}
