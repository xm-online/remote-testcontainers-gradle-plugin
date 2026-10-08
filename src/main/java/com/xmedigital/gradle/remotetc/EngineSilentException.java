package com.xmedigital.gradle.remotetc;

import java.io.Serial;

/** The container engine did not answer. Carries the reason id {@code engine_silent}. */
final class EngineSilentException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    EngineSilentException(String message) {
        super(message);
    }
}
