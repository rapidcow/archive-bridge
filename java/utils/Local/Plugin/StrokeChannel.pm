package Local::Plugin::StrokeChannel;

use 5.006;
use strict;
use warnings;
use base 'Template::Plugin';

use Local::Plugin::Snake qw(SNAKE);

sub parse_member
{
	my (@F) = @_;
	my %alist;

	@alist{qw( type name )} = @F;
	($alist{endian}) = ($alist{type} =~ /[<>]$/g)
		or die "E: type missing endianness\n";
	$alist{type} = substr $alist{type}, 0, length ($alist{type}) - 1;
	$alist{rescale} = $alist{name} =~ s/\*$//;
	\%alist;
}

my @chan = map { parse_member( split "\t" ) }
	grep { chomp; !/^#/ } split /^/,
	<<'DATA';
f32<	x
f32<	y
f32<	timestamp
f32<	radius
u16<	aspectRatio*
u16<	edgeWidth*
u16<	force*
u16<	azimuth*
u16<	altitude*
u16<	opacity*
f32<	radius2
u16<	threshold*
f32<	elevation
DATA

my %byte_size = (
	f32 => 4,
	u16 => 2,
);

sub byte_size
{
	my ($type) = @_;
	defined ( my $width = $byte_size{$type} )
		or die "E: Byte size of $type unknown\n";
	$width;
}

#
# `offset' serves to reconstruct sFieldOffsets by obeying
# the 4-byte alignment rule that the Cxx compiler follows.
# The wire format does not concern these offsets, but it
# helps us compare against progression of future PencilKit
# versions.  (Credits to Claude for alignment code...)
#
{
	my $offset = 0;
	foreach my $chan (@chan) {
		my $type = $chan->{type};
		my $width = byte_size($type);
		$offset = ($offset + $width - 1) & ~($width - 1);
		$chan->{offset} = $offset;
		$chan->{width} = $width;
		$offset += $width;
	}
}

my %java_type = (
	f32 => 'float',
	u16 => 'short',
);

sub java_type
{
	my ($type) = @_;
	defined ( my $java_type = $java_type{$type} )
		or die "E: No map from $type to a Java type\n";
	$java_type;
}

foreach my $chan (@chan) {
	my $type = $chan->{type};
	my $java_type = $java_type{$type};
	$chan->{java_type} = $java_type;
}

sub new
{
	my ($class, $context, @args) = @_;
	bless { context => $context }, $class;
}

sub fields
{
	my $bit = 0;
	my $pad = 0;

	# Deep copy: Note that I dropped the usual +{ ... }
	# brace disambiguation since the first pair of braces
	# of a pp_mapwhile always parses as XTERM.  (It makes
	# intuitive sense since scope within map doesn't make
	# much sense most of the time.)
	my @chan = map { { %$_ } } @chan;

	foreach (@chan) {
		my $name = $_->{name};
		unless (grep { $name eq $_ } qw( x y )) {
			$bit++;
		}
		$_->{bit} = $bit;
		$_->{enum} = SNAKE($_->{name});
		if ($_->{bit}) {
			$_->{decl} = "$_->{enum}($bit, $_->{width})";
			if ($pad < length($_->{decl})) {
				$pad = length($_->{decl});
			}
			$_->{parse} = $_->{type} eq 'u16' ? 'u16(b)' : "b.get\u$_->{java_type}()";
			$_->{decode} = $_->{rescale}
				? "new ChannelReading.\u$_->{name}($_->{parse})"
				: "new ChannelReading.Direct\u$_->{java_type}(this, $_->{parse})";
		}
	}

	foreach (@chan) {
		if ($_->{bit}) {
			$_->{decl_padded} = sprintf('%-*s', 1 + $pad, $_->{decl});
		}
	}

	@chan;
}

sub channels { grep { $_->{bit} } &fields; }

#
# Bitmask of every points_flags bit the decoder understands:
# bit 0 (the location pair) plus one bit per optional channel.
#
# I've written something like this, but scrapped it since i
# thought it was too strict.  Claude then independently wrote
# this up --- so thanks! :)
#
sub mask
{
	my $mask = 1;
	$mask |= 1 << $_->{bit} foreach &channels;
	$mask;
}

1;

__END__

=head1 NAME

Local::Plugin::StrokeChannel - shared StrokeChannel per-channel data

=head1 SYNOPSIS

  [% USE StrokeChannel %]
  [% FOREACH chan IN StrokeChannel.fields %]
  [% chan.java_type %] [% chan.name %]
  [% END %]

=head1 DESCRIPTION

Both C<StrokeChannel.java> and C<PointsData.java> need the same per-channel
facts (name, bit index, byte size, Java type, decode expression). This
plugin holds that table once so neither skel has to restate it.

The B<channels> method is now limited to the optional channels.
To get all channels, use B<fields>.

The B<mask> method returns the bitmask of all known C<points_flags> bits
(including bit 0, the always-present location pair).

=cut
