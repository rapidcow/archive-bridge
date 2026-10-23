package org.rapidcow.ai.archive.bridge.cozic.joplin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoplinExportTest {
    @TempDir
    Path temporary;

    @Test
    void testFileBasic() throws IOException {
        var jex = JoplinExportFile.open(/* TODO */);
        assertTrue(true);
        jex.close();
    }

    @Test
    void testDirectoryBasic() throws IOException {
        var jex = JoplinExportDirectory.open(/* TODO */);
        assertTrue(true);
        jex.close();
    }
}
