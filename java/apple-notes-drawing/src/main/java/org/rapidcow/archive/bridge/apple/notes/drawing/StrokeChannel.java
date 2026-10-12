/*
 * Copyright 2026 rapidcow!
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package org.rapidcow.archive.bridge.apple.notes.drawing;

/**
 * Enumeration of the optional channels in {@link PointsData}.
 */
public enum StrokeChannel {
	/* Declaration order is significant for
	 * EnumSet to sort us properly.
	 */

	/**
	 * The {@code timestamp} channel.
	 * Corresponds to bit 1 in the {@code points_flag}.
	 */
	TIMESTAMP,
	/**
	 * The {@code radius} channel.
	 * Corresponds to bit 2 in the {@code points_flag}.
	 */
	RADIUS,
	/**
	 * The {@code aspect_ratio} channel.
	 * Corresponds to bit 3 in the {@code points_flag}.
	 */
	ASPECT_RATIO,
	/**
	 * The {@code edge_width} channel.
	 * Corresponds to bit 4 in the {@code points_flag}.
	 */
	EDGE_WIDTH,
	/**
	 * The {@code force} channel.
	 * Corresponds to bit 5 in the {@code points_flag}.
	 */
	FORCE,
	/**
	 * The {@code azimuth} channel.
	 * Corresponds to bit 6 in the {@code points_flag}.
	 */
	AZIMUTH,
	/**
	 * The {@code altitude} channel.
	 * Corresponds to bit 7 in the {@code points_flag}.
	 */
	ALTITUDE,
	/**
	 * The {@code opacity} channel.
	 * Corresponds to bit 8 in the {@code points_flag}.
	 */
	OPACITY,
	/**
	 * The {@code radius2} channel.
	 * Corresponds to bit 9 in the {@code points_flag}.
	 */
	RADIUS2,
	/**
	 * The {@code threshold} channel.
	 * Corresponds to bit 10 in the {@code points_flag}.
	 */
	THRESHOLD,
	/**
	 * The {@code elevation} channel.
	 * Corresponds to bit 11 in the {@code points_flag}.
	 */
	ELEVATION;
}
