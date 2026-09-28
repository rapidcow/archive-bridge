package org.rapidcow.archive.bridge.apple.notes.drawing;

import java.util.Map;
import java.util.Optional;

/** One decoded point: the unconditional x/y location plus whichever of
 *  StrokeChannel's ten channels points_flags enabled for this stroke. */
public record StrokePoint(float x, float y, Map<StrokeChannel, ChannelReading> channels) {
    public StrokePoint {
        channels = Map.copyOf(channels);
    }

    public Optional<ChannelReading.Force> force()             { return typed(StrokeChannel.FORCE, ChannelReading.Force.class); }
    public Optional<ChannelReading.AspectRatio> aspectRatio() { return typed(StrokeChannel.ASPECT_RATIO, ChannelReading.AspectRatio.class); }
    public Optional<ChannelReading.EdgeWidth> edgeWidth()     { return typed(StrokeChannel.EDGE_WIDTH, ChannelReading.EdgeWidth.class); }
    public Optional<ChannelReading.Azimuth> azimuth()         { return typed(StrokeChannel.AZIMUTH, ChannelReading.Azimuth.class); }
    public Optional<ChannelReading.Altitude> altitude()       { return typed(StrokeChannel.ALTITUDE, ChannelReading.Altitude.class); }
    public Optional<ChannelReading.Opacity> opacity()         { return typed(StrokeChannel.OPACITY, ChannelReading.Opacity.class); }
    public Optional<ChannelReading.Threshold> threshold()     { return typed(StrokeChannel.THRESHOLD, ChannelReading.Threshold.class); }
    public Optional<ChannelReading.DirectFloat> timestamp()   { return typed(StrokeChannel.TIMESTAMP, ChannelReading.DirectFloat.class); }
    public Optional<ChannelReading.DirectFloat> radius()      { return typed(StrokeChannel.RADIUS, ChannelReading.DirectFloat.class); }
    public Optional<ChannelReading.DirectFloat> radius2()     { return typed(StrokeChannel.RADIUS2, ChannelReading.DirectFloat.class); }

    // safe only because StrokeChannel.decode() always pairs a constant with its own record type
    private <T extends ChannelReading> Optional<T> typed(StrokeChannel ch, Class<T> type) {
        return Optional.ofNullable(channels.get(ch)).map(type::cast);
    }
}
