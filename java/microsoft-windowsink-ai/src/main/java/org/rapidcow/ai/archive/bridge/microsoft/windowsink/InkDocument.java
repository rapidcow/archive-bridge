package org.rapidcow.ai.archive.bridge.microsoft.windowsink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decoded packet data with uninterpreted metadata preserved as serialized bytes. */
public record InkDocument(List<Stroke> strokes, List<Block> metadata) {
    public InkDocument {
        strokes = List.copyOf(strokes);
        metadata = List.copyOf(metadata);
    }

    /** A serialized metadata block (payload excludes its tag and size). */
    public record Block(int tag, byte[] data) {
        public Block {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /** Row-vector affine transform; coordinates remain in native ISF units (normally HIMETRIC). */
    public record Transform(double m11, double m12, double m21, double m22, double dx, double dy) {
        public static final Transform IDENTITY = new Transform(1, 0, 0, 1, 0, 0);

        public double x(double x, double y) {
            return x * m11 + y * m21 + dx;
        }

        public double y(double x, double y) {
            return x * m12 + y * m22 + dy;
        }
    }

    /** Channel keys are ISF tags: 50=X, 51=Y, 56=normal pressure. */
    public record Stroke(
            int pointCount,
            Map<Integer, List<Integer>> packetChannels,
            Transform transform,
            byte[] drawingAttributes,
            byte[] metrics,
            byte[] buttonData,
            byte[] properties) {
        public Stroke {
            var copy = new LinkedHashMap<Integer, List<Integer>>();
            packetChannels.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            packetChannels = Collections.unmodifiableMap(copy);
            drawingAttributes = drawingAttributes.clone();
            metrics = metrics.clone();
            buttonData = buttonData.clone();
            properties = properties.clone();
        }

        @Override
        public byte[] drawingAttributes() {
            return drawingAttributes.clone();
        }

        @Override
        public byte[] metrics() {
            return metrics.clone();
        }

        @Override
        public byte[] buttonData() {
            return buttonData.clone();
        }

        @Override
        public byte[] properties() {
            return properties.clone();
        }
    }
}
