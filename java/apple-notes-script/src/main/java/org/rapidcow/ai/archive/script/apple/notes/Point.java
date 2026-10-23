package org.rapidcow.ai.archive.script.apple.notes;

/**
 * A single point sampled along a stroke.
 *
 * @param x      horizontal position
 * @param y      vertical position
 * @param radius stroke radius at this point
 */
record Point(double x, double y, double radius) {}
