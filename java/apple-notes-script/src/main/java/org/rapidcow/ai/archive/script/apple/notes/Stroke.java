package org.rapidcow.ai.archive.script.apple.notes;

import java.awt.geom.AffineTransform;
import java.util.List;

/**
 * A single stroke, as a sequence of points.
 *
 * @param clock     sort identifier
 * @param uuid      stroke identifier
 * @param brush     brush properties
 * @param affineMap affine map to apply
 * @param inkMap    a second, independent affine map (Stroke.inkTransform);
 *                  identity when absent, as it is on nearly every stroke
 * @param points    points in drawing order
 */
record Stroke(
    Clock clock,
    String uuid,
    Ink brush,
    AffineTransform affineMap,
    AffineTransform inkMap,
    List<Point> points) {}
