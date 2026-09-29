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

{
	my ($offset, $width);
	$offset = 0;
	foreach my $chan (@chan) {
		my $type = $chan->{type};
		$width = byte_size($type);
		$chan->{offset} = $offset;
		$chan->{width} = $width;
	} continue {
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

The B<channels> method is now limited to the 10 optional channels.
To get all channels, use B<fields>.

=cut
