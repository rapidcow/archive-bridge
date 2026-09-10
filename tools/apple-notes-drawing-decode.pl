#!/usr/bin/env perl
# Decode a pre-Coherence Apple Notes PencilKit drawing (UTI "com.apple.drawing.2",
# stored in ZICCLOUDSYNCINGOBJECT.ZMERGEABLEDATA1 of NoteStore.sqlite) into
# either an s-expression or an InkML document.
#
# The blob's wire format is described in apple-notes-drawing.proto (in this
# same directory) - see that file for what's confirmed vs. guessed. This
# script skips the 8-byte envelope in front of that protobuf message, then
# unpacks the flat per-point float32 X/Y pairs that the .proto can't express
# (Geometry.points is a variable-stride blob, not a repeated field).
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

use strict;
use warnings;
use DBI;
use Getopt::Long;
use File::Basename;  # qw(basename)
use File::Spec;
use FindBin;

# As of Sept 2026, there is a new "Protobuf" distribution
# that comes straight from (an employee? of) Google:
# https://metacpan.org/release/CJCOLLIER/Protobuf-0.12/view/lib/Protobuf.pm
# https://github.com/GoogleCloudPlatform/google-auth-library-perl/tree/main/Protobuf
#
# I have never heard of it, so give it some time...
use Google::ProtocolBuffers;

my %INK_CATEGORY_GUESS = (
	2 => "pen",     # unconfirmed
	3 => "pencil",  # unconfirmed
	4 => "marker",  # unconfirmed
);

Google::ProtocolBuffers->parsefile(
	File::Spec->catfile( $FindBin::Bin, "apple-notes-drawing.proto" )
);

my $prog = basename($0);
my $usage = <<EOM;
usage: $0 --db NoteStore.sqlite --list
       $0 --db NoteStore.sqlite --pk N [--sexp|--inkml] [--out FILE]
EOM

my ($db, $pk, $list, $want_sexp, $want_inkml, $out);
GetOptions(
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
	return DBI->connect("dbi:SQLite:dbname=$db", '', '', {
		RaiseError => 1,
		PrintError => 0,
		AutoCommit => 1,
	});
}

# --- drawing decode ----------------------------------------------------------

sub decode_drawing {
	my ($blob) = @_;
	die "blob too short for 8-byte envelope\n" if length($blob) < 8;
	my $envelope = substr($blob, 0, 8);
	my $body     = substr($blob, 8);

	my $drawing = Drawing->decode($body);

	my @ink_defs;
	for my $ink (@{ $drawing->{ink_defs} || [] }) {
		my $rgba = $ink->{rgba} || {};
		push @ink_defs, {
			rgba   => [ $rgba->{r} || 0, $rgba->{g} || 0, $rgba->{b} || 0, $rgba->{a} || 0 ],
			name   => $ink->{name} || "",
			weight => $ink->{weight},
		};
	}

	my @strokes;
	for my $st (@{ $drawing->{strokes} || [] }) {
		my @points;
		my $stride;
		if (my $geom = $st->{geometry}) {
			my $npts  = $geom->{npts};
			my $pdata = $geom->{points};
			if (defined $npts && defined $pdata && $npts > 0
					&& length($pdata) % $npts == 0) {
				$stride = length($pdata) / $npts;
				for my $i (0 .. $npts - 1) {
					my $off = $i * $stride;
					my ($x, $y) = unpack("f<f<", substr($pdata, $off, 8));
					my $extra = substr($pdata, $off + 8, $stride - 8);
					push @points, { x => $x, y => $y, extra_hex => unpack("H*", $extra) };
				}
			}
		}

		my $category = $st->{seq_info} ? $st->{seq_info}{ink_category} : undef;
		my $bbox = $st->{bbox};

		push @strokes, {
		    uuid               => unpack("H*", $st->{uuid} || ""),
		    seq                => $st->{seq_info} ? $st->{seq_info}{seq} : undef,
		    ink_category       => $category,
		    ink_category_guess => $INK_CATEGORY_GUESS{ $category // -1 } || "unknown",
		    bbox               => $bbox ? [ $bbox->{f1}, $bbox->{f2}, $bbox->{f3}, $bbox->{f4} ] : undef,
		    point_stride       => $stride,
		    points             => \@points,
		};
	}

	return {
		envelope_hex => unpack("H*", $envelope),
		ink_defs     => \@ink_defs,
		strokes      => \@strokes,
	};
}

# --- output: s-expression ----------------------------------------------------

sub esc_str {
	my ($s) = @_;
	$s =~ s/(["\\])/\\$1/g;
	return "\"$s\"";
}

sub render_sexp {
	my ($d) = @_;
	my @out;
	push @out, "(drawing";
	push @out, "  (envelope-hex " . esc_str($d->{envelope_hex}) . ")";
	push @out, "  (ink-defs";
	for my $ink (@{ $d->{ink_defs} }) {
		push @out, sprintf(
		    "    (ink (name %s) (rgba %.6f %.6f %.6f %.6f) (weight %s))",
		    esc_str($ink->{name}),
		    @{ $ink->{rgba} },
		    defined $ink->{weight} ? $ink->{weight} : "nil");
	}
	push @out, "  )";
	push @out, "  (strokes";
	for my $st (@{ $d->{strokes} }) {
		push @out, "    (stroke";
		push @out, "      (uuid " . esc_str($st->{uuid}) . ")";
		push @out, "      (seq " . (defined $st->{seq} ? $st->{seq} : "nil") . ")";
		push @out, "      (ink-category-raw " . (defined $st->{ink_category} ? $st->{ink_category} : "nil") .
		            ") ; guess: " . $st->{ink_category_guess};
		if ($st->{bbox}) {
		    push @out, sprintf("      (bbox %.6f %.6f %.6f %.6f) ; order unconfirmed", @{ $st->{bbox} });
		}
		push @out, "      (point-stride " . (defined $st->{point_stride} ? $st->{point_stride} : "nil") . ")";
		push @out, "      (points";
		for my $p (@{ $st->{points} }) {
		    push @out, sprintf("        (%.6f %.6f) ; extra-hex %s",
		        $p->{x}, $p->{y}, $p->{extra_hex} || "");
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

	my $ctx_i = 0;
	my %ctx_for_category;
	for my $st (@{ $d->{strokes} }) {
		my $cat = $st->{ink_category_guess};
		next if exists $ctx_for_category{$cat};
		my $id = "ctx" . $ctx_i++;
		$ctx_for_category{$cat} = $id;
		push @out, sprintf('  <context xml:id="%s"><!-- ink category guess: %s (unconfirmed mapping) --></context>',
			$id, $cat);
	}

	my $trace_i = 0;
	for my $st (@{ $d->{strokes} }) {
		next unless @{ $st->{points} };
		my $ctx = $ctx_for_category{ $st->{ink_category_guess} };
		my @pts = map { sprintf("%.4f %.4f", $_->{x}, $_->{y}) } @{ $st->{points} };
		push @out, sprintf('  <trace id="t%d" contextRef="#%s">%s</trace>',
			$trace_i++, $ctx, join(", ", @pts));
	}

	push @out, '</ink>';
	return join("\n", @out) . "\n";
}
