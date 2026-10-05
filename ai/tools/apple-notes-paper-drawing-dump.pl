#!/usr/bin/env perl
# Walk the Coherence Reference rows of Apple Notes Paper bundles
# (Accounts/<acct>/Paper/Bundles/<id>.bundle/Database/data.sqlite3) and
# print the DRAWING / INK / STROKES structure, including TOMBSTONES.
#
# This is the com.apple.paper counterpart of apple-notes-drawing-decode.pl.
# The wire schema is apple-notes-paper.proto (generic Coherence messages) +
# apple-notes-paper-drawing.proto (Ink, StrokeData); the object graph this
# script follows is documented in the latter's header and in
# notes/apple/com.apple.paper/skeleton.md. Output is STRUCTURE ONLY: counts,
# ids, field paths - never point data (only its length/stride).
#
# Usage:
#   apple-notes-paper-drawing-dump.pl --bundles DIR
#       One summary line per bundle (DIR = .../Paper/Bundles), then totals.
#   apple-notes-paper-drawing-dump.pl --bundle PATH|ID [--bundles DIR] [--strokes] [--tombstones]
#       Detail for one bundle. --strokes: one line per member of the strokes
#       set; --tombstones: every sequence run, live AND tombstoned (a run
#       whose Subsequence has no sequence_start_id: positions that exist but
#       whose element was deleted), with position ids resolved through the
#       row's Constants.uuids.
#   apple-notes-paper-drawing-dump.pl --bundles DIR --check
#       Self-verification over every row of every bundle: decode/encode
#       round-trips (Dynamic discards unknown fields on decode, so a
#       byte-exact round-trip proves the .proto covers every field present),
#       embedded Ink/StrokeData round-trips and stride arithmetic,
#       reachability from the root row, and graph-grammar exceptions.
#   ... --json FILE|-   Also write everything above (all runs, including
#       tombstones) as JSON; binary values are hex strings.
#
# Bundles are opened read-only with ?immutable=1: valid only because their
# -wal files are empty (checked; a non-empty -wal makes the script warn).

use 5.010;
use strict;
use warnings;
use Getopt::Long;
use File::Basename qw(basename dirname);
use File::Spec;
use FindBin;

use DBD::SQLite::Constants qw(:file_open);
use DBI;
use JSON::XS;
use Google::ProtocolBuffers::Dynamic;

my ($bundles_dir, $bundle_arg, $strokes, $tombstones, $check, $json_path, $proto_dir);
GetOptions(
	'bundles=s'   => \$bundles_dir,
	'bundle=s'    => \$bundle_arg,
	'strokes'     => \$strokes,
	'tombstones'  => \$tombstones,
	'check'       => \$check,
	'json=s'      => \$json_path,
	'proto-dir=s' => \$proto_dir,
) or die "bad options (see the header of $0)\n";
$proto_dir //= $FindBin::Bin;

# --- schema -----------------------------------------------------------------

{
	my $dyn = Google::ProtocolBuffers::Dynamic->new($proto_dir);
	my @msgs;
	for my $f (qw(apple-notes-paper.proto apple-notes-paper-drawing.proto)) {
		$dyn->load_file($f);
		open my $fh, '<', "$proto_dir/$f" or die "$proto_dir/$f: $!\n";
		push @msgs, map { /^message\s+(\w+)/ ? $1 : () } <$fh>;   # top-level only; nested come along
	}
	$dyn->map_message($_, "P::$_") for @msgs;
	$dyn->resolve_references;
}

# Per-point channel sizes, channel index => bytes (apple-notes-drawing-2.proto
# header table); bit i of StrokeData.points_flags selects channel i.
my %CHANNEL_SIZE = (1 => 4, 2 => 4, 3 => 2, 4 => 2, 5 => 2, 6 => 2, 7 => 2, 8 => 2, 9 => 4, 10 => 2);

# --- bundle access ----------------------------------------------------------

sub bundle_paths {
	if (defined $bundle_arg && -d $bundle_arg) { return ($bundle_arg) }
	die "--bundles DIR is required (or --bundle PATH)\n" unless defined $bundles_dir;
	my @all = sort glob(quotemeta($bundles_dir) . '/*.bundle');
	return @all unless defined $bundle_arg;
	my @hit = grep { uc(basename($_)) =~ /^\Q\U$bundle_arg\E/ } @all;
	die "no bundle matches '$bundle_arg'\n" unless @hit;
	die "'$bundle_arg' is ambiguous: @{[map basename($_), @hit]}\n" if @hit > 1;
	return @hit;
}

sub open_bundle {
	my ($path) = @_;
	my $db = "$path/Database/data.sqlite3";
	warn "warning: $db-wal is non-empty and immutable=1 ignores it\n" if -s "$db-wal";
	return DBI->connect("dbi:SQLite:uri=file:$db?immutable=1", '', '', {
		RaiseError => 1, PrintError => 0, sqlite_open_flags => SQLITE_OPEN_READONLY | SQLITE_OPEN_URI });
}

# Loads every Reference row of a bundle: { id (raw bytes) => { id, hdr, body,
# doc, version_col, child_retain } }. `hdr` is the crdt-prefix version or undef.
sub load_rows {
	my ($dbh) = @_;
	my %rows;
	my $sth = $dbh->prepare('SELECT Id, Version, ChildRetainCounts, Data FROM Reference');
	$sth->execute;
	while (my ($id, $ver, $crc, $data) = $sth->fetchrow_array) {
		my $has = substr($data, 0, 4) eq 'crdt';
		my $body = $has ? substr($data, 8) : $data;
		$rows{$id} = {
			id => $id, hdr => $has ? unpack('V', substr($data, 4, 4)) : undef,
			body => $body, doc => P::Document->decode($body),
			version_col => $ver, child_retain => $crc, data_len => length $data,
		};
	}
	return \%rows;
}

# --- grammar helpers --------------------------------------------------------

# A Struct CRDT as an ordered list of [name, CRDT]; names are looked up in
# the *row's own* Constants.strings.
sub struct_fields {
	my ($row) = @_;
	my $c = $row->{doc}{crdt} or return;
	my $st = $c->{composite} or return;
	my $names = $row->{doc}{constants}{strings} // [];
	my @out;
	my @k = @{ $st->{keys} // [] };
	my @v = @{ $st->{values} // [] };
	for my $i (0 .. $#k) { push @out, [ $names->[ $k[$i] ] // "?$k[$i]", $v[$i] ] }
	return @out;
}

# The Value held by a Register CRDT.
sub reg_value { my ($crdt) = @_; return $crdt && $crdt->{register} ? $crdt->{register}{value} : undef }

# Id bytes of the row a Value refers to, looking through Optional.
sub value_ref {
	my ($v) = @_;
	return unless $v;
	$v = $v->{optional}{wrapped} if $v->{optional};
	return $v->{reference} ? $v->{reference}{key_path}{data} : undef;
}

# Doubles stored in Value.data are BIG-endian.
sub be_doubles { my ($bytes) = @_; return unpack('(d>)*', $bytes) }

sub hex8 { return substr(unpack('H*', $_[0]), 0, 8) }

# Runs of alternating (gap, length) over counters from 0, as a list of [from, to].
sub rle_ranges {
	my ($runs) = @_;
	my (@r, $pos);
	$pos = 0;
	my @x = @{ $runs // [] };
	while (@x) {
		my ($gap, $len) = (shift @x, shift(@x) // 0);
		$pos += $gap;
		push @r, [ $pos, $pos + $len - 1 ] if $len > 0;
		$pos += $len;
	}
	return @r;
}

sub rle_size { my $n = 0; $n += $_->[1] - $_->[0] + 1 for rle_ranges($_[0]); return $n }

# Total size of a Version's counter sets, per replica index.
sub version_sets {
	my ($ver) = @_;
	return () unless $ver;
	my @u = @{ $ver->{uuids} // [] };
	my @s = @{ $ver->{index_sets} // [] };
	return map { { replica => $u[$_], size => rle_size($s[$_]{runs}), ranges => [ rle_ranges($s[$_]{runs}) ] } } 0 .. $#u;
}

# --- ink / stroke payloads --------------------------------------------------

sub stride_of {
	my ($flags) = @_;
	my $s = 8;
	for my $bit (sort { $a <=> $b } keys %CHANNEL_SIZE) { $s += $CHANNEL_SIZE{$bit} if ($flags // 0) & (1 << $bit) }
	return $s;
}

sub describe_ink {
	my ($ink_row) = @_;
	my %f = map { $_->[0] => $_->[1] } struct_fields($ink_row);
	my $v = reg_value($f{ink}) or return;
	my $bytes = ($v->{optional} ? $v->{optional}{wrapped} : $v)->{data};
	return unless defined $bytes;
	my $ink = P::Ink->decode($bytes);
	my %o = (
		identifier => $ink->{identifier}, variant => $ink->{variant}, weight => $ink->{weight},
		color => $ink->{color} ? join(',', map { sprintf '%.3g', $ink->{color}{$_} // 0 } qw(r g b a)) : undef,
		has_transform => exists $f{transform} ? 1 : 0, roundtrip => (P::Ink->encode($ink) eq $bytes ? 1 : 0),
	);
	if (my $t = reg_value($f{transform})) { $o{transform} = [ be_doubles($t->{data}) ] if defined $t->{data} }
	return \%o;
}

sub describe_strokedata {
	my ($row) = @_;
	my $sib = $row->{doc}{crdt}{custom_crdt}{siblings}[0] or return;
	my $payload = $sib->{payload} // '';
	my $sd = P::StrokeData->decode($payload);
	my $stride = stride_of($sd->{points_flags});
	return {
		siblings => scalar @{ $row->{doc}{crdt}{custom_crdt}{siblings} },
		points => $sd->{points_count}, flags => $sd->{points_flags}, stride => $stride,
		data_len => length($sd->{points_data} // ''),
		stride_ok => (length($sd->{points_data} // '') == ($sd->{points_count} // 0) * $stride ? 1 : 0),
		created_with_finger => $sd->{created_with_finger}, immutable_points => $sd->{immutable_points_count},
		constant_flags => $sd->{points_constant_flags},
		roundtrip => (P::StrokeData->encode($sd) eq $payload ? 1 : 0),
		sibling_version => [ version_sets($sib->{version}) ],
	};
}

# --- one stroke / group member ---------------------------------------------

# An Element key -> the Register row E -> TaggedValue{tag, wrapped ref} -> stroke
# Struct row S (tag 0) or a CRDT.sequence row (tag 1, a group).
sub describe_member {
	my ($rows, $eid, $depth, $ex) = @_;
	my $e = $rows->{$eid} or do { push @$ex, 'member row missing'; return { kind => 'missing' } };
	my $tv = reg_value($e->{doc}{crdt}) && reg_value($e->{doc}{crdt})->{tagged_value};
	unless ($tv) { push @$ex, 'member is not a Register(TaggedValue)'; return { kind => 'unexpected' } }
	my $tag = $tv->{tag} // 0;
	my $tid = value_ref($tv->{wrapped});
	my $t = $tid && $rows->{$tid} or do { push @$ex, 'tagged target missing'; return { kind => 'missing', tag => $tag } };
	my $crdt = $t->{doc}{crdt};
	if ($crdt->{composite}) {
		my %f = map { $_->[0] => $_->[1] } struct_fields($t);
		my %m = (kind => 'stroke', tag => $tag, row => hex8($tid), fields => [ map { $_->[0] } struct_fields($t) ]);
		# inherited chain -> ink row
		my ($cur, $hops) = (value_ref(reg_value($f{inherited})), 0);
		while ($cur && $rows->{$cur}) {
			my %g = map { $_->[0] => $_->[1] } struct_fields($rows->{$cur});
			if ($g{ink}) { $m{ink} = describe_ink($rows->{$cur}); last }
			if ($g{inherited}) { $cur = value_ref(reg_value($g{inherited})); $hops++; next }
			push @$ex, 'inherited chain leaves the grammar'; last;
		}
		$m{inherited_hops} = $hops;
		# properties: CustomCRValue
		if (my $pv = reg_value($f{properties})) {
			my $cv = $pv->{custom};
			my @tags = @{ $cv->{tag} // [] };
			my @vals = @{ $cv->{value} // [] };
			$m{prop_tags} = [@tags];
			for my $i (0 .. $#tags) {
				my $val = $vals[$i];
				$m{prop_tag0} = $val->{uint} if $tags[$i] == 0;
				if ($tags[$i] == 3 && (my $pid = value_ref($val))) {
					$m{strokedata} = describe_strokedata($rows->{$pid}) if $rows->{$pid};
					$m{strokedata_row} = hex8($pid);
				}
				$m{"prop_$tags[$i]_len"} = length $val->{data} if defined $val->{data};
			}
		} else { push @$ex, 'stroke without properties' }
		return \%m;
	}
	if ($crdt->{sequence}) {
		my @members = map { describe_member($rows, value_ref($_), $depth + 1, $ex) } @{ $crdt->{sequence}{elements} // [] };
		# Keep the group's own sequence too, tombstoned runs included.
		my $runs = describe_sequence($crdt->{sequence}, $t->{doc}{constants}{uuids});
		my ($live, $tomb) = (0, 0);
		for my $run (@$runs) { next if $run->{sentinel}; if ($run->{live}) { $live += $run->{length} } else { $tomb += $run->{length} } }
		push @$ex, "group sequence: live positions ($live) != elements (" . scalar(@members) . ")" if $live != @members;
		return { kind => 'group', tag => $tag, row => hex8($tid), members => \@members, runs => $runs,
			live_positions => $live, tomb_positions => $tomb,
			version => [ version_sets($crdt->{sequence}{version}) ],
			added_by_version => [ version_sets($crdt->{sequence}{added_by_version}) ] };
	}
	push @$ex, 'tagged target is neither stroke struct nor sequence';
	return { kind => 'unexpected', tag => $tag };
}

# --- the strokes OrderedSet -------------------------------------------------

# Position runs of a Sequence, with tombstones kept and flagged.
sub describe_sequence {
	my ($seq, $uuids) = @_;
	my @subs = @{ $seq->{subsequence} // [] };
	my @res;
	my %parent;
	for my $i (0 .. $#subs) { $parent{$_} = $i for @{ $subs[$i]{child} // [] } }
	my $rep_hex = sub { my $i = shift; return defined $uuids && length($uuids) >= 16 * ($i + 1) ? hex8(substr($uuids, 16 * $i, 16)) : '?' };
	for my $i (0 .. $#subs) {
		my $s = $subs[$i];
		my $ts = ($s->{added_by} // [])->[0] // {};
		my $cid = $s->{sequence_start_id};
		push @res, {
			run => $i, replica => $ts->{uuid} // 0, replica_hex => $rep_hex->($ts->{uuid} // 0),
			counter => $ts->{counter} // 0, length => $s->{length} // 0,
			live => defined $cid ? 1 : 0,
			content_id => defined $cid ? { replica => $cid->{uuid} // 0, replica_hex => $rep_hex->($cid->{uuid} // 0), counter => $cid->{counter} // 0 } : undef,
			children => [ @{ $s->{child} // [] } ], parent => $parent{$i},
			sentinel => (($s->{length} // 0) == 0 ? 1 : 0),
		};
	}
	return \@res;
}

sub describe_bundle {
	my ($path) = @_;
	my $dbh = open_bundle($path);
	my $rows = load_rows($dbh);
	my $id = basename($path, '.bundle');
	my %r = (id => $id, rows => scalar(keys %$rows), exceptions => []);
	my $ex = $r{exceptions};
	$r{hdr_versions} = {};
	$r{hdr_versions}{ $_->{hdr} // 'none' }++ for values %$rows;

	# root row (Id 0xFF) -> "default"
	my $root = $rows->{"\xff"} or do { push @$ex, 'no root row'; return \%r };
	my $dkey = $root->{doc}{reference} ? $root->{doc}{reference}{key_path}{data} : undef;
	my $def = $dkey && $rows->{$dkey} or do { push @$ex, 'root does not point at a row'; return \%r };
	$r{root_target} = $dkey =~ /^\x01(.*)$/s ? "name:$1" : unpack('H*', $dkey);
	my %top = map { $_->[0] => $_->[1] } struct_fields($def);
	$r{top_fields} = [ map { $_->[0] } struct_fields($def) ];
	for my $k (qw(bounds frame)) {
		my $v = reg_value($top{$k});
		$r{$k} = [ be_doubles($v->{data}) ] if $v && defined $v->{data};
	}
	my $drow = $rows->{ value_ref(reg_value($top{drawing})) // '' } or do { push @$ex, 'default has no drawing row'; return \%r };
	my %dr = map { $_->[0] => $_->[1] } struct_fields($drow);
	$r{drawing_fields} = [ map { $_->[0] } struct_fields($drow) ];
	if (my $cb = reg_value($dr{canvasBounds})) { $r{canvas_bounds} = [ be_doubles($cb->{data}) ] if defined $cb->{data} }
	my $uuids = $drow->{doc}{constants}{uuids} // '';
	$r{replicas} = length($uuids) / 16;

	my $os = $dr{strokes} && $dr{strokes}{ordered_set} or do { push @$ex, 'drawing has no strokes ordered set'; return \%r };
	my @elements = @{ $os->{set}{insertions} // [] };
	my @members;
	for my $el (@elements) {
		my $m = describe_member($rows, value_ref($el->{key}), 0, $ex);
		$m->{added_by} = [ version_sets($el->{added_by}) ];
		push @members, $m;
	}
	$r{members} = \@members;
	$r{set_uuid} = $os->{set}{uuid};
	$r{set_version} = [ version_sets($os->{set}{version}) ];
	$r{timestamps} = scalar @{ $os->{timestamps} // [] };
	my $runs = describe_sequence($os->{sequence}, $uuids);
	$r{runs} = $runs;
	$r{sequence_version} = [ version_sets($os->{sequence}{version}) ];
	$r{sequence_added_by_version} = [ version_sets($os->{sequence}{added_by_version}) ];

	# derived counts
	$r{n_members} = scalar @members;
	$r{n_strokes} = scalar grep { $_->{kind} eq 'stroke' } @members;
	$r{n_groups}  = scalar grep { $_->{kind} eq 'group' } @members;
	$r{positions} = 0; $r{live_positions} = 0; $r{tomb_positions} = 0; $r{tomb_runs} = 0; $r{live_runs} = 0;
	for my $run (@$runs) {
		next if $run->{sentinel};
		$r{positions} += $run->{length};
		if ($run->{live}) { $r{live_positions} += $run->{length}; $r{live_runs}++ }
		else              { $r{tomb_positions} += $run->{length}; $r{tomb_runs}++ }
	}
	my $vsz = 0; $vsz += $_->{size} for @{ $r{sequence_version} };
	$r{sequence_version_size} = $vsz;
	$r{group_tomb_positions} = 0;
	{ my @g = grep { $_->{kind} eq 'group' } @members; while (my $g = shift @g) { $r{group_tomb_positions} += $g->{tomb_positions} // 0; push @g, grep { $_->{kind} eq 'group' } @{ $g->{members} } } }
	$r{points_total} = 0;
	my @flat = @members;
	while (my $m = shift @flat) {
		push @flat, @{ $m->{members} } if $m->{members};
		$r{points_total} += $m->{strokedata}{points} // 0;
	}
	$r{inks} = {};
	@flat = @members;
	while (my $m = shift @flat) {
		push @flat, @{ $m->{members} } if $m->{members};
		$r{inks}{ $m->{ink}{identifier} // '-' }++ if $m->{kind} eq 'stroke';
	}
	# consistency checks
	push @$ex, "live positions ($r{live_positions}) != set members ($r{n_members})" if $r{live_positions} != $r{n_members};
	push @$ex, "positions ($r{positions}) != Sequence.version size ($vsz)" if $r{positions} != $vsz;
	push @$ex, 'timestamps != members' if $r{timestamps} != $r{n_members};
	$r{rows_map} = $rows;   # for --check; stripped before JSON
	return \%r;
}

# --- output -----------------------------------------------------------------

sub fmt_rect { return @{ $_[0] // [] } ? '[' . join(',', map { sprintf '%g', $_ } @{ $_[0] }) . ']' : '-' }

sub print_summary_row {
	my ($r) = @_;
	printf "%-8s rows=%-6d members=%-5d strokes=%-5d groups=%-3d positions=%-5d tomb=%-5d (%d runs) points=%-7d replicas=%-3d%s\n",
		substr($r->{id}, 0, 8), $r->{rows}, $r->{n_members} // 0, $r->{n_strokes} // 0, $r->{n_groups} // 0,
		$r->{positions} // 0, $r->{tomb_positions} // 0, $r->{tomb_runs} // 0, $r->{points_total} // 0,
		$r->{replicas} // 0, @{ $r->{exceptions} } ? "  EXCEPTIONS=" . scalar(@{ $r->{exceptions} }) : '';
}

sub print_detail {
	my ($r) = @_;
	say "bundle $r->{id}";
	say "  rows: $r->{rows}   crdt-header versions: ", join(', ', map { "$_ x$r->{hdr_versions}{$_}" } sort keys %{ $r->{hdr_versions} });
	say "  root -> $r->{root_target}   top struct: (@{ $r->{top_fields} // [] })   drawing struct: (@{ $r->{drawing_fields} // [] })";
	say "  bounds=", fmt_rect($r->{bounds}), "  frame=", fmt_rect($r->{frame}), "  canvasBounds=", fmt_rect($r->{canvas_bounds});
	say "  replicas in drawing row's Constants.uuids: ", $r->{replicas} // 0;
	say "  strokes set: $r->{n_members} members ($r->{n_strokes} strokes, $r->{n_groups} groups); Set.uuid=", $r->{set_uuid} // '-',
		"; Set.version ", join(' ', map { "r$_->{replica}:$_->{size}" } @{ $r->{set_version} // [] });
	say "  sequence: $r->{live_runs} live runs ($r->{live_positions} positions), $r->{tomb_runs} TOMBSTONE runs ($r->{tomb_positions} positions); ",
		"Sequence.version covers ", $r->{sequence_version_size}, " positions";
	say "  inks: ", join(', ', map { "$_ x$r->{inks}{$_}" } sort keys %{ $r->{inks} });
	say "  tombstoned positions inside group sequences: $r->{group_tomb_positions}" if $r->{n_groups};
	say "  stroke points total: $r->{points_total}";
	say "  EXCEPTION: $_" for @{ $r->{exceptions} };
	if ($strokes) {
		say "  members (set order):";
		my $n = 0;
		for my $m (@{ $r->{members} }) {
			my $sd = $m->{strokedata};
			if ($m->{kind} eq 'stroke') {
				printf "    #%-5d stroke  ink=%-24s pts=%-5s flags=%-4s stride=%-3s finger=%s imm=%s tags=%s%s\n", $n++,
					$m->{ink}{identifier} // '-', $sd->{points} // '-', $sd->{flags} // '-', $sd->{stride} // '-',
					$sd->{created_with_finger} // '-', $sd->{immutable_points} // '-', join(',', @{ $m->{prop_tags} // [] }),
					$m->{ink}{has_transform} ? ' +transform' : '';
			} else {
				printf "    #%-5d %s  members=%d\n", $n++, $m->{kind}, scalar @{ $m->{members} // [] };
			}
		}
	}
	if ($tombstones) {
		say "  sequence runs (live and TOMBSTONE; position id = replica:counter, resolved replica = first 8 hex of Constants.uuids[replica]):";
		for my $run (@{ $r->{runs} }) {
			printf "    run %-5d %-9s pos=r%d(%s):%d..%d len=%-4d parent=%s children=[%s]%s\n", $run->{run},
				$run->{sentinel} ? 'SENTINEL' : $run->{live} ? 'live' : 'TOMBSTONE',
				$run->{replica}, $run->{replica_hex}, $run->{counter}, $run->{counter} + ($run->{length} || 1) - 1, $run->{length},
				$run->{parent} // '-', join(',', @{ $run->{children} }),
				$run->{content_id} ? sprintf(' content=r%d(%s):%d', @{ $run->{content_id} }{qw(replica replica_hex counter)}) : '';
		}
	}
}

# --- check ------------------------------------------------------------------

sub reachable_ids {
	my ($rows) = @_;
	my %seen;
	my @stack = ("\xff");
	while (defined(my $x = pop @stack)) {
		next if $seen{$x}++ || !$rows->{$x};
		my $body = $rows->{$x}{body};
		# every KeyPath.data in the row, via the RetainCounts column (the
		# row's own list of rows it refers to) plus the root's reference.
		my $rc = $rows->{$x}{child_retain};
		if (defined $rc && length $rc) {
			my $m = P::RetainCounts->decode($rc);
			push @stack, map { $_->{data} } @{ $m->{reference_keys} // [] };
		}
		if ($rows->{$x}{doc}{reference}) { push @stack, $rows->{$x}{doc}{reference}{key_path}{data} }
	}
	return \%seen;
}

sub run_check {
	my (@paths) = @_;
	my %t;
	for my $p (@paths) {
		my $r = describe_bundle($p);
		my $rows = delete $r->{rows_map};
		$t{bundles}++;
		$t{rows} += scalar keys %$rows;
		for my $row (values %$rows) {
			$t{doc_roundtrip}++ if P::Document->encode($row->{doc}) eq $row->{body};
			if (length($row->{version_col} // '')) { $t{vercol}++; $t{vercol_ok}++ if P::Document->encode(P::Document->decode($row->{version_col})) eq $row->{version_col} }
			if (length($row->{child_retain} // '')) { $t{retain}++; $t{retain_ok}++ if P::RetainCounts->encode(P::RetainCounts->decode($row->{child_retain})) eq $row->{child_retain} }
			$t{hdr_not6}++ if defined $row->{hdr} && $row->{hdr} != 6;
		}
		my $seen = reachable_ids($rows);
		my $unreach = grep { !$seen->{$_} && $_ ne "\xff" } keys %$rows;
		$t{unreachable_via_retaincounts} += $unreach;
		my @flat = @{ $r->{members} // [] };
		while (my $m = shift @flat) {
			push @flat, @{ $m->{members} } if $m->{members};
			next unless $m->{kind} eq 'stroke';
			$t{strokes}++;
			my $sd = $m->{strokedata};
			if ($sd) {
				$t{strokedata}++; $t{sd_roundtrip} += $sd->{roundtrip}; $t{stride_ok} += $sd->{stride_ok};
				$t{sd_siblings_not1}++ if $sd->{siblings} != 1;
			} else { $t{stroke_without_strokedata}++ }
			if ($m->{ink}) { $t{ink}++; $t{ink_roundtrip} += $m->{ink}{roundtrip} } else { $t{stroke_without_ink}++ }
		}
		$t{members} += $r->{n_members} // 0; $t{groups} += $r->{n_groups} // 0;
		$t{positions} += $r->{positions} // 0; $t{live_positions} += $r->{live_positions} // 0; $t{tomb_positions} += $r->{tomb_positions} // 0; $t{tomb_runs} += $r->{tomb_runs} // 0;
		$t{bundles_with_tombstones}++ if ($r->{tomb_runs} // 0) > 0;
		for my $e (@{ $r->{exceptions} }) { $t{exceptions}++; say "EXCEPTION [", substr($r->{id}, 0, 8), "] $e" }
	}
	say "bundles:                       $t{bundles}";
	say "rows:                          $t{rows}";
	say "Document decode/encode exact:  $t{doc_roundtrip} / $t{rows}";
	say "Version column round-trips:    ", $t{vercol_ok} // 0, " / ", $t{vercol} // 0;
	say "ChildRetainCounts round-trips: ", $t{retain_ok} // 0, " / ", $t{retain} // 0;
	say "crdt header version != 6:      ", $t{hdr_not6} // 0;
	say "unreachable from root (via ChildRetainCounts + root ref): ", $t{unreachable_via_retaincounts} // 0;
	say "strokes:                       $t{strokes}  (with StrokeData: ", $t{strokedata} // 0, ", with Ink: ", $t{ink} // 0, ")";
	say "StrokeData round-trip exact:   ", $t{sd_roundtrip} // 0, " / ", $t{strokedata} // 0;
	say "StrokeData stride exact:       ", $t{stride_ok} // 0, " / ", $t{strokedata} // 0;
	say "Ink round-trip exact:          ", $t{ink_roundtrip} // 0, " / ", $t{ink} // 0;
	say "StrokeData siblings != 1:      ", $t{sd_siblings_not1} // 0;
	say "set members: $t{members} (groups: ", $t{groups} // 0, ")  sequence positions: $t{positions} = live $t{live_positions} + tombstoned $t{tomb_positions} in $t{tomb_runs} tombstone runs; bundles with tombstones: ", $t{bundles_with_tombstones} // 0;
	say "grammar/consistency exceptions: ", $t{exceptions} // 0;
	return;
}

# --- main -------------------------------------------------------------------

my @paths = bundle_paths();

if ($check) { run_check(@paths); exit 0 }

my @reports;
for my $p (@paths) {
	my $r = describe_bundle($p);
	delete $r->{rows_map};
	push @reports, $r;
}

if (defined $bundle_arg) {
	print_detail($_) for @reports;
} else {
	print_summary_row($_) for @reports;
	my %t;
	for my $r (@reports) { $t{$_} += $r->{$_} // 0 for qw(rows n_members n_strokes n_groups positions live_positions tomb_positions tomb_runs points_total) }
	say '-' x 100;
	say "TOTAL   bundles=", scalar(@reports), " rows=$t{rows} members=$t{n_members} strokes=$t{n_strokes} groups=$t{n_groups} positions=$t{positions} (live $t{live_positions}, tombstoned $t{tomb_positions} in $t{tomb_runs} runs) points=$t{points_total}";
}

# JSON needs real numbers (not strings that were once interpolated) and no
# bare inf/nan; hex ids, names and identifiers must stay strings.
my %JSON_STRING_KEYS = map { $_ => 1 } qw(id replica_hex root_target row strokedata_row identifier variant color kind);
sub jsonify {
	my ($x, $key) = @_;
	if (ref $x eq 'HASH')  { return { map { $_ => jsonify($x->{$_}, $_) } keys %$x } }
	if (ref $x eq 'ARRAY') { return [ map { jsonify($_, $key) } @$x ] }
	return $x unless defined $x && !ref $x;
	return "$x" if $JSON_STRING_KEYS{ $key // '' };
	if ($x =~ /^-?(?:inf|infinity|nan)$/i) { return lc "$x" }
	return $x =~ /^-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?$/ ? $x + 0 : "$x";
}

if (defined $json_path) {
	my $json = JSON::XS->new->canonical->pretty->allow_nonref;
	my $out = $json->encode(jsonify([ map { my %c = %$_; \%c } @reports ]));
	if ($json_path eq '-') { print $out } else { open my $fh, '>', $json_path or die "$json_path: $!\n"; print $fh $out }
}
