package org.rapidcow.archive.script.apple.notes;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.rapidcow.ai.archive.script.apple.notes.DrawingDecoder;
import org.rapidcow.ai.archive.script.apple.notes.DrawingPreview;
import org.rapidcow.ai.archive.script.apple.notes.StoreTree;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DrawingPreviewTest {
    private static final String DRAWING = """
            (drawing
              (inks (ink (color 0x1p+0 0 0 1)))
              (strokes
                (stroke (sort-id 2 0 0) (uuid "second") (ink-index 0)
                  (substrokes (stroke (transform 1 0 0 1 10 20)))
                  (points ((point 0x1.8p+1 4) (radius . 3/2) (altitude . 1/2 #| π |#))))
                (stroke (sort-id 1 0 0) (uuid "first") (ink-index 0)
                  (points ((point 1 2))))))
            """;

    @Test void readsPerlFormsSortsStrokesAndFallsBackToChildTransform() throws Exception {
        var strokes = DrawingPreview.read(new ByteArrayInputStream(DRAWING.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, strokes.size());
        assertEquals("first", strokes.get(0).uuid());
        assertEquals(0, strokes.get(0).points().get(0).radius());
        var second = strokes.get(1);
        assertEquals(3, second.points().get(0).x());
        assertEquals(1.5, second.points().get(0).radius());
        assertEquals(10, second.affineMap().getTranslateX());
        assertEquals(20, second.affineMap().getTranslateY());
        assertEquals(java.awt.Color.RED, second.brush().color());
    }

    @Test void rejectsTrailingAndMalformedOutput() {
        for (String text : new String[] {DRAWING + " (extra)", "(drawing", "(drawing (inks) (inks))"}) {
            assertThrows(RuntimeException.class, () -> DrawingPreview.read(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test void playsRealPerlOutput() throws Exception {
        String fixture = System.getProperty("notesViewerFixture");
        assumeTrue(fixture != null, "Optional archive integration test");
        try (var store = StoreTree.open(Path.of(fixture))) {
            var attachment = store.entities.values().stream().flatMap(java.util.Collection::stream)
                    .filter(item -> item.row().entity().name().equals("ICAttachment"))
                    .filter(item -> "com.apple.drawing.2".equals(item.row().get("ZTYPEUTI")))
                    .filter(store::isDrawing).findFirst().orElseThrow();
            var strokes = DrawingDecoder.decode(store.path, attachment.row().pk());
            assertFalse(strokes.isEmpty());
            assertTrue(strokes.stream().anyMatch(s -> !s.points().isEmpty()));
        }
    }
}
