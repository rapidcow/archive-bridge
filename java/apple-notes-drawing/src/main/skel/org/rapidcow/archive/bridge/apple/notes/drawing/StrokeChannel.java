[% USE StrokeChannel -%]
package org.rapidcow.archive.bridge.apple.notes.drawing;

import java.nio.ByteBuffer;

/**
 * One of the ten optional per-point channels gated by {@code
 * StrokeData.points_flags} bits 1-10 (bit 0 is always set on real samples
 * but names no channel - see {@link PointsData}). Byte layout, bit
 * assignment and decode formulas are all from PencilKit's own
 * PKStrokePathPointsShared/Private accessors - see
 * apple-notes-drawing-2.proto's header comment. decode() returns the
 * value AS STORED ON THE WIRE, un-normalized - see {@link ChannelReading}.
 *
 * <p><b>Declaration order matters and is not decorative.</b> A set bit in
 * {@code points_flags} doesn't just mean "this channel is present" - the
 * enabled channels are packed back-to-back in ascending bit-index order,
 * with no separate field recording that order (see
 * point-decode-pipeline.md §5-§7: PencilKit's own {@code
 * computeCachedValues()} builds its cumulative per-channel byte offsets by
 * walking the presence bitmask from bit 1 upward). {@link PointsData#decode}
 * relies on {@code EnumSet}'s guaranteed natural iteration order (= these
 * constants' declaration/ordinal order) to reproduce that packing order, so
 * the constants below MUST stay declared in ascending {@code bitIndex}
 * order - the static initializer below enforces this at class-load time so
 * a future reordering fails loudly instead of silently misreading bytes.
 */
public enum StrokeChannel {
[% FOREACH ch IN StrokeChannel.channels -%]
	[% ch.decl_padded %]{ ChannelReading decode(ByteBuffer b) { return [% ch.decode %]; } }[% loop.last ? ';' : ',' %]
[% END -%]

	public final int bitIndex;
	public final int byteSize;

	StrokeChannel(int bitIndex, int byteSize) {
		this.bitIndex = bitIndex;
		this.byteSize = byteSize;
	}

	static {
		StrokeChannel[] values = values();
		for (int i = 0; i < values.length; i++) {
			if (values[i].bitIndex != i + 1) {
				throw new ExceptionInInitializerError(
					"StrokeChannel." + values[i] + " must be declared in ascending"
						+ " bitIndex order (expected bitIndex " + (i + 1) + " at ordinal "
						+ i + ") - PointsData.decode() relies on EnumSet's natural"
						+ " iteration order to reproduce points_data's packing order");
			}
		}
	}

	abstract ChannelReading decode(ByteBuffer littleEndian);

	private static int u16(ByteBuffer b) {
		return Short.toUnsignedInt(b.getShort());
	}
}
