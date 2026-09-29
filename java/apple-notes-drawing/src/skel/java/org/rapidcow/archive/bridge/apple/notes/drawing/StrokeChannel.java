[% USE Snake -%]
[% USE StrokeChannel -%]
package org.rapidcow.archive.bridge.apple.notes.drawing;

/**
 * Enumeration of optional channels in {@link PointsData}.
 */
public enum StrokeChannel {
	/*
	 * Declaration order is significant for
	 * EnumSet to sort us properly.
	 */
[% FOREACH chan IN StrokeChannel.channels -%]
	[% chan.name.SNAKE %][% loop.last ? ';' : ',' %]
[% END -%]
}
