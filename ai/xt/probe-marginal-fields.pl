#!/usr/bin/env perl
# Throwaway probe: over the whole local archive, tally how often each
# "marginal" (wire shape confirmed, semantics/usage unconfirmed) Ink/Stroke
# field is actually populated: Ink's weight/hdrHeadroom/hdrColor/
# hdrAddColor/hdrMultiplyColor/sixChannelAddColor/sixChannelMultiplyColor/
# azimuth/requiredContentVersion, and Stroke's centerlineSlices/maskPoints.
# Counts only - no field VALUES are printed for anything content-shaped
# (colors/azimuth numbers), just presence counts, so this is safe to run
# without dumping personal drawing content.
use 5.010;
use strict;
use warnings;
use File::Spec;
use FindBin;
use DBD::SQLite::Constants qw(:file_open);
use DBI;
use Google::ProtocolBuffers;
use URI::file;

Google::ProtocolBuffers->parsefile(
	File::Spec->catfile($FindBin::Bin, "..", "tools", "apple-notes-drawing-2.proto")
);

my $db = $ENV{NOTESTORE} or die "set \$NOTESTORE\n";
my $uri = URI::file->new(File::Spec->rel2abs($db));
$uri->query('mode=ro&immutable=1');
my $dbh = DBI->connect("dbi:SQLite:uri=$uri", '', '', {
	RaiseError => 1, PrintError => 0, AutoCommit => 1,
	sqlite_open_flags => SQLITE_OPEN_READONLY });

my $pks = $dbh->selectcol_arrayref(
	"SELECT Z_PK FROM ZICCLOUDSYNCINGOBJECT WHERE ZTYPEUTI='com.apple.drawing.2' ORDER BY Z_PK");

my %ink_field_count;
my $ninks = 0;
my %stroke_field_count;
my $nstrokes = 0;
my $ndrawings = 0;

my @ink_fields = qw(weight version variant required_content_version
	hdr_headroom hdr_color hdr_add_color hdr_multiply_color
	six_channel_add_color six_channel_multiply_color azimuth);
my @stroke_fields = qw(centerline_slices mask_points);

sub walk_strokes {
	my ($strokes) = @_;
	for my $st (@$strokes) {
		$nstrokes++;
		$stroke_field_count{centerline_slices}++ if @{ $st->{centerline_slices} || [] };
		$stroke_field_count{mask_points}++       if @{ $st->{mask_points} || [] };
		walk_strokes($st->{substrokes} || []);
	}
}

for my $pk (@$pks) {
	my ($blob) = $dbh->selectrow_array(
		"SELECT ZMERGEABLEDATA1 FROM ZICCLOUDSYNCINGOBJECT WHERE Z_PK=?", undef, $pk);
	next unless defined $blob && length($blob);

	my ($magic, $hdrlen) = unpack("V x2 v", substr($blob, 0, 8));
	my $body = ($magic == 0xF0647277 && $hdrlen >= 8 && $hdrlen < length($blob))
		? substr($blob, $hdrlen) : $blob;

	my $drawing = eval { Drawing->decode($body) };
	next unless $drawing;
	$ndrawings++;

	for my $ink (@{ $drawing->{inks} || [] }) {
		$ninks++;
		$ink_field_count{weight}++                     if defined $ink->{weight};
		$ink_field_count{version}++                    if defined $ink->{version};
		$ink_field_count{variant}++                     if defined $ink->{variant};
		$ink_field_count{required_content_version}++   if defined $ink->{required_content_version};
		$ink_field_count{hdr_headroom}++                if defined $ink->{hdr_headroom};
		$ink_field_count{hdr_color}++                   if defined $ink->{hdr_color};
		$ink_field_count{hdr_add_color}++               if defined $ink->{hdr_add_color};
		$ink_field_count{hdr_multiply_color}++          if defined $ink->{hdr_multiply_color};
		$ink_field_count{six_channel_add_color}++       if defined $ink->{six_channel_add_color};
		$ink_field_count{six_channel_multiply_color}++  if defined $ink->{six_channel_multiply_color};
		$ink_field_count{azimuth}++                     if defined $ink->{azimuth};
	}

	walk_strokes($drawing->{strokes} || []);
}

print "drawings=$ndrawings inks=$ninks strokes=$nstrokes\n";
print "-- ink fields --\n";
for my $f (@ink_fields) {
	printf "%-28s %d/%d\n", $f, $ink_field_count{$f} // 0, $ninks;
}
print "-- stroke fields --\n";
for my $f (@stroke_fields) {
	printf "%-28s %d/%d\n", $f, $stroke_field_count{$f} // 0, $nstrokes;
}
