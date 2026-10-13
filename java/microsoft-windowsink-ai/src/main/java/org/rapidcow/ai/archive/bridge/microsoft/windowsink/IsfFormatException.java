package org.rapidcow.ai.archive.bridge.microsoft.windowsink;

import java.io.IOException;

/** Invalid or unsupported Ink Serialized Format input. */
public final class IsfFormatException extends IOException {
    public IsfFormatException(String message) {
        super(message);
    }
}
