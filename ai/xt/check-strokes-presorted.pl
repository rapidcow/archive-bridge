#!/usr/bin/env perl
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

my $ndrawings_checked = 0;
my $nmonotonic = 0;
my $nviolations = 0;

for my $pk (@$pks) {
	my ($blob) = $dbh->selectrow_array(
		"SELECT ZMERGEABLEDATA1 FROM ZICCLOUDSYNCINGOBJECT WHERE Z_PK=?", undef, $pk);
	next unless defined $blob && length($blob);
	my ($magic, $version, undef, $hdrlen) = unpack("V C C v", substr($blob, 0, 8));
	my $body = ($magic == 0xF0647277) ? substr($blob, $hdrlen) : $blob;
	my $drawing = eval { Drawing->decode($body) };
	next unless $drawing;
	my @strokes = @{ $drawing->{strokes} || [] };
	next unless @strokes >= 2;
	$ndrawings_checked++;
	my $prev_clock;
	my $monotonic = 1;
	for my $st (@strokes) {
		my $sid = $st->{sort_id} or next;
		my $clock = $sid->{clock};
		if (defined $prev_clock && defined $clock && $clock < $prev_clock) {
			$monotonic = 0;
		}
		$prev_clock = $clock if defined $clock;
	}
	if ($monotonic) { $nmonotonic++ } else { $nviolations++; say "pk=$pk NOT monotonic in array order" }
}

say "drawings checked (>=2 strokes): $ndrawings_checked";
say "array order matches ascending clock: $nmonotonic";
say "array order violates ascending clock: $nviolations";
