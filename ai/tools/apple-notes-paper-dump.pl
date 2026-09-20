#!/usr/bin/env perl
# Dump rows out of a newer-format Apple Notes "Paper" bundle's
# Database/data.sqlite3 (Reference/Assets tables), for exploring the
# CRDT+protobuf wire format documented in
# notes/apple/com.apple.paper/envelope.md.
#
# This is a raw-inspection tool, not a decoder: it does not know the
# .proto schema (none has been written yet for this format - see the note
# above for what's confirmed vs. not). It decodes the Id encoding and
# strips the 8-byte "crdt"+u32-version prefix found on most Data blobs
# (again, see envelope.md), and otherwise just dumps bytes for you to feed
# to `protoc --decode_raw` or similar.
#
# Usage:
#   apple-notes-paper-dump.pl --db path/to/data.sqlite3 --list
#       List every Reference row as "<id-desc>  <length>  <data-prefix>".
#
#   apple-notes-paper-dump.pl --db path/to/data.sqlite3 --id <hex> --out FILE
#       Write one row's Data to FILE, after stripping the crdt/version
#       prefix if present (use --raw to skip stripping). <hex> is the exact
#       Id bytes as printed by --list.
#
# Add --immutable for a fixed archive (such as a git-annex object) where
# the database will not change while being read - this opens read-only
# without attempting to create -wal/-shm siblings, which matters because
# sqlite3 has been observed to fail to open annex objects in place
# otherwise (SQLITE_CANTOPEN) even though the file itself is intact; the
# workaround used while writing envelope.md was copying the file to a
# writable scratch directory first, but --immutable should avoid needing
# that.

use 5.010;
use strict;
use warnings;
use Getopt::Long;

use DBD::SQLite::Constants qw(:file_open);
use DBI;

my ($db_path, $list, $out_path, $id_hex, $raw, $immutable);
GetOptions(
    'db=s'        => \$db_path,
    'list'        => \$list,
    'id=s'        => \$id_hex,
    'out=s'       => \$out_path,
    'raw'         => \$raw,
    'immutable'   => \$immutable,
) or die "bad options\n";

die "usage: --db FILE (--list | --id HEX --out FILE)\n" unless $db_path;

my %connect_attrs = (RaiseError => 1, sqlite_open_flags => SQLITE_OPEN_READONLY);
if ($immutable) {
    # sqlite3 "immutable=1" URI parameter: promise the file won't change,
    # so it can be opened without WAL/journal machinery at all.
    $db_path = "file:$db_path?immutable=1";
    $connect_attrs{sqlite_open_flags} |= SQLITE_OPEN_URI if defined &SQLITE_OPEN_URI;
}

my $dbh = DBI->connect("dbi:SQLite:dbname=$db_path", '', '', \%connect_attrs);

# Id encoding (see envelope.md "Id encoding"): first byte 0x01 = name
# (rest is ASCII), 0x02 = uuid (rest is 16 raw bytes), 0xFF = bare root
# marker (no further bytes). Anything else is unrecognized - show as hex.
sub describe_id {
    my ($id) = @_;
    my @b = unpack 'C*', $id;
    return '<empty>' unless @b;
    my $tag = $b[0];
    if ($tag == 0x01) {
        return 'name:' . substr($id, 1);
    } elsif ($tag == 0x02 && length($id) == 17) {
        my @u = unpack 'C*', substr($id, 1);
        return sprintf 'uuid:%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X', @u;
    } elsif ($tag == 0xFF && length($id) == 1) {
        return 'root';
    }
    return 'raw:' . unpack('H*', $id);
}

# Strip the "crdt" + 4-byte little-endian version prefix documented in
# envelope.md, if present. Every sample seen there had version == 6; this
# does not check that, just the magic, so a different version doesn't
# silently misparse.
sub strip_crdt_header {
    my ($data) = @_;
    return $data unless substr($data, 0, 4) eq 'crdt';
    return substr($data, 8);
}

if ($list) {
    my $sth = $dbh->prepare('SELECT Id, Data FROM "Reference" ORDER BY Id');
    $sth->execute;
    while (my ($id, $data) = $sth->fetchrow_array) {
        my $prefix = unpack('H*', substr($data, 0, 12));
        printf "%-45s %8d  %s\n", describe_id($id), length($data), $prefix;
    }
    exit 0;
}

if (defined $id_hex) {
    die "--out is required with --id\n" unless defined $out_path;
    my $id = pack('H*', $id_hex);
    my $sth = $dbh->prepare('SELECT Data FROM "Reference" WHERE Id = ?');
    $sth->bind_param(1, $id, DBI::SQL_BLOB);
    $sth->execute;
    my ($data) = $sth->fetchrow_array;
    die "no such Id: $id_hex\n" unless defined $data;
    $data = strip_crdt_header($data) unless $raw;
    open my $fh, '>:raw', $out_path or die "$out_path: $!\n";
    print $fh $data;
    close $fh;
    exit 0;
}

die "nothing to do - pass --list or --id/--out\n";
