#!/usr/bin/env perl
# Throwaway probe: decode many real drawings, tally raw Stroke fields
# 1, 8, 10, 12, 13, 14, 16 (the ones still tied between multiple candidate
# real names - see notes/apple/com.apple.drawing.2/drawing.md's update
# section) directly off Google::ProtocolBuffers' Drawing->decode output,
# bypassing decode_drawing()'s narrower field selection entirely.
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

my %f1_len; my %f13_len; my %f16_len;
my %f8_vals; my %f14_vals;
my %f10_count; my %f12_count;
my $nstrokes = 0;
my $ndrawings = 0;

for my $pk (@$pks) {
	my ($blob) = $dbh->selectrow_array(
		"SELECT ZMERGEABLEDATA1 FROM ZICCLOUDSYNCINGOBJECT WHERE Z_PK=?", undef, $pk);
	next unless defined $blob && length($blob);
	my ($magic, $version, undef, $hdrlen) = unpack("V C C v", substr($blob, 0, 8));
	my $body = ($magic == 0xF0647277) ? substr($blob, $hdrlen) : $blob;
	my $drawing = eval { Drawing->decode($body) };
	next unless $drawing;
	$ndrawings++;
	for my $st (@{ $drawing->{strokes} || [] }) {
		$nstrokes++;
		if (defined $st->{stroke_uuid}) { $f1_len{length($st->{stroke_uuid})}++; }
		if (defined $st->{render_group_uuid}) { $f13_len{length($st->{render_group_uuid})}++; }
		if (defined $st->{group_uuid}) { $f16_len{length($st->{group_uuid})}++; }
		if (defined $st->{style_flags}) { $f8_vals{$st->{style_flags}}++; }
		if (defined $st->{shape_type}) { $f14_vals{$st->{shape_type}}++; }
		if (@{ $st->{centerline_slices} || [] }) { $f10_count{scalar @{$st->{centerline_slices}}}++; }
		if (@{ $st->{mask_points} || [] }) { $f12_count{scalar @{$st->{mask_points}}}++; }
	}
}

say "drawings=$ndrawings strokes=$nstrokes";
say "f1 (stroke_uuid) lengths: " . join(", ", map { "$_=>$f1_len{$_}" } sort keys %f1_len);
say "f13 lengths: " . join(", ", map { "$_=>$f13_len{$_}" } sort keys %f13_len);
say "f16 lengths: " . join(", ", map { "$_=>$f16_len{$_}" } sort keys %f16_len);
say "f8 values: " . join(", ", map { "$_=>$f8_vals{$_}" } sort { $a <=> $b } keys %f8_vals);
say "f14 values: " . join(", ", map { "$_=>$f14_vals{$_}" } sort { $a <=> $b } keys %f14_vals);
say "f10 (centerline_slices) elem-counts: " . join(", ", map { "$_=>$f10_count{$_}" } sort { $a <=> $b } keys %f10_count);
say "f12 (mask_points) elem-counts: " . join(", ", map { "$_=>$f12_count{$_}" } sort { $a <=> $b } keys %f12_count);
