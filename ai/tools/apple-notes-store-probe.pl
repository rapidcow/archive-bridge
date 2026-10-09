#!/usr/bin/env perl
# General-purpose, content-blind survey of a NoteStore.sqlite: what is in
# it, how the rows relate, and what format each blob column holds - without
# decoding any blob's schema. Output is structure and counts only, never
# note titles, text or blob contents (blob hex prefixes appear only for
# blobs of unrecognized format).
#
# Core Data uses single-table inheritance: nearly every entity lives in
# ZICCLOUDSYNCINGOBJECT, told apart by Z_ENT, with columns shared between
# entities and disambiguated by numeric suffixes (ZNOTE/ZNOTE1,
# ZTYPEUTI/ZTYPEUTI1, ...) that are NOT stable between OS versions. So
# nothing here hard-codes a column; it is all derived from the data.
#
# Usage:
#   apple-notes-store-probe.pl --db NoteStore.sqlite [COMMAND ...]
#     --entities    Z_PRIMARYKEY: entity number, name, supertype, row count
#                   (rows are counted in ZICCLOUDSYNCINGOBJECT by Z_ENT, and
#                   in ZICNOTEDATA/ZICMEDIA-style side tables by table).
#     --tables      every table: row count, column count.
#     --columns     per entity in ZICCLOUDSYNCINGOBJECT: the columns that are
#                   non-NULL on at least one of its rows, with the count.
#     --relations   integer columns whose non-NULL values all equal some
#                   Z_PK of ZICCLOUDSYNCINGOBJECT: prints the target
#                   entity histogram (e.g. ZNOTE -> ICNote). A heuristic:
#                   a column of small ints can match by accident; look at
#                   the "of N" denominator and the names before trusting.
#     --blobs       every BLOB column: per (entity, detected format) counts.
#                   Formats: gzip+X, bplist, json, xml, protobuf (parses as
#                   wire format - weak evidence on short blobs), other.
#     --all         all of the above (the default when no command is given).
#     --notes       one line per note, to identify it in the Notes app: Z_PK,
#                   modified and created time (local time zone), folder,
#                   title, number of attachments (and how many are
#                   com.apple.paper), pinned/trash flags. UNLIKE every other
#                   command this prints note titles. Sorted by most recently
#                   modified first. Column names carry Core Data suffixes
#                   (ZTITLE1, ZCREATIONDATE3, ...); each is resolved by
#                   prefix to the candidate populated for that entity.
#     --uti         histogram of ZTYPEUTI and ZTYPEUTI1 values per entity.
#
# The database is copied (with -wal/-shm) and the copy opened, as in
# apple-notes-paper-probe.pl; --immutable skips that and warns on a
# non-empty WAL, since ?immutable=1 ignores it.

use 5.010;
use strict;
use warnings;
use Getopt::Long;
use File::Copy;
use File::Spec;
use File::Temp;
use IO::Uncompress::Gunzip qw(gunzip);
use POSIX qw(strftime);

use DBD::SQLite::Constants qw(:file_open);
use DBI;
use URI::file;

my ($db_path, $immutable, %cmd);
GetOptions(
	'db=s'      => \$db_path,
	'immutable' => \$immutable,
	'entities'  => \$cmd{entities},
	'tables'    => \$cmd{tables},
	'columns'   => \$cmd{columns},
	'relations' => \$cmd{relations},
	'blobs'     => \$cmd{blobs},
	'uti'       => \$cmd{uti},
	'notes'     => \$cmd{notes},
	'all'       => \$cmd{all},
) or die "bad options (see the header of $0)\n";
die "--db is required\n" unless defined $db_path && -f $db_path;
$cmd{all} = 1 unless grep { $_ } map { $cmd{$_} } grep { $_ ne 'all' } keys %cmd;

our $snapshot_dir;   # kept alive until exit
my $dbh = connect_db($db_path);
my %ent_name = map { @$_ } @{ $dbh->selectall_arrayref('SELECT Z_ENT, Z_NAME FROM Z_PRIMARYKEY') };
my $MAIN = 'ZICCLOUDSYNCINGOBJECT';

section('entities',  \&entities)  if $cmd{entities}  || $cmd{all};
section('tables',    \&tables)    if $cmd{tables}    || $cmd{all};
section('columns',   \&columns)   if $cmd{columns}   || $cmd{all};
section('relations', \&relations) if $cmd{relations} || $cmd{all};
section('notes',     \&notes)     if $cmd{notes};   # prints titles: never part of --all
section('uti',       \&uti)       if $cmd{uti}       || $cmd{all};
section('blobs',     \&blobs)     if $cmd{blobs}     || $cmd{all};

sub section { my ($name, $code) = @_; say "== $name"; $code->(); say '' }

# --- connection ------------------------------------------------------------

sub connect_db {
	my ($path) = @_;
	if ($immutable) {
		warn "warning: $path-wal is non-empty and --immutable ignores it\n"
			if -s "$path-wal";
		my $uri = URI::file->new(File::Spec->rel2abs($path));
		$uri->query('mode=ro&immutable=1');
		return DBI->connect("dbi:SQLite:uri=$uri", '', '', {
			RaiseError => 1, PrintError => 0, sqlite_open_flags => SQLITE_OPEN_READONLY });
	}
	$snapshot_dir = File::Temp->newdir('apple-notes-probe-XXXXXX', TMPDIR => 1);
	for my $suffix ('', '-wal', '-shm') {
		next unless -e "$path$suffix";
		copy("$path$suffix", "$snapshot_dir/NoteStore.sqlite$suffix") or die "copy $path$suffix: $!\n";
		chmod 0600, "$snapshot_dir/NoteStore.sqlite$suffix";
	}
	return DBI->connect("dbi:SQLite:dbname=$snapshot_dir/NoteStore.sqlite", '', '', {
		RaiseError => 1, PrintError => 0 });
}

# --- helpers ---------------------------------------------------------------

sub columns_of {
	my ($table) = @_;
	return map { $_->[1] } @{ $dbh->selectall_arrayref("PRAGMA table_info($table)") };
}

sub ent_label { my ($e) = @_; return defined $e ? "$e " . ($ent_name{$e} // '?') : 'NULL' }

# Core Data's suffix (ZTITLE vs ZTITLE1 vs ZTITLE2) depends on the model
# version, not on the entity's meaning. Find the column named BASE plus an
# optional number that is populated on the most rows of entity ENT; undef if
# none is, and a warning if the best two tie.
my %resolved;
sub resolve_col {
	my ($ent, $base) = @_;
	return $resolved{"$ent/$base"} //= do {
		my @cand = grep { /^\Q$base\E\d*$/ } columns_of($MAIN);
		my @scored = sort { $b->[1] <=> $a->[1] } map {
			[ $_, scalar $dbh->selectrow_array(
				qq{SELECT count("$_") FROM $MAIN WHERE Z_ENT = ?}, undef, $ent) ]
		} @cand;
		warn "warning: $base is ambiguous for entity $ent: $scored[0][0] vs $scored[1][0]\n"
			if @scored > 1 && $scored[0][1] && $scored[0][1] == $scored[1][1];
		@scored && $scored[0][1] ? $scored[0][0] : undef;
	};
}

sub local_time {
	my ($t) = @_;
	return '-' unless defined $t;
	return strftime('%Y-%m-%d %H:%M %z', localtime($t + 978307200));
}

# --- commands --------------------------------------------------------------

sub entities {
	my %n = map { @$_ } @{ $dbh->selectall_arrayref("SELECT Z_ENT, count(*) FROM $MAIN GROUP BY Z_ENT") };
	my $sup = $dbh->selectall_arrayref('SELECT Z_ENT, Z_NAME, Z_SUPER, Z_MAX FROM Z_PRIMARYKEY ORDER BY Z_ENT');
	say join "\t", qw(Z_ENT name super rows_in_main z_max);
	for my $r (@$sup) {
		my $s = $r->[2] ? ($ent_name{ $r->[2] } // $r->[2]) : '-';
		say join "\t", $r->[0], $r->[1], $s, $n{ $r->[0] } // 0, $r->[3] // '-';
	}
}

sub tables {
	my $t = $dbh->selectcol_arrayref(q{SELECT name FROM sqlite_master WHERE type='table' ORDER BY name});
	say join "\t", qw(table rows columns);
	for my $name (@$t) {
		next if $name =~ /^sqlite_/;
		my ($n) = $dbh->selectrow_array(qq{SELECT count(*) FROM "$name"});
		say join "\t", $name, $n, scalar(my @c = columns_of($name));
	}
}

sub columns {
	my @cols = columns_of($MAIN);
	my $ents = $dbh->selectcol_arrayref("SELECT DISTINCT Z_ENT FROM $MAIN ORDER BY Z_ENT");
	for my $e (@$ents) {
		my ($rows) = $dbh->selectrow_array("SELECT count(*) FROM $MAIN WHERE Z_ENT = ?", undef, $e);
		say ent_label($e), " ($rows rows)";
		my $sel = join ', ', map { qq{count("$_")} } @cols;
		my @counts = $dbh->selectrow_array("SELECT $sel FROM $MAIN WHERE Z_ENT = ?", undef, $e);
		my @used = map { "$cols[$_]=$counts[$_]" } grep { $counts[$_] } 0 .. $#cols;
		# Z_PK/Z_ENT/Z_OPT are bookkeeping on every row.
		say "  ", join ' ', grep { !/^Z_(PK|ENT|OPT)=/ } @used;
	}
}

sub relations {
	my @cols = grep { !/^Z_(PK|ENT|OPT)$/ } columns_of($MAIN);
	my %pk_ent = map { @$_ } @{ $dbh->selectall_arrayref("SELECT Z_PK, Z_ENT FROM $MAIN") };
	say join "\t", qw(from_entity column -> target_entity count of);
	for my $c (@cols) {
		my ($ints, $nn) = $dbh->selectrow_array(
			qq{SELECT sum(typeof("$c")='integer'), count("$c") FROM $MAIN});
		next unless $nn && ($ints // 0) == $nn;   # every non-NULL value is an integer
		my $rows = $dbh->selectall_arrayref(
			qq{SELECT Z_ENT, "$c", count(*) FROM $MAIN WHERE "$c" IS NOT NULL GROUP BY Z_ENT, "$c"});
		my %hist;   # from_ent => { target_ent => count }
		my %total;
		my %miss;
		for my $r (@$rows) {
			my ($from, $v, $k) = @$r;
			$total{$from} += $k;
			if (defined $pk_ent{$v}) { $hist{$from}{ $pk_ent{$v} } += $k } else { $miss{$from} += $k }
		}
		for my $from (sort { $a <=> $b } keys %total) {
			# Only report columns where >= 90% of values resolve to a Z_PK
			# and a single target entity dominates; flags and counters
			# (0/1/small ints) otherwise match everything.
			my %h = %{ $hist{$from} // {} };
			my ($best) = sort { $h{$b} <=> $h{$a} } keys %h;
			next unless defined $best;
			my $ok = $h{$best};
			next unless $ok >= 0.9 * $total{$from};
			next if $total{$from} < 2 && $ok < 1;
			say join "\t", ent_label($from), $c, '->', ent_label($best), $ok, $total{$from};
		}
	}
}

sub notes {
	my ($NOTE, $ATT, $FOLDER) = map { $dbh->selectrow_array(
		'SELECT Z_ENT FROM Z_PRIMARYKEY WHERE Z_NAME = ?', undef, $_) } qw(ICNote ICAttachment ICFolder);
	my %c = (
		title    => resolve_col($NOTE, 'ZTITLE'),
		created  => resolve_col($NOTE, 'ZCREATIONDATE'),
		modified => resolve_col($NOTE, 'ZMODIFICATIONDATE'),
		folder   => resolve_col($NOTE, 'ZFOLDER'),
		pinned   => resolve_col($NOTE, 'ZISPINNED'),
		a_note   => resolve_col($ATT,  'ZNOTE'),
		a_uti    => resolve_col($ATT,  'ZTYPEUTI'),
		f_title  => resolve_col($FOLDER, 'ZTITLE'),
		f_type   => resolve_col($FOLDER, 'ZFOLDERTYPE'),
	);
	for (keys %c) { die "cannot resolve a column for '$_'\n" unless defined $c{$_} }
	my %folder = map { $_->[0] => $_ } @{ $dbh->selectall_arrayref(
		qq{SELECT Z_PK, "$c{f_title}", "$c{f_type}" FROM $MAIN WHERE Z_ENT = ?}, undef, $FOLDER) };
	my %att;   # note pk => [total, paper]
	for my $r (@{ $dbh->selectall_arrayref(
		qq{SELECT "$c{a_note}", "$c{a_uti}" FROM $MAIN WHERE Z_ENT = ?}, undef, $ATT) }) {
		next unless defined $r->[0];
		$att{ $r->[0] }[0]++;
		$att{ $r->[0] }[1]++ if ($r->[1] // '') eq 'com.apple.paper';
	}
	my $rows = $dbh->selectall_arrayref(qq{
		SELECT Z_PK, "$c{title}", "$c{created}", "$c{modified}", "$c{folder}", "$c{pinned}"
		FROM $MAIN WHERE Z_ENT = ? ORDER BY "$c{modified}" DESC, Z_PK}, undef, $NOTE);
	say join "\t", qw(Z_PK modified created folder attach(paper) flags title);
	for my $r (@$rows) {
		my ($pk, $title, $cr, $mo, $fo, $pin) = @$r;
		my $f = defined $fo ? $folder{$fo} : undef;
		my $a = $att{$pk} // [0, 0];
		my @flags;
		push @flags, 'pinned' if $pin;
		push @flags, 'trash' if $f && $f->[2] == 1;   # ICFolder.ZFOLDERTYPE: 1 = Recently Deleted
		(my $t = $title // '(untitled)') =~ s/[\t\r\n]+/ /g;
		say join "\t", $pk, local_time($mo), local_time($cr), $f ? $f->[1] // '?' : '-',
			"$a->[0]($a->[1])", join(',', @flags) || '-', $t;
	}
}

sub uti {
	for my $c (grep { /^ZTYPEUTI\d*$/ } columns_of($MAIN)) {
		say $c;
		my $rows = $dbh->selectall_arrayref(
			qq{SELECT Z_ENT, "$c", count(*) FROM $MAIN GROUP BY Z_ENT, "$c" ORDER BY Z_ENT, 3 DESC});
		say "  ", ent_label($_->[0]), "\t", $_->[1] // 'NULL', "\t", $_->[2] for @$rows;
	}
}

# --- blob format sniffing --------------------------------------------------

# True if BYTES parse end-to-end as protobuf wire format. Weak evidence:
# short or textual blobs can parse by chance.
sub looks_like_protobuf {
	my ($b) = @_;
	my ($i, $n) = (0, length $b);
	return 0 unless $n;
	my $varint = sub {
		my ($v, $s) = (0, 0);
		while (1) {
			return undef if $i >= $n || $s > 63;
			my $c = ord substr($b, $i++, 1);
			$v |= ($c & 0x7f) << $s; $s += 7;
			return $v unless $c & 0x80;
		}
	};
	while ($i < $n) {
		my $k = $varint->() // return 0;
		my ($f, $w) = ($k >> 3, $k & 7);
		return 0 if $f == 0;
		if    ($w == 0) { $varint->() // return 0 }
		elsif ($w == 1) { $i += 8 }
		elsif ($w == 5) { $i += 4 }
		elsif ($w == 2) { my $l = $varint->() // return 0; $i += $l }
		else            { return 0 }
		return 0 if $i > $n;
	}
	return 1;
}

sub sniff {
	my ($b) = @_;
	my $prefix = '';
	if ($b =~ /^\x1f\x8b/) {
		my $o;
		gunzip(\$b => \$o) or return 'gzip(corrupt)';
		($b, $prefix) = ($o, 'gzip+');
	}
	return $prefix . 'bplist' if $b =~ /^bplist\d\d/;
	return $prefix . 'xml'    if $b =~ /^\s*<\?xml/;
	return $prefix . 'json'   if $b =~ /^\s*[\{\[]/ && $b =~ /^[\x20-\x7e\s\x80-\xff]*\z/;
	return $prefix . 'protobuf' if looks_like_protobuf($b);
	return $prefix . 'other(' . unpack('H8', $b) . ')';
}

sub blobs {
	my $tables = $dbh->selectcol_arrayref(q{SELECT name FROM sqlite_master WHERE type='table' ORDER BY name});
	for my $t (@$tables) {
		next if $t =~ /^sqlite_/;
		my $has_ent = grep { $_ eq 'Z_ENT' } columns_of($t);
		for my $c (columns_of($t)) {
			my ($n) = $dbh->selectrow_array(qq{SELECT count(*) FROM "$t" WHERE typeof("$c") = 'blob'});
			next unless $n;
			my $rows = $dbh->selectall_arrayref(
				'SELECT ' . ($has_ent ? 'Z_ENT' : 'NULL') . qq{, "$c" FROM "$t" WHERE typeof("$c") = 'blob'});
			my %k;
			$k{ ($has_ent ? ent_label($_->[0]) : '-') . ": " . sniff($_->[1]) }++ for @$rows;
			say "$t.$c  n=$n  ", join ', ', map { "$_=$k{$_}" } sort keys %k;
		}
	}
}
