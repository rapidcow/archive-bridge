#!/usr/bin/env perl
# Probe the NoteStore.sqlite side ("the envelope") of com.apple.paper
# attachments: the ICAttachment rows that point at
# Accounts/<acct>/Paper/Bundles/<ZIDENTIFIER>.bundle/Database/data.sqlite3.
# It never looks inside a bundle's Coherence payload (see
# apple-notes-paper-dump.pl for that); the one thing it asks of a bundle is
# whether it opens and has a Reference table.
#
# What is established is written up in
# notes/apple/notestore-sqlite/com.apple.paper-envelope.md; this script is
# for re-checking it against other accounts/archives.
#
# Usage:
#   apple-notes-paper-probe.pl --db NoteStore.sqlite [--check]
#       Default action. Cross-checks rows, bundles, fallback images and
#       note bodies in both directions and prints a summary plus one line
#       per anomaly.
#
#   apple-notes-paper-probe.pl --db NoteStore.sqlite --list
#       One line per attachment: Z_PK, ZIDENTIFIER, ZNOTE, dates. No note
#       titles or recognized-handwriting text (see --show-text).
#
#   apple-notes-paper-probe.pl --db NoteStore.sqlite --pk 10
#   apple-notes-paper-probe.pl --db NoteStore.sqlite --id 9A5E83A6-...
#       Everything known about one attachment: columns, metadata JSON,
#       resolved paths, the owning note's attachment_info run.
#       Add --server-record to also unarchive ZSERVERRECORDDATA (the
#       CKRecord NSKeyedArchiver bplist; sync metadata only).
#
# By default the database is first COPIED, together with its -wal and -shm,
# to a temporary directory and the copy is opened. This matters:
# ?immutable=1 makes SQLite ignore the WAL, and on the sample account the
# WAL held a change (52 vs 51 notes) that the immutable view missed. Use
# --immutable only for a fixed archive whose -wal is known to be empty; the
# script warns if it is not. (Bundle databases are always opened immutable,
# with the same warning for a non-empty -wal.)
#
# Option --proto FILE: ciofeca's notestore.proto (default: $APPLE_NOTES_PROTO,
# then ~/net/github.com/threeplanetssoftware/apple_cloud_notes_parser/
# proto/notestore.proto). Option --show-text prints the recognized
# handwriting summary in --pk output.
#
# Mac::PropertyList: needs the patched ReadBinary (64-bit trailer fields);
# installed into ~/perl5 here, so no -I is needed.

use 5.010;
use strict;
use warnings;
use Getopt::Long;
use File::Basename;  # qw(dirname)
use File::Copy;      # qw(copy)
use File::Spec;
use File::Temp;      # qw(tempdir)
use IO::Uncompress::Gunzip qw($GunzipError);

use DBD::SQLite::Constants qw(:file_open);
use DBI;
use URI::file;
use JSON::XS;
use Google::ProtocolBuffers::Dynamic;
use Mac::PropertyList qw(parse_plist);

use constant TYPE_UTI => 'com.apple.paper';
# Seconds from the Unix epoch to Core Data's reference date (2001-01-01).
use constant CD_EPOCH => 978307200;

my ($db_path, $list, $check, $pk, $ident, $immutable, $show_text,
    $server_record, $proto_path);
GetOptions(
	'db=s'          => \$db_path,
	'list'          => \$list,
	'check'         => \$check,
	'pk=i'          => \$pk,
	'id=s'          => \$ident,
	'immutable'     => \$immutable,
	'show-text'     => \$show_text,
	'server-record' => \$server_record,
	'proto=s'       => \$proto_path,
) or die "bad options (see the header of $0)\n";
die "--db is required\n" unless defined $db_path;
die "no such file: $db_path\n" unless -f $db_path;
$check = 1 unless $list || defined $pk || defined $ident;

my $root = dirname($db_path);

$proto_path //= $ENV{APPLE_NOTES_PROTO}
	// "$ENV{HOME}/net/github.com/threeplanetssoftware/apple_cloud_notes_parser/proto/notestore.proto";
{
	# Dynamic parses .proto text itself (proto2 and proto3), but resolves
	# the file relative to an include directory, and wants every message
	# it refers to mapped to a Perl class before resolve_references().
	my $dyn = Google::ProtocolBuffers::Dynamic->new(dirname($proto_path));
	$dyn->load_file(basename($proto_path));
	open my $pf, '<', $proto_path or die "$proto_path: $!\n";
	$dyn->map_message($_, "NoteStore::$_")
		for map { /^message\s+(\w+)/ ? $1 : () } <$pf>;   # top-level messages
	$dyn->resolve_references;
}

my $dbh = connect_main($db_path);
my $ent = attachment_entity($dbh);
my @rows = fetch_rows($dbh, $ent);

if ($list) {
	for my $r (@rows) {
		say join "\t", $r->{Z_PK}, $r->{ZIDENTIFIER}, $r->{ZNOTE} // '-',
			fmt_date($r->{ZCREATIONDATE}), fmt_date($r->{ZMODIFICATIONDATE});
	}
} elsif (defined $pk || defined $ident) {
	my ($r) = grep {
		defined $pk ? $_->{Z_PK} == $pk : uc $_->{ZIDENTIFIER} eq uc $ident
	} @rows;
	die "no " . TYPE_UTI . " attachment matches\n" unless $r;
	show_one($r);
} else {
	run_check();
}

# --- connection ------------------------------------------------------------

sub wal_nonempty {
	my ($path) = @_;
	return -s "$path-wal" ? 1 : 0;
}

sub connect_main {
	my ($path) = @_;
	if ($immutable) {
		warn "warning: $path-wal is non-empty and --immutable ignores it\n"
			if wal_nonempty($path);
		return open_immutable($path);
	}
	# Snapshot: copy the trio so the WAL is applied and the original is
	# never written to (opening a WAL database can checkpoint it).
	my $tmp = File::Temp->newdir('apple-notes-probe-XXXXXX', TMPDIR => 1);
	our $snapshot_dir = $tmp;    # keep alive until exit
	for my $suffix ('', '-wal', '-shm') {
		next unless -e "$path$suffix";
		copy("$path$suffix", "$tmp/NoteStore.sqlite$suffix")
			or die "copy $path$suffix: $!\n";
		chmod 0600, "$tmp/NoteStore.sqlite$suffix";
	}
	return DBI->connect("dbi:SQLite:dbname=$tmp/NoteStore.sqlite", '', '', {
		RaiseError => 1, PrintError => 0, AutoCommit => 1,
	});
}

sub open_immutable {
	my ($path) = @_;
	my $uri = URI::file->new(File::Spec->rel2abs($path));
	$uri->query('mode=ro&immutable=1');
	return DBI->connect("dbi:SQLite:uri=$uri", '', '', {
		RaiseError => 1, PrintError => 0, AutoCommit => 1,
		sqlite_open_flags => SQLITE_OPEN_READONLY,
	});
}

# --- queries ---------------------------------------------------------------

# Z_ENT numbers differ between databases, so look ICAttachment up by name.
sub attachment_entity {
	my ($dbh) = @_;
	my ($e) = $dbh->selectrow_array(
		q{SELECT Z_ENT FROM Z_PRIMARYKEY WHERE Z_NAME = 'ICAttachment'});
	die "no ICAttachment in Z_PRIMARYKEY\n" unless defined $e;
	return $e;
}

sub fetch_rows {
	my ($dbh, $ent) = @_;
	my $rows = $dbh->selectall_arrayref(q{
		SELECT Z_PK, ZIDENTIFIER, ZNOTE, ZFALLBACKIMAGEGENERATION,
		       ZMETADATADATA, ZSIZEWIDTH, ZSIZEHEIGHT, ZCREATIONDATE,
		       ZMODIFICATIONDATE, ZMINIMUMSUPPORTEDNOTESVERSION,
		       ZSUMMARY, ZHANDWRITINGSUMMARY, ZSERVERRECORDDATA,
		       ZPAPERBUNDLEGENERATION, ZISPASSWORDPROTECTED
		FROM ZICCLOUDSYNCINGOBJECT
		WHERE Z_ENT = ? AND ZTYPEUTI = ?
		ORDER BY Z_PK
	}, { Slice => {} }, $ent, TYPE_UTI);
	return @$rows;
}

# --- path resolution -------------------------------------------------------

sub account_dirs {
	return grep { -d } glob(quotemeta("$root/Accounts") . '/*');
}

# Returns a hashref of resolved paths (undef where absent).
sub resolve_paths {
	my ($r) = @_;
	my $id  = $r->{ZIDENTIFIER};
	my $gen = $r->{ZFALLBACKIMAGEGENERATION};
	my (@bundles, @images);
	for my $acct (account_dirs()) {
		my $b = "$acct/Paper/Bundles/$id.bundle/Database/data.sqlite3";
		push @bundles, $b if -f $b;
		if (defined $gen && length $gen) {
			push @images, glob(quotemeta("$acct/FallbackImages/$id/$gen") . '/FallbackImage.*');
		}
	}
	return {
		bundles  => \@bundles,
		images   => \@images,
		previews => [ map { glob(quotemeta($_) . "/Previews/$id-*") } account_dirs() ],
	};
}

# --- note body link --------------------------------------------------------

# Returns { found => N (matching runs), runs => total runs, text => note text
# length } or { error => ... }.
sub note_link {
	my ($r) = @_;
	return { error => 'no ZNOTE' } unless defined $r->{ZNOTE};
	my ($blob) = $dbh->selectrow_array(
		q{SELECT ZDATA FROM ZICNOTEDATA WHERE ZNOTE = ?}, undef, $r->{ZNOTE});
	return { error => 'no ZICNOTEDATA row' } unless defined $blob;
	my $raw;
	IO::Uncompress::Gunzip::gunzip(\$blob => \$raw)
		or return { error => "gunzip: $GunzipError" };
	# Messages are plain hashes (absent optional fields are missing keys)
	# and strings come back already decoded to characters.
	my $msg = eval { NoteStore::NoteStoreProto->decode($raw) }
		or return { error => "protobuf: " . ($@ =~ s/\n.*//sr) };
	my $note = $msg->{document}{note} // {};
	my @runs = @{ $note->{attribute_run} // [] };
	my $found = grep {
		my $ai = $_->{attachment_info};
		$ai && ($ai->{attachment_identifier} // '') eq $r->{ZIDENTIFIER}
		    && ($ai->{type_uti} // '') eq TYPE_UTI;
	} @runs;
	return { found => $found, runs => scalar(@runs),
	         text => length($note->{note_text} // '') };
}

# --- metadata --------------------------------------------------------------

sub metadata_json {
	my ($r) = @_;
	return (undef, 'NULL') unless defined $r->{ZMETADATADATA};
	my $j = eval { JSON::XS->new->canonical->decode($r->{ZMETADATADATA}) };
	return (undef, "bad JSON: " . ($@ =~ s/ at .*//sr)) unless $j;
	return ($j, undef);
}

# --- bundle sanity (opens it, counts rows, nothing else) -------------------

sub bundle_summary {
	my ($path) = @_;
	warn "warning: $path-wal is non-empty; bundle counts may be stale\n"
		if wal_nonempty($path);
	my $b = eval { open_immutable($path) } or return "unopenable: $@";
	my ($refs, $assets) = eval {
		($b->selectrow_array('SELECT count(*) FROM Reference'),
		 $b->selectrow_array('SELECT count(*) FROM Assets'))
	};
	return "no Reference/Assets tables" unless defined $refs;
	return "Reference=$refs Assets=$assets";
}

# --- actions ---------------------------------------------------------------

sub show_one {
	my ($r) = @_;
	say "Z_PK:            $r->{Z_PK}";
	say "ZIDENTIFIER:     $r->{ZIDENTIFIER}";
	say "ZNOTE:           ", $r->{ZNOTE} // '(null)';
	say "created:         ", fmt_date($r->{ZCREATIONDATE});
	say "modified:        ", fmt_date($r->{ZMODIFICATIONDATE});
	say "size (w x h):    ", join ' x ', map { $_ // '?' } @$r{qw(ZSIZEWIDTH ZSIZEHEIGHT)};
	say "min notes ver:   ", $r->{ZMINIMUMSUPPORTEDNOTESVERSION} // '(null)';
	say "password prot.:  ", $r->{ZISPASSWORDPROTECTED} // '(null)';
	say "bundle gen col:  ", $r->{ZPAPERBUNDLEGENERATION} // '(null)';
	say "fallback gen:    ", $r->{ZFALLBACKIMAGEGENERATION} // '(null)';
	my ($j, $err) = metadata_json($r);
	say "metadata:        ", $j ? JSON::XS->new->canonical->encode($j) : "($err)";
	my $p = resolve_paths($r);
	say "bundle:          $_  [", bundle_summary($_), "]" for @{ $p->{bundles} };
	say "bundle:          (none)" unless @{ $p->{bundles} };
	say "fallback image:  $_" for @{ $p->{images} };
	say "fallback image:  (none)" unless @{ $p->{images} };
	say "previews:        ", scalar @{ $p->{previews} };
	my $l = note_link($r);
	if ($l->{error}) {
		say "note link:       ERROR $l->{error}";
	} else {
		say "note link:       $l->{found} of $l->{runs} runs reference this id"
			. " (note text $l->{text} chars)";
	}
	if ($show_text) {
		say "summary:         ", $r->{ZSUMMARY} // '(null)';
		say "handwriting:     ", $r->{ZHANDWRITINGSUMMARY} // '(null)';
	}
	if ($server_record) {
		say "server record:";
		say dump_archive($r->{ZSERVERRECORDDATA});
	}
}

sub run_check {
	my (%seen, @anom);
	my ($n_bundle, $n_image, $n_linked) = (0, 0, 0);
	for my $r (@rows) {
		my $id = $r->{ZIDENTIFIER} // '(null)';
		push @anom, "$id: duplicate ZIDENTIFIER" if $seen{uc $id}++;
		my $p = resolve_paths($r);
		if (@{ $p->{bundles} } == 1) { $n_bundle++ }
		else { push @anom, "$id: " . scalar(@{ $p->{bundles} }) . " bundles found" }
		if (@{ $p->{images} }) { $n_image++ }
		else { push @anom, "$id: no fallback image" }
		my $l = note_link($r);
		if ($l->{error})           { push @anom, "$id: note link: $l->{error}" }
		elsif ($l->{found} != 1)   { push @anom, "$id: $l->{found} body runs reference it" }
		else                       { $n_linked++ }
		my (undef, $err) = metadata_json($r);
		push @anom, "$id: metadata $err" if $err;
		push @anom, "$id: ZPAPERBUNDLEGENERATION set"
			if defined $r->{ZPAPERBUNDLEGENERATION};
		push @anom, "$id: password protected" if $r->{ZISPASSWORDPROTECTED};
		for my $b (@{ $p->{bundles} }) {
			my $s = bundle_summary($b);
			push @anom, "$id: bundle $s" if $s !~ /^Reference=\d+/;
		}
	}
	# Reverse direction: bundles on disk with no row.
	my %row_ids = map { uc($_->{ZIDENTIFIER} // '') => 1 } @rows;
	for my $acct (account_dirs()) {
		for my $dir (glob(quotemeta("$acct/Paper/Bundles") . '/*.bundle')) {
			(my $id = basename($dir)) =~ s/\.bundle$//;
			push @anom, "$id: bundle without ICAttachment row (" . basename($acct) . ")"
				unless $row_ids{uc $id};
		}
	}
	say "rows (Z_ENT=$ent, ZTYPEUTI=" . TYPE_UTI . "): ", scalar @rows;
	say "  with exactly one bundle:      $n_bundle";
	say "  with a fallback image:        $n_image";
	say "  referenced once by note body: $n_linked";
	say "anomalies: ", scalar @anom;
	say "  $_" for @anom;
}

# --- ZSERVERRECORDDATA (NSKeyedArchiver CKRecord) --------------------------

# Resolve the archive's $top.root through the $objects table and print it.
sub dump_archive {
	my ($blob) = @_;
	return '  (null)' unless defined $blob;
	my $plist = eval { parse_plist($blob) } or return "  (unparseable: $@)";
	my $objs  = $plist->value->{'$objects'}->value;
	my $top   = $plist->value->{'$top'}->value;
	my $out   = '';
	for my $k (sort keys %$top) {
		$out .= "  $k:\n" . dump_value($objs, $top->{$k}, 2, {});
	}
	return $out;
}

sub dump_value {
	my ($objs, $v, $depth, $seen) = @_;
	my $pad  = '  ' x ($depth + 1);
	my ($type) = ref($v) =~ /::(\w+)$/;
	$type //= ref($v);
	if ($type eq 'uid') {
		my $i = hex $v->value;   # UIDs read back as hex strings
		return "${pad}(cycle -> #$i)\n" if $seen->{$i};
		return "${pad}(depth limit -> #$i)\n" if $depth > 8;
		return dump_value($objs, $objs->[$i], $depth, { %$seen, $i => 1 });
	}
	if ($type eq 'dict') {
		my $d = $v->value;
		my $cls = '';
		if (my $c = $d->{'$class'}) {
			my $co = $objs->[ hex $c->value ]->value;
			$cls = ' <' . $co->{'$classname'}->value . '>';
		}
		my $out = "${pad}{$cls\n";
		for my $k (sort grep { $_ ne '$class' } keys %$d) {
			my $sub = dump_value($objs, $d->{$k}, $depth + 1, $seen);
			$out .= "${pad}  $k =>" . ($sub =~ s/^\s+/ /r);
		}
		return $out . "${pad}}\n";
	}
	if ($type eq 'array') {
		return "${pad}[" . scalar(@{ $v->value }) . " items]\n";
	}
	if ($type eq 'data') {
		my $b = $v->value;
		my $hex = unpack('H*', length($b) > 24 ? substr($b, 0, 24) : $b);
		return "${pad}data(" . length($b) . ") $hex" . (length($b) > 24 ? '...' : '') . "\n";
	}
	return "${pad}$type " . ($v->value // '') . "\n";
}

# --- misc ------------------------------------------------------------------

sub fmt_date {
	my ($t) = @_;
	return '-' unless defined $t;
	my @g = gmtime($t + CD_EPOCH);
	return sprintf '%04d-%02d-%02dT%02d:%02d:%02dZ',
		$g[5] + 1900, $g[4] + 1, @g[3, 2, 1, 0];
}
