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

use constant M_PI2 => 2*atan2(1,1);  # π/2
use constant M_PI  => 4*atan2(1,1);  # π
use constant M_TAU => 8*atan2(1,1);  # 2π
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
#           via disassembly of PKStrokePathPointsPrivate's *AtIndex accessors
#   force - little-endian uint16, decoded value = raw / 1000.0 - confirmed
#           via disassembly (ucvtf, not fcvt - i.e. NOT float16 - then fdiv
#           by an exact 1000.0 double read out of PencilKit's __TEXT), see
#           notes/apple/com.apple.drawing.2/point-decode-pipeline.md §8
#   azimuth  - little-endian uint16, decoded value = raw*(2*PI/65535) - PI
#              (radians) - confirmed the same way as force, §8
#   altitude - little-endian uint16, decoded value =
#              PI/2 - raw*(PI/2/65535) (radians) - confirmed the same way,
#              §8; note the bias is subtracted from, not added to
#   hex   - raw bytes, hex-encoded, no known decoding yet (aspectRatio/
#           edgeWidth/opacity/threshold - presumed uint16 fixed-point by
#           analogy to force/azimuth/altitude, per §8's "Not concluded",
#           but not individually confirmed)
use constant AZIMUTH_DIVISOR  => 65535 / M_TAU;
use constant AZIMUTH_BIAS     => (- M_PI);
use constant ALTITUDE_DIVISOR => 65535 / M_PI2;
use constant ALTITUDE_BIAS    => M_PI2;
my @POINT_CHANNELS = (
	undef,                        # index 0: location, handled separately
	[ timestamp   => 4, 'f32'      ],
	[ radius      => 4, 'f32'      ],
	[ aspectRatio => 2, 'hex'      ],
	[ edgeWidth   => 2, 'hex'      ],
	[ force       => 2, 'force'    ],
	[ azimuth     => 2, 'azimuth'  ],
	[ altitude    => 2, 'altitude' ],
	[ opacity     => 2, 'hex'      ],
	[ radius2     => 4, 'f32'      ],
	[ threshold   => 2, 'hex'      ],
);

Google::ProtocolBuffers->parsefile(
	File::Spec->catfile( $FindBin::Bin, "apple-notes-drawing-2.proto" )
);

my $prog = basename($0);
my $usage = <<EOM;
usage: $prog --db NoteStore.sqlite [--immutable] --list
       $prog --db NoteStore.sqlite [--immutable] --pk N [--sexp|--inkml] [--out FILE]
EOM

my ($db, $pk, $list, $want_sexp, $want_inkml, $out);
my $immutable;
GetOptions(
	"immutable" => \$immutable,
	"db=s"   => \$db,
	"pk=i"   => \$pk,
	"list"   => \$list,
	"sexp"   => \$want_sexp,
	"inkml"  => \$want_inkml,
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

	my @inks;
	for my $ink (@{ $drawing->{inks} || [] }) {
		my $color = $ink->{color} || {};
		push @inks, {
			color      => [ $color->{r} || 0, $color->{g} || 0, $color->{b} || 0, $color->{a} || 0 ],
			identifier => $ink->{identifier} || "",
			weight     => $ink->{weight},
		};
	}

	my @strokes;
	for my $st (@{ $drawing->{strokes} || [] }) {
		my @points;
		my $stride;
		if (my $geom = $st->{stroke_data}) {
			my $npts  = $geom->{npts};
			my $pdata = $geom->{points};
			my $flags = $geom->{point_flags} // 0;
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

		# Stroke.sort_id (field 2) - real field name confirmed as sortID,
		# type confirmed as StrokeID (see apple-notes-drawing-2.proto and
		# notes/apple/com.apple.drawing.2/drawing.md) - but StrokeID's own
		# f1/f2/f3 sub-fields are still unconfirmed, so exposed here only as
		# raw, unlabeled integers.
		my $sort_id = $st->{sort_id};
		my $bounds = $st->{bounds};

		push @strokes, {
		    stroke_uuid  => unpack("H*", $st->{stroke_uuid} || ""),
		    sort_id      => $sort_id ? [ $sort_id->{f1}, $sort_id->{f2}, $sort_id->{f3} ] : undef,
		    bounds       => $bounds ? [ $bounds->{f1}, $bounds->{f2}, $bounds->{f3}, $bounds->{f4} ] : undef,
		    point_stride => $stride,
		    points       => \@points,
		};
	}

	return {
		envelope_hex => unpack("H*", $envelope),
		inks         => \@inks,
		strokes      => \@strokes,
	};
}

# Decode one channel's raw bytes per its 'kind' (see @POINT_CHANNELS above).
# Returns { kind => ..., value => ... } - 'value' is a number for confirmed
# kinds (f32/force/azimuth/altitude), a hex string for 'hex' (unconfirmed).
sub decode_channel {
	my ($kind, $raw) = @_;
	if ($kind eq 'f32') {
		return { kind => $kind, value => unpack("f<", $raw) };
	} elsif ($kind eq 'force') {
		return { kind => $kind, value => unpack("v", $raw) / 1000.0 };
	} elsif ($kind eq 'azimuth') {
		return { kind => $kind, value => unpack("v", $raw) * (1 / AZIMUTH_DIVISOR) + AZIMUTH_BIAS };
	} elsif ($kind eq 'altitude') {
		return { kind => $kind, value => ALTITUDE_BIAS - unpack("v", $raw) / ALTITUDE_DIVISOR };
	} else {
		return { kind => 'hex', value => unpack("H*", $raw) };
	}
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
		return sprintf("%.6frad", $c->{value});
	} else {
		return sprintf("%.6f", $c->{value});
	}
}

sub render_sexp {
	my ($d) = @_;
	my @out;
	push @out, "(drawing";
	push @out, "  (envelope-hex " . esc_str($d->{envelope_hex}) . ")";
	push @out, "  (inks";
	for my $ink (@{ $d->{inks} }) {
		push @out, sprintf(
		    "    (ink (identifier %s) (color %.6f %.6f %.6f %.6f) (weight %s))",
		    esc_str($ink->{identifier}),
		    @{ $ink->{color} },
		    defined $ink->{weight} ? $ink->{weight} : "nil");
	}
	push @out, "  )";
	push @out, "  (strokes";
	for my $st (@{ $d->{strokes} }) {
		push @out, "    (stroke";
		push @out, "      (uuid " . esc_str($st->{stroke_uuid}) . ")";
		push @out, "      (sort-id " . ($st->{sort_id} ? join(" ", map { $_ // "nil" } @{ $st->{sort_id} }) : "nil") .
		            ") ; field name confirmed, sub-fields f1/f2/f3 unconfirmed - see drawing.md";
		if ($st->{bounds}) {
		    push @out, sprintf("      (bounds %.6f %.6f %.6f %.6f) ; order unconfirmed", @{ $st->{bounds} });
		}
		push @out, "      (point-stride " . (defined $st->{point_stride} ? $st->{point_stride} : "nil") . ")";
		push @out, "      (points";
		for my $p (@{ $st->{points} }) {
		    my $chans = $p->{channels} || {};
		    my $chan_str = join(" ", map { "$_=" . format_channel($chans->{$_}) } sort keys %$chans);
		    push @out, sprintf("        (%.6f %.6f) ; channels: %s",
		        $p->{x}, $p->{y}, $chan_str);
		}
		push @out, "      )";
		push @out, "    )";
	}
	push @out, "  )";
	push @out, ")";
	return join("\n", @out) . "\n";
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

	# A single shared context: there is no confirmed per-stroke ink-category
	# signal to group by (the earlier "ink_category" guess was withdrawn -
	# see apple-notes-drawing-2.proto and drawing.md - and the real
	# ink-selection mechanism, Stroke.inkIndex, isn't decoded by this
	# script at all yet).
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
