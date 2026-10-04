package org.rapidcow.archive.bridge.apple.notes.drawing;

/**
 * Enumeration of optional channels in {@link PointsData}.
 */
public enum StrokeChannel {
	/*
	 * Declaration order is significant for
	 * EnumSet to sort us properly.
	 */
	TIMESTAMP,
	RADIUS,
	ASPECT_RATIO,
	EDGE_WIDTH,
	FORCE,
	AZIMUTH,
	ALTITUDE,
	OPACITY,
	RADIUS2,
	THRESHOLD,
	ELEVATION;
}
