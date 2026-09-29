package Local::Plugin::StrokeChannel;

use 5.006;
use strict;
use warnings;
use base 'Template::Plugin';

use Local::Plugin::Snake qw(SNAKE);

# Single source of truth for the ten optional per-point channels gated by
# StrokeData.points_flags bits 1-10. `bit'/`size' come from PencilKit's own
# sFieldOffsets/sFieldSizes; `decode' is the Java expression each
# StrokeChannel enum constant's decode(ByteBuffer) returns.  Order here is
# the ascending-bitIndex declaration order that StrokeChannel.java's own
# static initializer requires - see that file's class doc for why.
my @CHANNELS = (
	{ name => 'timestamp',   bit => 1,  size => 4, type => 'float',
	  decode => 'new ChannelReading.DirectFloat(this, b.getFloat())' },
	{ name => 'radius',      bit => 2,  size => 4, type => 'float',
	  decode => 'new ChannelReading.DirectFloat(this, b.getFloat())' },
	{ name => 'aspectRatio', bit => 3,  size => 2, type => 'short',
	  decode => 'new ChannelReading.AspectRatio(u16(b))' },
	{ name => 'edgeWidth',   bit => 4,  size => 2, type => 'short',
	  decode => 'new ChannelReading.EdgeWidth(u16(b))' },
	{ name => 'force',       bit => 5,  size => 2, type => 'short',
	  decode => 'new ChannelReading.Force(u16(b))' },
	{ name => 'azimuth',     bit => 6,  size => 2, type => 'short',
	  decode => 'new ChannelReading.Azimuth(u16(b))' },
	{ name => 'altitude',    bit => 7,  size => 2, type => 'short',
	  decode => 'new ChannelReading.Altitude(u16(b))' },
	{ name => 'opacity',     bit => 8,  size => 2, type => 'short',
	  decode => 'new ChannelReading.Opacity(u16(b))' },
	{ name => 'radius2',     bit => 9,  size => 4, type => 'float',
	  decode => 'new ChannelReading.DirectFloat(this, b.getFloat())' },
	{ name => 'threshold',   bit => 10, size => 2, type => 'short',
	  decode => 'new ChannelReading.Threshold(u16(b))' },
);

sub new {
	my ($class, $context, @args) = @_;
	my $self = bless { context => $context }, $class;
	$self->{channels} = [ map { { %$_ } } @CHANNELS ];

	# `enum' = the StrokeChannel constant's spelling, e.g. aspectRatio -> ASPECT_RATIO.
	# `decl' = "ENUM_NAME(bit, size)", left-padded so every constant's
	# opening brace lines up, matching the hand-aligned original.
	my $width = 0;
	for my $ch (@{ $self->{channels} }) {
		$ch->{enum} = SNAKE($ch->{name});
		$ch->{decl} = "$ch->{enum}($ch->{bit}, $ch->{size})";
		$width = length($ch->{decl}) if length($ch->{decl}) > $width;
	}
	$_->{decl_padded} = sprintf('%-*s', $width + 1, $_->{decl})
		for @{ $self->{channels} };

	return $self;
}

sub channels { return $_[0]->{channels}; }

1;

__END__

=head1 NAME

Local::Plugin::StrokeChannel - shared StrokeChannel per-channel data

=head1 SYNOPSIS

    [% USE StrokeChannel %]
    [% FOREACH ch IN StrokeChannel.channels %]
    [% ch.enum %] bit=[% ch.bit %] size=[% ch.size %] type=[% ch.type %]
    [% END %]

=head1 DESCRIPTION

Both C<StrokeChannel.java> and C<PointsData.java> need the same per-channel
facts (name, bit index, byte size, Java type, decode expression). This
plugin holds that table once so neither skel has to restate it.

=cut
