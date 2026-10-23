package org.rapidcow.ai.archive.script.apple.notes;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.rapidcow.trivnote.libexpr.Atom;
import org.rapidcow.trivnote.libexpr.Cons;
import org.rapidcow.trivnote.libexpr.DataLoader;
import org.rapidcow.trivnote.libexpr.Datum;
import org.rapidcow.trivnote.libexpr.Nil;
import org.rapidcow.trivnote.libexpr.Symbol;

/** Adapts PreviewAppleNotesDrawing's geometry loader to libexpr 0.0.0. */
final class DrawingPreview {
    private DrawingPreview() {}

    static List<Stroke> read(InputStream input) throws IOException {
        Datum root;
        try (var in = new BufferedInputStream(input)) {
            var loader = new DataLoader(in);
            root = loader.readDatum();
            if (loader.readDatum() != null) throw invalid("Trailing datum after drawing");
        }
        if (!(root instanceof Cons pair) || pair.car() != Symbol.of("drawing")) {
            throw invalid("Expected a drawing");
        }
        var drawing = properties(pair.cdr());
        var inks = new ArrayList<Ink>();
        for (Datum ink : tagged("ink", drawing.get("inks"))) {
            List<Datum> color = list(properties(ink).get("color"));
            if (color.size() != 4) throw invalid("Expected four color components");
            inks.add(new Ink(new Color((float) number(color.get(0), 0), (float) number(color.get(1), 0),
                    (float) number(color.get(2), 0), (float) number(color.get(3), 1))));
        }
        var strokes = new ArrayList<Stroke>();
        for (Datum stroke : tagged("stroke", drawing.get("strokes"))) {
            var fields = properties(stroke);
            var points = new ArrayList<Point>();
            for (Datum point : list(fields.get("points"))) {
                var channels = properties(point);
                var xy = list(channels.get("point"));
                if (xy.size() != 2) throw invalid("Expected two point coordinates");
                points.add(new Point(number(xy.get(0), 0), number(xy.get(1), 0),
                        number(channels.get("radius"), 0)));
            }
            int inkIndex = Integer.parseInt(value(first(fields.get("ink-index"))));
            if (inkIndex < 0 || inkIndex >= inks.size()) throw invalid("Invalid ink index: " + inkIndex);
            strokes.add(new Stroke(clock(fields.get("sort-id")), value(first(fields.get("uuid"))),
                    inks.get(inkIndex), transformOrIdentity(fields.get("transform"), fields.get("substrokes")),
                    transformOrIdentity(fields.get("ink-transform"), null), List.copyOf(points)));
        }
        strokes.sort(Comparator.comparing(Stroke::clock, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(strokes);
    }

    private static Clock clock(Datum node) {
        var parts = list(node);
        if (parts.size() == 1 && absent(parts.get(0))) return null;
        if (parts.size() != 3) throw invalid("Expected three sort-id components");
        return new Clock(Integer.parseInt(value(parts.get(0))), Integer.parseInt(value(parts.get(1))),
                Integer.parseInt(value(parts.get(2))));
    }

    // Match the existing preview: a missing parent transform falls back to
    // the first transformed substroke (the pre-erase point list's placement).
    private static AffineTransform transformOrIdentity(Datum node, Datum substrokes) {
        if (node == null) {
            for (Datum sub : tagged("stroke", substrokes)) {
                node = properties(sub).get("transform");
                if (node != null) break;
            }
        }
        if (node == null) return new AffineTransform();
        var parts = list(node);
        if (parts.size() != 6) throw invalid("Expected six transform components");
        double[] values = new double[6];
        for (int i = 0; i < 6; i++) values[i] = number(parts.get(i), i == 0 || i == 3 ? 1 : 0);
        return new AffineTransform(values);
    }

    private static List<Datum> tagged(String tag, Datum node) {
        var result = new ArrayList<Datum>();
        for (Datum element : list(node)) {
            if (!(element instanceof Cons pair) || pair.car() != Symbol.of(tag)) {
                throw invalid("Expected a " + tag + " form");
            }
            result.add(pair.cdr());
        }
        return result;
    }

    private static Map<String, Datum> properties(Datum node) {
        var result = new HashMap<String, Datum>();
        for (Datum element : list(node)) {
            if (!(element instanceof Cons pair) || !(pair.car() instanceof Symbol key)) {
                throw invalid("Expected a property pair");
            }
            if (result.putIfAbsent(key.value(), pair.cdr()) != null) {
                throw invalid("Duplicate property: " + key.value());
            }
        }
        return result;
    }

    private static List<Datum> list(Datum node) {
        var result = new ArrayList<Datum>();
        if (node == null) return result;
        while (node instanceof Cons pair) {
            result.add(pair.car());
            node = pair.cdr();
        }
        if (node != Nil.INSTANCE) throw invalid("Expected a proper list");
        return result;
    }

    private static Datum first(Datum node) {
        if (node instanceof Cons pair && pair.cdr() == Nil.INSTANCE) return pair.car();
        throw invalid("Expected one value");
    }

    private static String value(Datum node) {
        if (node instanceof Atom atom) return atom.value();
        throw invalid("Expected an atom");
    }

    private static boolean absent(Datum node) {
        return node == null || node == Nil.INSTANCE || node == Symbol.of("nil");
    }

    private static double number(Datum node, double fallback) {
        if (absent(node)) return fallback;
        String value = value(node);
        int slash = value.indexOf('/');
        double result = slash < 0 ? Double.parseDouble(value)
                : Double.parseDouble(value.substring(0, slash)) / Double.parseDouble(value.substring(slash + 1));
        if (!Double.isFinite(result)) throw invalid("Non-finite drawing coordinate or channel");
        return result;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
