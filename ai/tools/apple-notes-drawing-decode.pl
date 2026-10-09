#!/usr/bin/env perl
# Decode a pre-Coherence Apple Notes PencilKit drawing (UTI "com.apple.drawing.2",
# stored in ZICCLOUDSYNCINGOBJECT.ZMERGEABLEDATA1 of NoteStore.sqlite) into
# either an s-expression or an InkML document.
#
# The blob's wire format is described in apple-notes-drawing-2.proto (in
# this same directory) - see that file for what's confirmed vs. guessed,
# and see notes/apple/com.apple.drawing.2/drawing.md for how its field
# names were confirmed/corrected against PencilKit.framework's own real
# `drawing::*` C++ class field names. This
# script parses the envelope in front of that protobuf message (magic +
# version + header length, per PencilKit's own parser) and starts the
# protobuf message at the header-length offset it specifies (usually but
# not always 8), then unpacks the flat per-point float32 X/Y pairs and
# named auxiliary channels that the .proto can't express (Geometry.points is
# a variable-stride blob, not a repeated field; which channels are present
# in that stride is Geometry.point_flags, PencilKit's own per-point
# presence bitmask - see apple-notes-drawing.proto's Geometry comment for
# the channel table and what's confirmed vs. not).
#
# Usage:
#   apple-notes-drawing-decode.pl --db NoteStore.sqlite --list
#       List candidate drawing attachments (com.apple.drawing.2) as
#       "Z_PK  ZIDENTIFIER  ZMODIFICATIONDATE" only - no note titles or
#       content, so you can pick one without dumping unrelated notes.
#
#   apple-notes-drawing-decode.pl --db NoteStore.sqlite --pk 4756 --sexp
#       Decode attachment Z_PK=4756, print an s-expression to stdout.
#
#   apple-notes-drawing-decode.pl --db NoteStore.sqlite --pk 4756 --inkml
#       Decode attachment Z_PK=4756, print InkML (X/Y only) to stdout.
#
# Add --immutable for a fixed archive (such as a git-annex object).
# Only use this when the database will not change while being read.
#
# Add --sort to reorder strokes by ascending sortID (clock, replicaIndex,
# subclock) before printing, matching PencilKit's own +[PKDrawing
# sortStrokes:] - see notes/apple/com.apple.drawing.2/drawing.md Update 6
# for why this can matter: the stored array order usually already matches
# this, but not always (confirmed empirically against real sample data),
# so anything doing painter's-algorithm rendering should sort explicitly
# rather than trust array order.

use 5.010;
use strict;
use warnings;
use Getopt::Long;
use File::Basename;  # qw(basename)
use File::Spec;
use FindBin;

# https://metacpan.org/dist/DBD-SQLite
use DBD::SQLite::Constants qw(:file_open);
# https://metacpan.org/dist/DBI
use DBI;
# As of Sept 2026, there is a new "Protobuf" distribution
# that comes straight from (an employee? of) Google:
# https://metacpan.org/release/CJCOLLIER/Protobuf-0.12/view/lib/Protobuf.pm
# https://github.com/GoogleCloudPlatform/google-auth-library-perl/tree/main/Protobuf
#
# I have never heard of it, so give it some time...
#
# This one is older and ++'d by a few CPAN authors.
# https://metacpan.org/dist/Google-ProtocolBuffers
use Google::ProtocolBuffers;
# https://metacpan.org/dist/URI
use URI::file;
# https://metacpan.org/pod/Math::BigRat
use Math::BigRat;
# https://metacpan.org/pod/Data::Float
use Data::Float qw(float_hex);

# Confirmed from arm64e disassembly of PencilKit's own parser - see the
# envelope comment at the top of apple-notes-drawing.proto.
use constant ENVELOPE_MAGIC => 0xF0647277;

# Per-point auxiliary channel table: index 1-10 in PencilKit's own
# PKStrokePathPointsShared::sFieldSizes, gated by the matching bit (1<<index)
# of Geometry.point_flags. Index 0 (location) is not in this list - it's the
# unconditional first 8 bytes of every point record. Sizes are confirmed
# (static disassembly + empirical cross-check against real samples).
#
# 'kind' says how to decode the channel's raw bytes:
#   f32   - little-endian float32, used as-is (no normalization) - confirmed
#           via disassembly of PKStrokePathPointsPrivate's *AtIndex accessors;
#           printed via Data::Float::float_hex() as exact IEEE754 hex
#           (e.g. "0x1.8p+3"), not a lossy decimal %.6f rounding
#   force - little-endian uint16, decoded value = raw / 1000.0 - confirmed
#           via disassembly (ucvtf, not fcvt - i.e. NOT float16 - then fdiv
#           by an exact 1000.0 double read out of PencilKit's __TEXT), see
#           notes/apple/com.apple.drawing.2/point-decode-pipeline.md §8.
#           Kept as an exact Math::BigRat (raw/1000), no double involved.
#   azimuth  - little-endian uint16; raw*(2*PI/65535) - PI (radians) per §8,
#              but since the PI factor is common to both the divisor and
#              the bias, this is exactly (2*raw - 65535)/65535 *turns of PI*
#              - kept as an exact Math::BigRat fraction of PI, in (-1, 1],
#              matching PencilKit's own on-screen direction convention:
#              0 = +x (right), 1/2 = +y (down), 1 or -1 = -x (left),
#              -1/2 = -y (up) - i.e. a positive rotation has torque in +z.
#   altitude - little-endian uint16; PI/2 - raw*(PI/2/65535) (radians) per
#              §8 - exactly (65535 - raw)/131070 *turns of PI* - kept as an
#              exact Math::BigRat fraction of PI, in [0, 1/2] (flat pen = 0,
#              upright pen = 1/2)
#   aspect_ratio/edge_width/opacity/threshold - little-endian uint16, plain
#           divide (no bias) by the per-field constant below - same
#           confirmed formulas as force/azimuth/altitude (see the .proto's
#           channel table), kept as an exact Math::BigRat, no double.
#
# force/azimuth/altitude/aspect_ratio/edge_width/opacity/threshold's
# divisor/bias are exact Math::BigRat constants (azimuth/altitude in units
# of "turns of PI", per the comment above), so decode_channel()'s
# raw*(1/DIVISOR)+BIAS arithmetic below stays exact - same expression shape
# the old floating-point version used, just with BigRat operands instead of
# Perl doubles.
use constant FORCE_DIVISOR        => Math::BigRat->new(1000);
use constant AZIMUTH_DIVISOR      => Math::BigRat->new("65535/2");
use constant AZIMUTH_BIAS         => Math::BigRat->new(-1);
use constant ALTITUDE_DIVISOR     => Math::BigRat->new(131070);
use constant ALTITUDE_BIAS        => Math::BigRat->new("1/2");
use constant ASPECT_RATIO_DIVISOR => Math::BigRat->new(1000);
use constant EDGE_WIDTH_DIVISOR   => Math::BigRat->new(10);
use constant OPACITY_DIVISOR      => Math::BigRat->new("65535/2");
use constant THRESHOLD_DIVISOR    => Math::BigRat->new("65535/2");
my @POINT_CHANNELS = (
	undef,                        # index 0: location, handled separately
	[ timestamp   => 4, 'f32'         ],
	[ radius      => 4, 'f32'         ],
	[ aspectRatio => 2, 'aspect_ratio' ],
	[ edgeWidth   => 2, 'edge_width'  ],
	[ force       => 2, 'force'       ],
	[ azimuth     => 2, 'azimuth'     ],
	[ altitude    => 2, 'altitude'    ],
	[ opacity     => 2, 'opacity'     ],
	[ radius2     => 4, 'f32'         ],
	[ threshold   => 2, 'threshold'   ],
);

Google::ProtocolBuffers->parsefile(
	File::Spec->catfile( $FindBin::Bin, "apple-notes-drawing-2.proto" )
);

my $prog = basename($0);
my $usage = <<EOM;
usage: $prog --db NoteStore.sqlite [--immutable] --list
       $prog --db NoteStore.sqlite [--immutable] --pk N [--sexp|--inkml] [--sort] [--out FILE]
EOM

my ($db, $pk, $list, $want_sexp, $want_inkml, $out, $want_sort);
my $immutable;
GetOptions(
	"immutable" => \$immutable,
	"db=s"   => \$db,
	"pk=i"   => \$pk,
	"list"   => \$list,
	"sexp"   => \$want_sexp,
	"inkml"  => \$want_inkml,
	"sort"   => \$want_sort,
	"out=s"  => \$out,
) or die $usage;

die $usage unless $db;

if ($list) {
	list_attachments($db);
	exit 0;
}

die "need --pk\n" unless defined $pk;
$want_sexp = 1 unless $want_sexp || $want_inkml;

my $blob = fetch_blob($db, $pk);
die "no ZMERGEABLEDATA1 for Z_PK=$pk (wrong pk, or not a com.apple.drawing.2 attachment?)\n"
	unless defined $blob && length($blob);

my $drawing = decode_drawing($blob);
sort_strokes_by_sort_id($drawing) if $want_sort;

my $fh = \*STDOUT;
if ($out) {
	open($fh, '>', $out) or die "can't write $out: $!\n";
}

print $fh render_sexp($drawing)  if $want_sexp;
print $fh render_inkml($drawing) if $want_inkml;

# ---------------------------------------------------------------------------

sub list_attachments {
	my ($db) = @_;
	my $dbh = connect_db($db);
	my $rows = $dbh->selectall_arrayref(
		"SELECT Z_PK, ZIDENTIFIER, ZMODIFICATIONDATE FROM ZICCLOUDSYNCINGOBJECT " .
		"WHERE ZTYPEUTI='com.apple.drawing.2' ORDER BY Z_PK;");
	$dbh->disconnect;
	print "Z_PK\tZIDENTIFIER\tZMODIFICATIONDATE\n";
	print join("|", map { defined $_ ? $_ : '' } @$_), "\n" for @$rows;
}

sub fetch_blob {
	my ($db, $pk) = @_;
	my $dbh = connect_db($db);
	my ($blob) = $dbh->selectrow_array(
		"SELECT ZMERGEABLEDATA1 FROM ZICCLOUDSYNCINGOBJECT WHERE Z_PK = ?",
		undef, $pk);
	$dbh->disconnect;
	return $blob;
}

sub connect_db {
	my ($db) = @_;
	my $dsn = "dbi:SQLite:dbname=$db";
	if ($immutable) {
		my $uri = URI::file->new(File::Spec->rel2abs($db));
		$uri->query('mode=ro&immutable=1');
		$dsn = "dbi:SQLite:uri=$uri";
	}
	return DBI->connect($dsn, '', '', {
		RaiseError => 1,
		PrintError => 0,
		AutoCommit => 1,
		sqlite_open_flags => SQLITE_OPEN_READONLY
	});
}

# --- drawing decode ----------------------------------------------------------

# Decode a drawing::Color submessage (real class name, confirmed 2026-09-21 -
# see apple-notes-drawing-2.proto's Color message comment) into a 4-element
# [r,g,b,a] list, each component undef if absent on the wire (independently
# optional, same presence convention as Transform/Bounds). Returns undef if
# the submessage itself is absent.
sub decode_color {
	my ($c) = @_;
	return undef unless $c;
	return [ $c->{r}, $c->{g}, $c->{b}, $c->{a} ];
}

sub decode_drawing {
	my ($blob) = @_;
	die "blob too short for envelope\n" if length($blob) < 8;

	my ($magic, $version, undef, $hdrlen) = unpack("V C C v", substr($blob, 0, 8));

	my ($envelope, $body);
	if ($magic == ENVELOPE_MAGIC) {
		die "drawing format version $version is too new (>=6); this decoder only understands PencilKit's <6 envelope layout\n"
			if $version >= 6;
		die "bogus envelope header length $hdrlen (need 8 <= hdrlen < " . length($blob) . ")\n"
			unless $hdrlen >= 8 && $hdrlen < length($blob);
		$envelope = substr($blob, 0, $hdrlen);
		$body     = substr($blob, $hdrlen);
	} else {
		# Magic mismatch: PencilKit's own fallback treats the entire blob
		# (no envelope at all) as a raw protobuf Drawing message.
		$envelope = '';
		$body     = $blob;
	}

	my $drawing = Drawing->decode($body);

	# Ink's 13 real fields (color/identifier/version/variant/
	# sixChannelAddColor/sixChannelMultiplyColor/requiredContentVersion/
	# weight/hdrHeadroom/hdrColor/hdrAddColor/hdrMultiplyColor/azimuth) all
	# confirmed 2026-09-21 via live disassembly of drawing::Ink::readFrom
	# (see apple-notes-drawing-2.proto's Ink message comment and
	# notes/apple/com.apple.drawing.2/drawing.md's latest Update). weight/
	# hdrHeadroom/azimuth are doubles (NOT int32 as this decoder used to
	# assume for weight) - Ink.azimuth is unrelated to the per-point
	# azimuth channel, see the same comments.
	my @inks;
	for my $ink (@{ $drawing->{inks} || [] }) {
		push @inks, {
			identifier                 => $ink->{identifier},
			color                      => decode_color($ink->{color}),
			weight                     => $ink->{weight},
			version                    => $ink->{version},
			variant                    => $ink->{variant},
			required_content_version   => $ink->{required_content_version},
			hdr_headroom               => $ink->{hdr_headroom},
			hdr_color                  => decode_color($ink->{hdr_color}),
			hdr_add_color              => decode_color($ink->{hdr_add_color}),
			hdr_multiply_color         => decode_color($ink->{hdr_multiply_color}),
			six_channel_add_color      => decode_color($ink->{six_channel_add_color}),
			six_channel_multiply_color => decode_color($ink->{six_channel_multiply_color}),
			azimuth                    => $ink->{azimuth},
		};
	}

	my @strokes = map { decode_stroke($_) } @{ $drawing->{strokes} || [] };

	# Drawing-level bounds/bounds_version/uuid/version_vector (fields 6/7/8/3)
	# - all confirmed 2026-09-21 via live disassembly of
	# drawing::Drawing::readFrom (see apple-notes-drawing-2.proto's Drawing
	# message comment and notes/apple/com.apple.drawing.2/drawing.md's latest
	# Update). version_vector is repeated StrokeID (NOT the old guessed
	# 3-int32 IndexEntry shape), so it's exposed the same [clock,
	# replicaIndex, subclock] way sort_id/version/substrokes_version are.
	my $bounds = $drawing->{bounds};
	my $bounds_version = $drawing->{bounds_version};

	return {
		envelope_hex   => unpack("H*", $envelope),
		uuid           => defined $drawing->{uuid} ? unpack("H*", $drawing->{uuid}) : undef,
		replica_uuids  => [ map { unpack("H*", $_) } @{ $drawing->{replica_uuids} || [] } ],
		version_vector => [ map { [ $_->{clock}, $_->{replica_index}, $_->{subclock} ] } @{ $drawing->{version_vector} || [] } ],
		bounds         => $bounds ? [ $bounds->{origin_x}, $bounds->{origin_y}, $bounds->{width}, $bounds->{height} ] : undef,
		bounds_version => $bounds_version ? [ $bounds_version->{clock}, $bounds_version->{replica_index}, $bounds_version->{subclock} ] : undef,
		inks           => \@inks,
		strokes        => \@strokes,
	};
}

# Decode one drawing::Stroke protobuf-message hash (as produced by
# Google::ProtocolBuffers) into this script's own flat stroke hash,
# recursing into Stroke.substrokes (field 11, proven real - readFrom calls
# PB::PtrVector<drawing::Stroke>::emplace_back directly, see the .proto's
# Stroke message comment) since it's the same message shape.
sub decode_stroke {
	my ($st) = @_;
	my @points;
	my $stride;
	# StrokeData's own non-point fields (stroke_data_uuid/timestamp/
	# points_constant_flags/points_constant_values/created_with_finger/
	# immutable_points_count/start_index) - all confirmed 2026-09-21 via
	# live disassembly of drawing::StrokeData::readFrom/formatText (see
	# apple-notes-drawing-2.proto's StrokeData message comment). Exposed
	# raw (no decode formula beyond what the .proto comment already notes)
	# since none of them have been empirically surveyed yet.
	my ($stroke_data_uuid, $timestamp, $points_constant_flags,
	    $points_constant_values, $created_with_finger,
	    $immutable_points_count, $start_index);
	if (my $geom = $st->{stroke_data}) {
		my $npts  = $geom->{points_count};
		my $pdata = $geom->{points_data};
		my $flags = $geom->{points_flags} // 0;
		$stroke_data_uuid = defined $geom->{stroke_data_uuid} ? unpack("H*", $geom->{stroke_data_uuid}) : undef;
		$timestamp = $geom->{timestamp};
		$points_constant_flags = $geom->{points_constant_flags};
		$points_constant_values = defined $geom->{points_constant_values} ? unpack("H*", $geom->{points_constant_values}) : undef;
		$created_with_finger = $geom->{created_with_finger};
		$immutable_points_count = $geom->{immutable_points_count};
		$start_index = $geom->{start_index};
		if (defined $npts && defined $pdata && $npts > 0
				&& length($pdata) % $npts == 0) {
			$stride = length($pdata) / $npts;
			for my $i (0 .. $npts - 1) {
				my $off = $i * $stride;
				my ($x, $y) = unpack("f<f<", substr($pdata, $off, 8));
				my $cursor = $off + 8;
				my %channels;
				for my $idx (1 .. 10) {
					next unless ($flags >> $idx) & 1;
					my ($name, $size, $kind) = @{ $POINT_CHANNELS[$idx] };
					my $raw = substr($pdata, $cursor, $size);
					$channels{$name} = decode_channel($kind, $raw);
					$cursor += $size;
				}
				push @points, { x => $x, y => $y, channels => \%channels };
			}
		}
	}

	# StrokeID-typed fields (sort_id/version/substrokes_version, Stroke
	# fields 2/3/9) - real field names confirmed as sortID/version/
	# substrokesVersion, type confirmed as StrokeID, all three of whose own
	# fields are now named (clock/replicaIndex/subclock, see
	# apple-notes-drawing-2.proto and notes/apple/com.apple.drawing.2/
	# drawing.md Update 5). Each exposed here as [clock, replicaIndex,
	# subclock], matching the real wire field order (1, 2, 3).
	my $sort_id = $st->{sort_id};
	my $version = $st->{version};
	my $substrokes_version = $st->{substrokes_version};
	my $bounds = $st->{bounds};

	# Stroke.transform / Stroke.ink_transform (fields 7/15) - real
	# drawing::Transform shape, confirmed 2026-09-20 via live disassembly
	# of both readFrom() (field numbers 1-6 = a/b/c/d/tx/ty, no gaps) and
	# the ObjC bridge that builds a runtime CGAffineTransform from it
	# (see apple-notes-drawing-2.proto's Transform message comment and
	# notes/apple/com.apple.drawing.2/point-decode-pipeline.md). Each of
	# the 6 fields is independently optional/presence-tracked on the
	# wire - undef here means "absent", NOT "identity"; PencilKit's own
	# runtime default (falling back to an identity CGAffineTransform, or
	# to a parent stroke's transform) is an in-memory behavior, not a
	# wire-format fact, so this decoder reports raw presence as-is.
	my $xf  = $st->{transform};
	my $ixf = $st->{ink_transform};

	return {
	    stroke_uuid   => unpack("H*", $st->{stroke_uuid} || ""),
	    sort_id       => $sort_id ? [ $sort_id->{clock}, $sort_id->{replica_index}, $sort_id->{subclock} ] : undef,
	    version       => $version ? [ $version->{clock}, $version->{replica_index}, $version->{subclock} ] : undef,
	    substrokes_version => $substrokes_version ? [ $substrokes_version->{clock}, $substrokes_version->{replica_index}, $substrokes_version->{subclock} ] : undef,
	    # Stroke.ink_index (field 4) - real field name confirmed as
	    # inkIndex, proven at disassembly level: index into Drawing.inks[]
	    # (see apple-notes-drawing-2.proto). Without this, there is no way
	    # to know which ink/color/tool a given stroke uses.
	    ink_index     => $st->{ink_index},
	    bounds        => $bounds ? [ $bounds->{origin_x}, $bounds->{origin_y}, $bounds->{width}, $bounds->{height} ] : undef,
	    transform     => $xf  ? [ @{$xf}{qw(a b c d tx ty)} ]  : undef,
	    ink_transform => $ixf ? [ @{$ixf}{qw(a b c d tx ty)} ] : undef,
	    # Fields 8/10/12/13/14/16 below are empirical-only (or, for 16,
	    # elimination-only) - see the .proto's Stroke message comment for
	    # what's actually been checked for each. Exposed as raw values
	    # since none of them have a confirmed decode formula beyond "here's
	    # the scalar/blob PencilKit itself wrote".
	    style_flags       => $st->{style_flags},
	    shape_type        => $st->{shape_type},
	    render_group_uuid => defined $st->{render_group_uuid} ? unpack("H*", $st->{render_group_uuid}) : undef,
	    group_uuid        => defined $st->{group_uuid} ? unpack("H*", $st->{group_uuid}) : undef,
	    # centerline_slices (field 10) - empirically (2026-09-21, full
	    # sweep of the user's own archive) every one of 3026 real blobs has
	    # a length that's a multiple of 8, and unpacking as flat little-
	    # endian float32 (x,y) pairs gives smooth, bounds-scale point
	    # sequences (not noise or an unrelated encoding) - see drawing.md's
	    # latest Update. Decoded as such here rather than left as opaque hex.
	    centerline_slices => [ map { decode_float_pairs($_) } @{ $st->{centerline_slices} || [] } ],
	    mask_points       => [ @{ $st->{mask_points} || [] } ],
	    stroke_data_uuid        => $stroke_data_uuid,
	    timestamp               => $timestamp,
	    points_constant_flags   => $points_constant_flags,
	    points_constant_values  => $points_constant_values,
	    created_with_finger     => $created_with_finger,
	    immutable_points_count  => $immutable_points_count,
	    start_index             => $start_index,
	    point_stride  => $stride,
	    points        => \@points,
	    substrokes    => [ map { decode_stroke($_) } @{ $st->{substrokes} || [] } ],
	};
}

# Reorders $drawing->{strokes} in place by ascending sort_id (clock,
# replica_index, subclock) - i.e. PencilKit's own +[PKDrawing sortStrokes:]
# comparator (see notes/apple/com.apple.drawing.2/drawing.md Update 6).
# Stored array order is usually already this order, but not always -
# confirmed empirically against real sample data - so anything that
# renders these drawings (painter's algorithm, later strokes on top)
# should sort explicitly rather than trust array order. Strokes with no
# sort_id (shouldn't happen in practice) sort last, keeping their relative
# order among themselves.
sub sort_strokes_by_sort_id {
	my ($drawing) = @_;
	my $key = sub {
		my ($st) = @_;
		return $st->{sort_id} ? [ 0, @{ $st->{sort_id} } ] : [ 1, 0, 0, 0 ];
	};
	@{ $drawing->{strokes} } = sort {
		my ($ka, $kb) = ($key->($a), $key->($b));
		$ka->[0] <=> $kb->[0] || $ka->[1] <=> $kb->[1]
		|| $ka->[2] <=> $kb->[2] || $ka->[3] <=> $kb->[3]
	} @{ $drawing->{strokes} };
}

# Unpack a raw byte string as flat little-endian float32 (x,y) pairs -
# Stroke.centerline_slices' per-element wire content (see drawing.md's
# latest Update: empirically confirmed divisible by 8 in every sample
# checked, decoding to smooth/bounds-scale point sequences). Returns an
# arrayref of [x,y] pairs; empty if the input isn't a clean multiple of 8
# bytes (shouldn't happen on real data, but don't die on malformed input).
sub decode_float_pairs {
	my ($bytes) = @_;
	return [] if length($bytes) % 8 != 0;
	my @pairs;
	for (my $off = 0; $off < length($bytes); $off += 8) {
		push @pairs, [ unpack("f<f<", substr($bytes, $off, 8)) ];
	}
	return \@pairs;
}

# Decode one channel's raw bytes per its 'kind' (see @POINT_CHANNELS above).
# Returns { kind => ..., value => ... } - 'value' is a Math::BigRat (exact
# rational; a fraction of PI for azimuth/altitude) for force/azimuth/
# altitude, a plain number for 'f32', or a hex string for 'hex'
# (unconfirmed).
sub decode_channel {
	my ($kind, $raw) = @_;
	if ($kind eq 'f32') {
		return { kind => $kind, value => unpack("f<", $raw) };
	} elsif ($kind eq 'force') {
		return { kind => $kind, value => unpack("v", $raw) / FORCE_DIVISOR };
	} elsif ($kind eq 'azimuth') {
		return { kind => $kind, value => unpack("v", $raw) / AZIMUTH_DIVISOR + AZIMUTH_BIAS };
	} elsif ($kind eq 'altitude') {
		return { kind => $kind, value => ALTITUDE_BIAS - unpack("v", $raw) / ALTITUDE_DIVISOR };
	} elsif ($kind eq 'aspect_ratio') {
		return { kind => $kind, value => unpack("v", $raw) / ASPECT_RATIO_DIVISOR };
	} elsif ($kind eq 'edge_width') {
		return { kind => $kind, value => unpack("v", $raw) / EDGE_WIDTH_DIVISOR };
	} elsif ($kind eq 'opacity') {
		return { kind => $kind, value => unpack("v", $raw) / OPACITY_DIVISOR };
	} elsif ($kind eq 'threshold') {
		return { kind => $kind, value => unpack("v", $raw) / THRESHOLD_DIVISOR };
	} else {
		return { kind => 'hex', value => unpack("H*", $raw) };
	}
}

# Render a list of optional plain float32 sub-fields (Stroke.transform/
# ink_transform's [a,b,c,d,tx,ty], or Stroke.bounds's [originX,originY,
# width,height]) - any element undef if that field was absent on the wire,
# since each sub-field is independently optional (see the .proto's
# Transform/Bounds message comments). None of these are normalized (unlike
# the per-point channels), so printed the same exact-IEEE-hex way as the
# f32 point channels rather than a lossy %.6f.
sub format_float_list {
	my ($xf) = @_;
	return join(" ", map { defined $_ ? float_hex($_) : "nil" } @$xf);
}

# --- output: s-expression ----------------------------------------------------

sub esc_str {
	my ($s) = @_;
	$s =~ s/(["\\])/\\$1/g;
	return "\"$s\"";
}

# Render one decode_channel() result for the s-expression's inline comment.
# 'hex' kinds are flagged unconfirmed; the others are confirmed decodings
# (see @POINT_CHANNELS / decode_channel() above).
sub format_channel {
	my ($c) = @_;
	return "nil" unless $c;
	if ($c->{kind} eq 'hex') {
		return sprintf("%s(hex,unconfirmed)", $c->{value});
	} elsif ($c->{kind} eq 'azimuth' || $c->{kind} eq 'altitude') {
		return $c->{value}->bstr() . " #| π |#";  # exact rational, no decimal; raw UTF-8 bytes, no `use utf8`/-CS
	} elsif ($c->{kind} eq 'force' || $c->{kind} eq 'aspect_ratio'
			|| $c->{kind} eq 'edge_width' || $c->{kind} eq 'opacity'
			|| $c->{kind} eq 'threshold') {
		return $c->{value}->bstr();  # exact rational
	} else {
		return float_hex($c->{value});  # f32 kind (timestamp/radius/radius2) - exact IEEE754 hex, not lossy decimal
	}
}

sub render_sexp {
	my ($d) = @_;
	my @out;
	push @out, "(drawing";
	push @out, "  (envelope-hex " . esc_str($d->{envelope_hex}) . ")";
	push @out, "  (uuid " . (defined $d->{uuid} ? esc_str($d->{uuid}) : "nil") . ")";
	if (@{ $d->{replica_uuids} }) {
	    push @out, "  (replica-uuids " . join(" ", map { esc_str($_) } @{ $d->{replica_uuids} }) . ")";
	}
	if (@{ $d->{version_vector} }) {
	    push @out, "  (version-vector " . join(" ", map { "(" . join(" ", map { $_ // "nil" } @$_) . ")" } @{ $d->{version_vector} }) . ") ; each (clock replicaIndex subclock), see drawing.md";
	}
	if ($d->{bounds}) {
	    push @out, "  (bounds " . format_float_list($d->{bounds}) . ") ; originX originY width height";
	}
	if ($d->{bounds_version}) {
	    push @out, "  (bounds-version " . join(" ", map { $_ // "nil" } @{ $d->{bounds_version} }) . ") ; clock replicaIndex subclock (StrokeID)";
	}
	push @out, "  (inks";
	for my $ink (@{ $d->{inks} }) {
		push @out, "    (ink";
		push @out, "      (identifier " . esc_str($ink->{identifier} // "") . ")";
		push @out, "      (color " . format_float_list($ink->{color}) . ") ; r g b a";
		push @out, "      (weight " . (defined $ink->{weight} ? float_hex($ink->{weight}) : "nil") . ")";
		push @out, "      (version " . (defined $ink->{version} ? $ink->{version} : "nil") . ")";
		push @out, "      (variant " . (defined $ink->{variant} ? esc_str($ink->{variant}) : "nil") . ")";
		push @out, "      (required-content-version " . (defined $ink->{required_content_version} ? $ink->{required_content_version} : "nil") . ")";
		push @out, "      (azimuth " . (defined $ink->{azimuth} ? float_hex($ink->{azimuth}) : "nil") . ") ; Ink-level azimuth, unrelated to the per-point azimuth channel";
		if ($ink->{hdr_headroom}) {
		    push @out, "      (hdr-headroom " . float_hex($ink->{hdr_headroom}) . ")";
		}
		if ($ink->{hdr_color}) {
		    push @out, "      (hdr-color " . format_float_list($ink->{hdr_color}) . ") ; r g b a";
		}
		if ($ink->{hdr_add_color}) {
		    push @out, "      (hdr-add-color " . format_float_list($ink->{hdr_add_color}) . ") ; r g b a";
		}
		if ($ink->{hdr_multiply_color}) {
		    push @out, "      (hdr-multiply-color " . format_float_list($ink->{hdr_multiply_color}) . ") ; r g b a";
		}
		if ($ink->{six_channel_add_color}) {
		    push @out, "      (six-channel-add-color " . format_float_list($ink->{six_channel_add_color}) . ") ; r g b a, same Color shape as color above";
		}
		if ($ink->{six_channel_multiply_color}) {
		    push @out, "      (six-channel-multiply-color " . format_float_list($ink->{six_channel_multiply_color}) . ") ; r g b a, same Color shape as color above";
		}
		push @out, "    )";
	}
	push @out, "  )";
	push @out, "  (strokes";
	push @out, render_stroke($_, "    ") for @{ $d->{strokes} };
	push @out, "  )";
	push @out, ")";
	return join("\n", @out) . "\n";
}

# Render one decode_stroke() result as an s-expression, recursing into
# Stroke.substrokes (field 11) at one deeper indent level. $prefix is the
# leading whitespace for this stroke's own "(stroke" line.
sub render_stroke {
	my ($st, $prefix) = @_;
	my @out;
	push @out, "${prefix}(stroke";
	push @out, "${prefix}  (uuid " . esc_str($st->{stroke_uuid}) . ")";
	push @out, "${prefix}  (sort-id " . ($st->{sort_id} ? join(" ", map { $_ // "nil" } @{ $st->{sort_id} }) : "nil") .
	            ") ; clock replicaIndex subclock, see drawing.md";
	if ($st->{version}) {
	    push @out, "${prefix}  (version " . join(" ", map { $_ // "nil" } @{ $st->{version} }) . ") ; clock replicaIndex subclock (StrokeID)";
	}
	if ($st->{substrokes_version}) {
	    push @out, "${prefix}  (substrokes-version " . join(" ", map { $_ // "nil" } @{ $st->{substrokes_version} }) . ") ; clock replicaIndex subclock (StrokeID)";
	}
	push @out, "${prefix}  (ink-index " . (defined $st->{ink_index} ? $st->{ink_index} : "nil") . ") ; index into drawing's (inks ...) list, proven";
	if ($st->{bounds}) {
	    push @out, "${prefix}  (bounds " . format_float_list($st->{bounds}) . ") ; originX originY width height";
	}
	if ($st->{transform}) {
	    push @out, "${prefix}  (transform " . format_float_list($st->{transform}) . ") ; a b c d tx ty";
	}
	if ($st->{ink_transform}) {
	    push @out, "${prefix}  (ink-transform " . format_float_list($st->{ink_transform}) . ") ; a b c d tx ty";
	}
	if (defined $st->{stroke_data_uuid}) {
	    push @out, "${prefix}  (stroke-data-uuid " . esc_str($st->{stroke_data_uuid}) . ") ; different UUID concept from (uuid ...) above, see drawing.md Update 3";
	}
	if (defined $st->{timestamp}) {
	    push @out, "${prefix}  (stroke-data-timestamp " . float_hex($st->{timestamp}) . ")";
	}
	if (defined $st->{points_constant_flags}) {
	    push @out, "${prefix}  (points-constant-flags " . $st->{points_constant_flags} . ") ; name confirmed, mechanism inferred only, see apple-notes-drawing-2.proto's StrokeData comment";
	}
	if (defined $st->{points_constant_values}) {
	    push @out, "${prefix}  (points-constant-values " . esc_str($st->{points_constant_values}) . ") ; name confirmed, mechanism inferred only, see apple-notes-drawing-2.proto's StrokeData comment";
	}
	if (defined $st->{created_with_finger}) {
	    push @out, "${prefix}  (created-with-finger " . ($st->{created_with_finger} ? "t" : "nil") . ")";
	}
	if (defined $st->{immutable_points_count}) {
	    push @out, "${prefix}  (immutable-points-count " . $st->{immutable_points_count} . ")";
	}
	if (defined $st->{start_index}) {
	    push @out, "${prefix}  (start-index " . $st->{start_index} . ")";
	}
	push @out, "${prefix}  (style-flags " . (defined $st->{style_flags} ? $st->{style_flags} : "nil") . ") ; name/meaning empirical only, see drawing.md";
	push @out, "${prefix}  (shape-type " . (defined $st->{shape_type} ? $st->{shape_type} : "nil") . ") ; name/meaning empirical only, see drawing.md";
	if (defined $st->{render_group_uuid}) {
	    push @out, "${prefix}  (render-group-uuid " . esc_str($st->{render_group_uuid}) . ") ; name empirical only, see drawing.md";
	}
	if (defined $st->{group_uuid}) {
	    push @out, "${prefix}  (group-uuid " . esc_str($st->{group_uuid}) . ") ; name elimination-only, see drawing.md";
	}
	if (@{ $st->{centerline_slices} }) {
	    push @out, "${prefix}  (centerline-slices " . join(" ", map {
	            "(" . join(" ", map { "(" . float_hex($_->[0]) . " " . float_hex($_->[1]) . ")" } @$_) . ")"
	        } @{ $st->{centerline_slices} }) . ") ; each slice: a flat polyline of (x y) points, field number empirical only, see drawing.md";
	}
	if (@{ $st->{mask_points} }) {
	    push @out, "${prefix}  (mask-points " . join(" ", map { float_hex($_) } @{ $st->{mask_points} }) . ") ; name empirical only, flat x,y pairs, see drawing.md";
	}
	push @out, "${prefix}  (point-stride " . (defined $st->{point_stride} ? $st->{point_stride} : "nil") . ")";
	push @out, "${prefix}  (points";
	for my $p (@{ $st->{points} }) {
	    my $chans = $p->{channels} || {};
	    my $chan_str = join("", map { " ($_ . " . format_channel($chans->{$_}) . ")" } sort keys %$chans);
	    push @out, sprintf("${prefix}    ((point %s %s)%s)",
	        float_hex($p->{x}), float_hex($p->{y}), $chan_str);
	}
	push @out, "${prefix}  )";
	if (@{ $st->{substrokes} }) {
	    push @out, "${prefix}  (substrokes";
	    push @out, render_stroke($_, "${prefix}    ") for @{ $st->{substrokes} };
	    push @out, "${prefix}  )";
	}
	push @out, "${prefix})";
	return join("\n", @out);
}

# --- output: InkML (X/Y geometry only) ---------------------------------------

sub render_inkml {
	my ($d) = @_;
	my @out;
	push @out, '<?xml version="1.0" encoding="UTF-8"?>';
	push @out, '<ink xmlns="http://www.w3.org/2003/InkML">';
	push @out, '  <traceFormat>';
	push @out, '    <channel name="X" type="decimal"/>';
	push @out, '    <channel name="Y" type="decimal"/>';
	push @out, '  </traceFormat>';

	# A single shared context: Stroke.ink_index (which ink/color/tool a
	# stroke uses, see decode_stroke()) is decoded now, but this renderer
	# doesn't emit per-ink InkML <brush>/<context> definitions from it yet -
	# X/Y geometry only, per this function's own doc comment above.
	push @out, '  <context xml:id="ctx0"/>';

	my $trace_i = 0;
	for my $st (@{ $d->{strokes} }) {
		next unless @{ $st->{points} };
		my @pts = map { sprintf("%.4f %.4f", $_->{x}, $_->{y}) } @{ $st->{points} };
		push @out, sprintf('  <trace id="t%d" contextRef="#ctx0">%s</trace>',
			$trace_i++, join(", ", @pts));
	}

	push @out, '</ink>';
	return join("\n", @out) . "\n";
}
