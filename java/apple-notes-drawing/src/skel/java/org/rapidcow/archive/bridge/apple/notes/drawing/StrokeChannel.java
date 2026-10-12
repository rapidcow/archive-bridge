/*
 * Copyright 2026 rapidcow!
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
[% USE Snake -%]
[% USE StrokeChannel -%]
package org.rapidcow.archive.bridge.apple.notes.drawing;

/**
 * Enumeration of the optional channels in {@link PointsData}.
 */
public enum StrokeChannel {
	/* Declaration order is significant for
	 * EnumSet to sort us properly.
	 */

[% FOREACH chan IN StrokeChannel.channels -%]
	/**
	 * The {@code [% chan.name.snake %]} channel.
	 * Corresponds to bit [% chan.bit %] in the {@code points_flag}.
	 */
	[% chan.name.SNAKE %][% loop.last ? ';' : ',' %]
[% END -%]
}
