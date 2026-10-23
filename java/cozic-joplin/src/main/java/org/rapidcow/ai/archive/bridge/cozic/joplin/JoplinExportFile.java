package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.IOException;

public final class JoplinExportFile implements AutoCloseable {
    public static JoplinExportFile open() {
        return new JoplinExportFile();
    }

    @Override
    public void close() throws IOException {
        // TODO
    }
}
