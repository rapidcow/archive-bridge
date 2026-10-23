package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.IOException;

public final class JoplinExportDirectory implements AutoCloseable {
    public static JoplinExportDirectory open() {
        return new JoplinExportDirectory();
    }

    @Override
    public void close() throws IOException {
        // TODO
    }
}
