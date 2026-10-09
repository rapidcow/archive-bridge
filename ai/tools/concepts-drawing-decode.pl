#!/usr/bin/env perl
# Decode a Concepts app (TopHatch, Inc.) ".concept" drawing file (the iOS/
# iPadOS/macOS format - a zip archive containing bplist00-format Strokes.plist
# etc.) into a neutral s-expression or InkML document, in the same spirit as
# ~/my/project/apple-notes/ai/tools/apple-notes-drawing-decode.pl (its header
# comment is worth reading first if you haven't - this script mirrors its
# separation of "decode the container" from "walk the decoded message tree",
# and its discipline about labeling anything not fully confirmed).
#
# Format background, and what's confirmed vs. still-open, is in this repo's
# ai/notes/study.md - read that first for anything not explained inline here.
# In short: a .concept file is a zip containing Strokes.plist (bplist00,
# NSKeyedArchiver-shaped: $version/$archiver/$top/$objects, with UID-typed
# cross-references into $objects), whose object graph is, top to bottom:
#   root (StrokeManager) -> rootGroupLayers (NSMutableArray) -> one
#   DrawingItemGroup per *layer* (has a real .name, e.g. "Pen"/"Marker") ->
#   groupItems (NSMutableArray/Set) -> Stroke / ImageItem / TiledPDFPage /
#   TextItem / nested DrawingItemGroup objects.
# This script only handles that iOS/macOS ".concept" format. The Android/
# Windows ".concepts" format (MessagePack tree.pack, no confirmed layer
# structure at all yet - see study.md §7/§11) is not implemented here.
#
# Only the top-level container (Strokes.plist) is read - Drawing.plist
# (document-level transform/canvas state), Resources.plist (image UUID ->
# file-extension map) and the actual ImportedImages/*/Thumb.jpg bytes are
# not touched, since the ask this script answers is "stroke data + layer
# structure", not "render a picture". See study.md §6 if image/PDF
# resource-file resolution is needed later.
#
# --- A real bug in the installed Mac::PropertyList (confirmed, worked
#     around below, NOT patched in the installed module itself) ---
#
# Mac::PropertyList::ReadBinary's private array/dict binary-plist readers
# decide how to unpack each element's object-reference bytes with:
#     $self->_object_ref_size == 1 ? "C*" : "n*"
# i.e. only 1-byte refs are handled correctly; every other ref size
# (2 needs "n*" and happens to work by coincidence of matching that
# fallback branch, but the format also allows ref_size 3 or 4, neither of
# which "n*" reads correctly) silently misparses. A bplist needs
# ref_size==4 once it has more than 65535 low-level objects total - not a
# contrived edge case for Concepts: a single stroke's plist sub-objects
# (brushProperties/brushColor/diTimeStamp/keyPoints/KeyPoint*N/the point
# data blob/...) easily add up to 10-20 objects each, so any sketchbook
# with a few thousand strokes crosses that threshold. Confirmed against a
# real file in this repo's own sample corpus
# (~/my/art/export/iPad/New\ Folder/New\ Drawing\ 2\ Copy.concept - its own
# trailer bytes decode to ref_size=4, num_objects=92039) - with the
# upstream bug present, parsing that file doesn't just give wrong answers,
# it manifests as what looks like a self-referencing object (bplist index 0
# appearing to reference itself) and recurses until the process is killed;
# this is unpack() misreading 4-byte big-endian refs as 2-byte ones, not a
# real cycle in the data.
#
# Can't be patched from outside by overriding a small piece: the buggy
# closures live inside a `my $type_readers = {...}` lexical that's private
# to a BEGIN block in ReadBinary.pm, unreachable once compiled. So this
# script replaces the two affected package subs, _read_object and
# _read_object_at_offset, wholesale (via typeglob assignment, guarded by
# `no warnings 'redefine'`) with a corrected reimplementation below that:
# (a) fixes the ref-size handling for all of 1/2/4 bytes (3 is treated the
#     same as 4, since a spec-legal-but-unseen-in-practice 3-byte ref count
#     doesn't fit any of Perl's native unpack widths - if this ever
#     surfaces for real, it'll throw rather than silently misparse), and
# (b) adds memoization by low-level object index, since the original does
#     none at all - correctness-neutral for the ref-size bug specifically,
#     but avoids needlessly re-parsing e.g. the one $class dict every
#     Stroke object in a file references, from scratch, once per stroke.
# Everything else (trailer parsing, the offset table, the plain scalar
# type readers int/real/date/data/string/uid) is unaffected by the bug and
# is NOT reimplemented - this script still goes through
# Mac::PropertyList::ReadBinary->new / Mac::PropertyList::parse_plist for
# all of that, and reuses its Mac::PropertyList::{dict,array,string,...}
# result classes so the rest of this script's code (and anyone comparing
# output against a small file where the bug never triggers) sees identical
# shapes either way.

use 5.010;
use strict;
use warnings;
use Getopt::Long;
use File::Basename qw(basename);
use POSIX qw(SEEK_SET);
use Archive::Zip;
use Mac::PropertyList;
use Mac::PropertyList::ReadBinary;
use Math::BigInt;
use Encode qw(decode);

# --- Mac::PropertyList::ReadBinary bugfix (see header comment) -------------

{
	my %SINGLETONS = (
		0 => undef,
		8 => Mac::PropertyList::false->new,
		9 => Mac::PropertyList::true->new,
	);

	# Unpack $count object-reference indices, $ref_size bytes each,
	# big-endian - the one thing the installed module gets wrong for
	# ref_size other than 1 (see header comment).
	sub _fixed_unpack_refs {
		my ($buf, $ref_size) = @_;
		my $format = { 1 => "C*", 2 => "n*", 4 => "N*" }->{$ref_size};
		die "concepts-drawing-decode.pl: unsupported object-ref size $ref_size (only 1/2/4 are implemented - see this script's header comment)\n"
			unless $format;
		return unpack($format, $buf);
	}

	# Reimplementation of Mac::PropertyList::ReadBinary's private
	# _read_object, fixing the array/dict ref-size bug. Mirrors the
	# original's type dispatch exactly for every other type (int/real/
	# date/data/string/ustring/uid) - see ReadBinary.pm itself for the
	# format this was checked against.
	sub _fixed_read_object {
		my ($self) = @_;
		my $fh = $self->_fh;
		my $marker;
		read($fh, $marker, 1) == 1 or die "concepts-drawing-decode.pl: read failed for bplist type byte\n";
		my $byte = unpack("C", $marker);
		my $type = ($byte & 0xF0) >> 4;
		my $len  = $byte & 0x0F;
		$len = _fixed_read_object($self)->value if $type != 0 && $len == 0x0F;

		if ($type == 0x0) {
			return $SINGLETONS{$len} if exists $SINGLETONS{$len};
			die sprintf("concepts-drawing-decode.pl: unknown bplist singleton byte %02X\n", $len);
		}
		elsif ($type == 0x1) { # int
			my $n = 1 << $len;
			die "concepts-drawing-decode.pl: integer with $n bytes not supported\n" if $n > 8;
			my $buf; read($fh, $buf, $n);
			my @formats = qw(C n N NN);
			my @v = unpack($formats[$len], $buf);
			if ($len == 3) { # 64-bit: two 32-bit halves, sign-extend
				my ($hi, $lo) = @v;
				my $b = Math::BigInt->new($hi)->blsft(32)->bior($lo);
				$b -= Math::BigInt->new(2)->bpow(64)
					if $b->bcmp(Math::BigInt->new(2)->bpow(63)) >= 0;
				@v = ($b);
			}
			return Mac::PropertyList::integer->new($v[0]);
		}
		elsif ($type == 0x2) { # real
			my $n = 1 << $len;
			my $buf; read($fh, $buf, $n);
			my $v = $n == 4 ? unpack("f>", $buf) : unpack("d>", $buf);
			return Mac::PropertyList::real->new($v);
		}
		elsif ($type == 0x3) { # date: 8-byte float, seconds since 2001-01-01
			my $buf; read($fh, $buf, 8);
			my ($secs) = unpack("d>", $buf);
			my $iso = POSIX::strftime("%Y-%m-%dT%H:%M:%SZ", gmtime(978307200 + $secs));
			return Mac::PropertyList::date->new($iso);
		}
		elsif ($type == 0x4) { # data
			my $buf; read($fh, $buf, $len);
			return Mac::PropertyList::data->new($buf);
		}
		elsif ($type == 0x5) { # ASCII string
			my $buf; read($fh, $buf, $len);
			return Mac::PropertyList::string->new($buf);
		}
		elsif ($type == 0x6) { # UTF-16BE string
			my $buf; read($fh, $buf, 2 * $len);
			return Mac::PropertyList::ustring->new(decode("UTF-16BE", $buf));
		}
		elsif ($type == 0x8) { # UID
			my $buf; read($fh, $buf, $len + 1);
			return Mac::PropertyList::uid->new(unpack("H*", $buf));
		}
		elsif ($type == 0xA || $type == 0xC) { # array or set
			my $buf; read($fh, $buf, $len * $self->_object_ref_size);
			my @refs = _fixed_unpack_refs($buf, $self->_object_ref_size);
			my @arr = map { _fixed_read_object_at_offset($self, $_) } @refs;
			return Mac::PropertyList::array->new(\@arr);
		}
		elsif ($type == 0xD) { # dict: keys block, then values block
			my $kbuf; read($fh, $kbuf, $len * $self->_object_ref_size);
			my @key_refs = _fixed_unpack_refs($kbuf, $self->_object_ref_size);
			my $vbuf; read($fh, $vbuf, $len * $self->_object_ref_size);
			my @val_refs = _fixed_unpack_refs($vbuf, $self->_object_ref_size);
			my %dict;
			for my $i (0 .. $len - 1) {
				my $k = _fixed_read_object_at_offset($self, $key_refs[$i])->value;
				my $v = _fixed_read_object_at_offset($self, $val_refs[$i]);
				$dict{$k} = $v;
			}
			return Mac::PropertyList::dict->new(\%dict);
		}
		else {
			die sprintf("concepts-drawing-decode.pl: unknown bplist object type nibble %X\n", $type);
		}
	}

	sub _fixed_read_object_at_offset {
		my ($self, $idx) = @_;
		return $self->{_fixed_cache}{$idx} if exists $self->{_fixed_cache}{$idx};
		seek($self->_fh, ${ $self->_offsets }[$idx], SEEK_SET);
		return $self->{_fixed_cache}{$idx} = _fixed_read_object($self);
	}

	no warnings 'redefine';
	*Mac::PropertyList::ReadBinary::_read_object           = \&_fixed_read_object;
	*Mac::PropertyList::ReadBinary::_read_object_at_offset = \&_fixed_read_object_at_offset;
}

# --- per-point auxiliary channel hypothesis (see study.md §12) -------------
#
# Confirmed: every point record is 16 bytes, x/y float32 LE first (8
# bytes), regardless of the containsPressure/altitudeAngleAvailable/
# azimuthAngleAvailable flags - checked against real files where all three
# are false (see study.md's addendum to §4a). The trailing 8 bytes'
# meaning is NOT confirmed the way apple-notes-drawing-decode.pl's point
# channels are (that one's backed by live disassembly; this one is not) -
# so by default this script only reports those 8 bytes as raw hex/uint16.
#
# When all three flags are true, study.md §12 found that reusing Apple's
# own (disassembly-confirmed, for their PencilKit format) fixed-point
# formulas at these exact byte offsets produces physically-plausible pencil
# force/altitude/azimuth values for a real Concepts stroke - a well-
# motivated hypothesis, still NOT proof (different vendor; could
# coincidentally converge, or use a different scale/bias). Gated behind
# --aux-hypothesis so the default output stays fully honest about what's
# actually confirmed; pass that flag to additionally print this guess.
use constant AUX_FORCE_DIVISOR    => 1000.0;
use constant AUX_AZIMUTH_DIVISOR  => 65535 / (2 * 3.14159265358979323846);
use constant AUX_AZIMUTH_BIAS     => 3.14159265358979323846;
use constant AUX_ALTITUDE_DIVISOR => 65535 / (3.14159265358979323846 / 2);
use constant AUX_ALTITUDE_BIAS    => 3.14159265358979323846 / 2;

# --- CLI --------------------------------------------------------------------

my $prog = basename($0);
my $usage = <<EOM;
usage: $prog FILE.concept [--sexp|--inkml] [--out FILE] [--aux-hypothesis]
EOM

my ($want_sexp, $want_inkml, $out, $aux_hypothesis);
GetOptions(
	"sexp"            => \$want_sexp,
	"inkml"           => \$want_inkml,
	"out=s"           => \$out,
	"aux-hypothesis"  => \$aux_hypothesis,
) or die $usage;

my $file = shift @ARGV;
die $usage unless defined $file && length $file;
$want_sexp = 1 unless $want_sexp || $want_inkml;

my $drawing = decode_concept_file($file);

my $fh = \*STDOUT;
if ($out) {
	open($fh, '>', $out) or die "can't write $out: $!\n";
}

print $fh render_sexp($drawing)  if $want_sexp;
print $fh render_inkml($drawing) if $want_inkml;

# --- container / plist plumbing ---------------------------------------------

# Archive::Zip's memberNamed() is an exact-string match, but not every
# real .concept zip stores names the same way: some (older exports, seen
# on a real file dated 2020) store entries with a leading "/" ("/Strokes.
# plist" instead of "Strokes.plist"), which memberNamed() then can't find
# at all - confirmed against a real sample in this repo's own corpus,
# where that member being invisible made this script wrongly report "no
# Strokes.plist" for a file that in fact had one. Match by exact name, by
# name with a leading "/" stripped, or by "/"-suffix (a subdirectory
# prefix, seen in some other tools' code even if not in our own samples)
# rather than trust one fixed spelling.
sub find_member {
	my ($zip, $name) = @_;
	for my $m ($zip->members) {
		my $fn = $m->fileName;
		$fn =~ s{^/}{};
		return $m if $fn eq $name || $fn =~ m{/\Q$name\E$};
	}
	return undef;
}

sub decode_concept_file {
	my ($file) = @_;

	my $zip = Archive::Zip->new();
	Archive::Zip::AZ_OK() == $zip->read($file)
		or die "$prog: can't read '$file' as a zip archive\n";

	my $strokes_member = find_member($zip, "Strokes.plist");
	unless ($strokes_member) {
		if (find_member($zip, "tree.pack")) {
			die "$prog: '$file' looks like an Android/Windows .concepts file (has tree.pack, not Strokes.plist) - not implemented yet, see ai/notes/study.md §7/§11\n";
		}
		die "$prog: '$file' has no Strokes.plist - not a recognized .concept file\n";
	}

	my $plist = Mac::PropertyList::parse_plist($strokes_member->contents());
	my $objects = $plist->value->{'$objects'}->value;

	my $top_uid = $plist->value->{'$top'}->value->{root};
	my $root = deref($objects, $top_uid);

	my $group_layers = deref($objects, $root->value->{rootGroupLayers});
	my $layer_refs = ns_objects($group_layers);

	my @layers;
	for my $lref (@$layer_refs) {
		push @layers, decode_group($objects, deref($objects, $lref));
	}

	return { source_file => $file, layers => \@layers };
}

# Dereference a Mac::PropertyList::uid into the $objects[] element it
# points at; anything else (already-a-value) passes through unchanged, so
# callers don't need to know in advance whether a given field was stored
# as a UID reference or inlined.
sub deref {
	my ($objects, $item) = @_;
	return $item unless ref($item) eq 'Mac::PropertyList::uid';
	return $objects->[ hex($item->value) ];
}

# Fetch a dict field by key, dereferencing it - undef if the key is absent
# (distinct from present-but-null, which this format doesn't seem to use).
sub field {
	my ($objects, $dict, $key) = @_;
	return undef unless ref($dict) eq 'Mac::PropertyList::dict';
	my $h = $dict->value;
	return undef unless exists $h->{$key};
	return deref($objects, $h->{$key});
}

# NSKeyedArchiver's NSMutableArray/NSMutableSet dicts store their elements
# under an 'NS.objects' key (already an array Item, not a UID - the array
# itself was already dereferenced by the caller) - returns the arrayref of
# (still possibly-UID) elements.
sub ns_objects {
	my ($array_dict) = @_;
	return [] unless ref($array_dict) eq 'Mac::PropertyList::dict';
	my $ns = $array_dict->value->{'NS.objects'};
	return [] unless $ns;
	return $ns->value;
}

# real $classname of a dict's $class reference, or undef if it's not a
# class-tagged dict at all (plain string/array/etc values have no $class).
sub classname {
	my ($objects, $dict) = @_;
	my $cls = field($objects, $dict, '$class');
	return undef unless ref($cls) eq 'Mac::PropertyList::dict';
	my $cn = $cls->value->{'$classname'};
	return $cn ? $cn->value : undef;
}

# Full $classes ancestry (leaf to root, e.g. ["BallpointPen","Stroke",
# "DrawingItem","NSObject"]), or [] if not class-tagged. Confirmed on a
# real sample (~/my/art/export/iPad/Welcome to Concepts/Section 1/
# Untitled 2.concept) that a stroke's own $classname is NOT always the
# generic "Stroke" seen in the one Converted-PLISTS reference sample this
# repo's study.md was originally built from - it can be a per-tool
# subclass ("BallpointPen" confirmed; presumably Marker/Fineliner/Pencil/
# etc. too, per study.md's still-open brushToolNew question) whose own
# $classes ancestry does include "Stroke". Dispatch in decode_group()
# below checks ancestry via is_a(), not exact $classname, because of this.
sub class_ancestry {
	my ($objects, $dict) = @_;
	my $cls = field($objects, $dict, '$class');
	return [] unless ref($cls) eq 'Mac::PropertyList::dict';
	my $list = $cls->value->{'$classes'};
	return [] unless $list && ref($list) eq 'Mac::PropertyList::array';
	return [ map { $_->value } @{ $list->value } ];
}

sub is_a {
	my ($objects, $dict, $name) = @_;
	return scalar grep { $_ eq $name } @{ class_ancestry($objects, $dict) };
}

# Plain-scalar extraction for string/integer/real/date/data leaf Items;
# undef for anything else (dict/array/uid, or $item itself being undef).
sub scalar_value {
	my ($item) = @_;
	return undef unless defined $item && ref($item);
	return $item->value if ref($item) =~ /^Mac::PropertyList::(string|ustring|integer|real|date|data)$/;
	return undef;
}

# --- CGAffineTransform-shaped 64-byte buffer (a 4x4 float32 matrix with
# the 2D affine part at fixed offsets) - see study.md §5. Returns undef if
# $item isn't a data blob of the right size.
sub decode_transform {
	my ($item) = @_;
	return undef unless ref($item) eq 'Mac::PropertyList::data';
	my $buf = $item->value;
	return undef unless length($buf) >= 64;
	my ($a, $b) = unpack("f<f<", substr($buf, 0, 8));
	my ($c, $d) = unpack("f<f<", substr($buf, 16, 8));
	my ($tx, $ty) = unpack("f<f<", substr($buf, 48, 8));
	return [ $a, $b, $c, $d, $tx, $ty ];
}

# Parse a Concepts CGSize-as-string like "{397.5, 393}" -> [w, h], or undef.
sub parse_size_string {
	my ($s) = @_;
	return undef unless defined $s && $s =~ /\{\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\}/;
	return [ $1 + 0, $2 + 0 ];
}

# --- points -------------------------------------------------------------
#
# Fixed 16-byte stride (confirmed - see this file's header comment and
# study.md); first 8 bytes are always float32 LE x, y. The trailing 8
# bytes are reported as 4 raw uint16 LE values by default (offsets
# labeled, not decoded) - see decode_point_aux() for the optional
# --aux-hypothesis decode of the one flag combination study.md §12 found
# plausible.
use constant POINT_STRIDE => 16;

sub decode_points {
	my ($blob) = @_;
	return [] unless defined $blob && length($blob);
	if (length($blob) % POINT_STRIDE != 0) {
		warn "$prog: stroke point buffer length @{[length $blob]} is not a multiple of @{[POINT_STRIDE]} - skipping this stroke's points\n";
		return [];
	}
	my $n = length($blob) / POINT_STRIDE;
	my @points;
	for my $i (0 .. $n - 1) {
		my $off = $i * POINT_STRIDE;
		my ($x, $y) = unpack("f<f<", substr($blob, $off, 8));
		my @aux16 = unpack("v4", substr($blob, $off + 8, 8));
		push @points, { x => $x, y => $y, aux16 => \@aux16 };
	}
	return \@points;
}

# Fallback point source when strokePoints{45,NonOptionalAngles} is absent
# or empty: keyPoints -> NS.objects -> each KeyPoint's glPosition (8-byte
# data, float32 LE x, y - no aux channels at all here, confirmed simplest
# of the point encodings, see study.md §4).
sub decode_key_points {
	my ($objects, $key_points_dict) = @_;
	my @points;
	for my $ref (@{ ns_objects($key_points_dict) }) {
		my $kp = deref($objects, $ref);
		my $pos = field($objects, $kp, 'glPosition');
		next unless ref($pos) eq 'Mac::PropertyList::data' && length($pos->value) >= 8;
		my ($x, $y) = unpack("f<f<", substr($pos->value, 0, 8));
		push @points, { x => $x, y => $y, aux16 => undef };
	}
	return \@points;
}

# Study.md §12's cross-referenced-against-Apple's-own-PencilKit-formulas
# hypothesis, applied only when the stroke's own three *Available flags
# all say pressure/altitude/azimuth are present (the one configuration a
# real sample was checked against). Returns undef (not a guess) otherwise.
sub decode_point_aux {
	my ($aux16, $flags_all_present) = @_;
	return undef unless $flags_all_present && $aux16;
	my ($force_raw, $alt_raw, $unk_raw, $az_raw) = @$aux16;
	return {
		force    => $force_raw / AUX_FORCE_DIVISOR,
		altitude => AUX_ALTITUDE_BIAS - $alt_raw / AUX_ALTITUDE_DIVISOR,
		azimuth  => $az_raw / AUX_AZIMUTH_DIVISOR - AUX_AZIMUTH_BIAS,
		unknown_raw => $unk_raw, # study.md §12: still unexplained even in this configuration
	};
}

# --- item decoding --------------------------------------------------------

sub decode_stroke {
	my ($objects, $item) = @_;

	# The leaf $classname is often a per-tool subclass, not the generic
	# "Stroke" (e.g. "BallpointPen" - confirmed on a real sample, see
	# is_a()'s comment above); reported here as-is since it's real signal
	# towards study.md's still-open brushToolNew id->name question, not
	# just decoration.
	my $tool_class = classname($objects, $item);

	my $uuid = scalar_value(field($objects, $item, 'diIdentifier'));

	my $ts_dict = field($objects, $item, 'diTimeStamp');
	my $timestamp;
	if (ref($ts_dict) eq 'Mac::PropertyList::dict' && exists $ts_dict->value->{'NS.time'}) {
		my $secs = $ts_dict->value->{'NS.time'}->value;
		$timestamp = POSIX::strftime("%Y-%m-%dT%H:%M:%SZ", gmtime(978307200 + $secs));
	}

	my $bp = field($objects, $item, 'brushProperties');
	my ($width, $alpha, $tool, $color);
	if ($bp) {
		$width = scalar_value(field($objects, $bp, 'brushWidth'));
		$alpha = scalar_value(field($objects, $bp, 'brushAlpha'));
		$tool  = scalar_value(field($objects, $bp, 'brushToolNew'));
		my $col = field($objects, $bp, 'brushColor');
		if ($col) {
			$color = [
				scalar_value(field($objects, $col, 'UIRed'))   // 0,
				scalar_value(field($objects, $col, 'UIGreen')) // 0,
				scalar_value(field($objects, $col, 'UIBlue'))  // 0,
				scalar_value(field($objects, $col, 'UIAlpha')) // 1,
			];
		}
	}

	my $transform = decode_transform(field($objects, $item, 'diSavedTransform'));

	my $closed_item = field($objects, $item, 'closed');
	my $closed = ref($closed_item) eq 'Mac::PropertyList::true' ? 1
	           : ref($closed_item) eq 'Mac::PropertyList::false' ? 0
	           : undef;

	my $flags_all_present =
		ref(field($objects, $item, 'containsPressure'))        eq 'Mac::PropertyList::true' &&
		ref(field($objects, $item, 'altitudeAngleAvailable'))   eq 'Mac::PropertyList::true' &&
		ref(field($objects, $item, 'azimuthAngleAvailable'))    eq 'Mac::PropertyList::true';

	my $point_blob = field($objects, $item, 'strokePointsNonOptionalAngles')
	              // field($objects, $item, 'strokePoints45');
	my $points = ref($point_blob) eq 'Mac::PropertyList::data'
		? decode_points($point_blob->value)
		: [];
	if (!@$points) {
		my $kp = field($objects, $item, 'keyPoints');
		$points = decode_key_points($objects, $kp) if $kp;
	}

	return {
		type => 'stroke', uuid => $uuid, timestamp => $timestamp,
		tool_class => $tool_class,
		width => $width, alpha => $alpha, tool => $tool, color => $color,
		transform => $transform, closed => $closed,
		flags_all_present => $flags_all_present, points => $points,
	};
}

sub decode_image_like {
	my ($objects, $item, $type, $id_key, $props_key) = @_;

	my $uuid = scalar_value(field($objects, $item, $id_key));
	my $size = parse_size_string(scalar_value(field($objects, $item, 'size')));
	my $crop = scalar_value(field($objects, $item, 'crop'));
	my $transform = decode_transform(field($objects, $item, 'diSavedTransform'));

	my $props = field($objects, $item, $props_key);
	my ($alpha, $tool);
	if ($props) {
		$alpha = scalar_value(field($objects, $props, 'brushAlpha'));
		$tool  = scalar_value(field($objects, $props, 'brushToolNew'));
	}

	my %extra;
	if ($type eq 'pdf-page') {
		$extra{page} = scalar_value(field($objects, $item, 'page'));
	}

	return {
		type => $type, uuid => $uuid, size => $size, crop => $crop,
		transform => $transform, alpha => $alpha, tool => $tool, %extra,
	};
}

sub decode_text {
	my ($objects, $item) = @_;

	my $text = scalar_value(field($objects, $item, 'text'));
	my $transform = decode_transform(field($objects, $item, 'diSavedTransform'));

	my $props = field($objects, $item, 'properties');
	my ($alpha, $color);
	if ($props) {
		$alpha = scalar_value(field($objects, $props, 'brushAlpha'));
		my $col = field($objects, $props, 'brushColor');
		if ($col) {
			$color = [
				scalar_value(field($objects, $col, 'UIRed'))   // 0,
				scalar_value(field($objects, $col, 'UIGreen')) // 0,
				scalar_value(field($objects, $col, 'UIBlue'))  // 0,
				scalar_value(field($objects, $col, 'UIAlpha')) // 1,
			];
		}
	}

	return { type => 'text', text => $text, transform => $transform, alpha => $alpha, color => $color };
}

# A DrawingItemGroup found *inside* another group's groupItems (as opposed
# to the top-level layers, which are also DrawingItemGroups but walked
# separately by decode_concept_file) - nesting depth beyond one level is
# not confirmed to occur in real files, but the walk is written to support
# it regardless rather than assume a fixed two-level tree (see study.md §3).
sub decode_group {
	my ($objects, $group) = @_;

	my $name = scalar_value(field($objects, $group, 'name'));
	my $items_dict = field($objects, $group, 'groupItems');
	my $item_refs = ns_objects($items_dict);

	my @items;
	for my $iref (@$item_refs) {
		my $item = deref($objects, $iref);
		# Ancestry-based dispatch, not exact-$classname - a stroke's own
		# leaf class can be a per-tool subclass like "BallpointPen" rather
		# than the generic "Stroke" (confirmed on a real sample; see
		# is_a()'s comment above). classname() (exact leaf name) is still
		# used for the fallback branch's diagnostic output below.
		my $cn = classname($objects, $item) // '';

		if (is_a($objects, $item, 'Stroke')) {
			push @items, decode_stroke($objects, $item);
		}
		elsif (is_a($objects, $item, 'ImageItem')) {
			push @items, decode_image_like($objects, $item, 'image', 'imageIdentifier', 'imageProperties');
		}
		elsif (is_a($objects, $item, 'TiledPDFPage')) {
			push @items, decode_image_like($objects, $item, 'pdf-page', 'resourceIdentifier', 'pdfProperties');
		}
		elsif (is_a($objects, $item, 'TextItem')) {
			push @items, decode_text($objects, $item);
		}
		elsif (is_a($objects, $item, 'DrawingItemGroup')) {
			push @items, { type => 'group', %{ decode_group($objects, $item) } };
		}
		else {
			# Honesty over silence: every existing 3rd-party viewer just
			# drops classes it doesn't recognize (MeasurementLayerItem,
			# eraser/auto-shape items if the $classname differs from what
			# the 3 reference viewers guessed - see study.md §3/§11 Q4).
			# Report the shape instead of hiding the gap.
			push @items, {
				type => 'unknown', classname => $cn || '(no $class)',
				keys => [ ref($item) eq 'Mac::PropertyList::dict' ? sort(keys %{ $item->value }) : () ],
			};
		}
	}

	return { name => $name, items => \@items };
}

# --- output: s-expression ---------------------------------------------------

sub esc_str {
	my ($s) = @_;
	return '""' unless defined $s;
	$s =~ s/(["\\])/\\$1/g;
	return "\"$s\"";
}

sub fmt_num { return defined $_[0] ? sprintf("%.6g", $_[0]) : "nil"; }

sub fmt_transform {
	my ($t) = @_;
	return "nil" unless $t;
	return "(" . join(" ", map { fmt_num($_) } @$t) . ")"; # a b c d tx ty
}

sub fmt_color {
	my ($c) = @_;
	return "nil" unless $c;
	return "(" . join(" ", map { fmt_num($_) } @$c) . ")"; # r g b a
}

sub render_point_line {
	my ($p, $flags_all_present, $aux_hypothesis) = @_;
	my $line = sprintf("(%s %s)", fmt_num($p->{x}), fmt_num($p->{y}));
	return $line unless $p->{aux16};

	my @parts = ("aux16=" . join(",", map { sprintf("0x%04x", $_) } @{ $p->{aux16} }));
	if ($aux_hypothesis) {
		my $dec = decode_point_aux($p->{aux16}, $flags_all_present);
		push @parts, sprintf(
			"hypothesis(force=%.4f altitude=%.4frad azimuth=%.4frad unknown_raw=0x%04x; unconfirmed, see study.md §12)",
			$dec->{force}, $dec->{altitude}, $dec->{azimuth}, $dec->{unknown_raw}
		) if $dec;
	}
	return "$line ; " . join(" ", @parts);
}

sub render_item_sexp {
	my ($item, $indent, $aux_hypothesis) = @_;
	my $pad = "  " x $indent;
	my @out;

	if ($item->{type} eq 'stroke') {
		push @out, "${pad}(stroke";
		push @out, "${pad}  (uuid " . esc_str($item->{uuid}) . ")";
		push @out, "${pad}  (class " . esc_str($item->{tool_class}) . ") ; leaf \$classname - a per-tool Stroke subclass, e.g. \"BallpointPen\"";
		push @out, "${pad}  (timestamp " . esc_str($item->{timestamp}) . ")";
		push @out, "${pad}  (tool " . (defined $item->{tool} ? $item->{tool} : "nil") . ") ; brushToolNew, id->name unconfirmed - see study.md";
		push @out, "${pad}  (width " . fmt_num($item->{width}) . ")";
		push @out, "${pad}  (alpha " . fmt_num($item->{alpha}) . ")";
		push @out, "${pad}  (color " . fmt_color($item->{color}) . ") ; r g b a, 0..1";
		push @out, "${pad}  (transform " . fmt_transform($item->{transform}) . ") ; a b c d tx ty";
		push @out, "${pad}  (closed " . (defined $item->{closed} ? ($item->{closed} ? "t" : "nil") : "nil") . ")";
		push @out, "${pad}  (point-stride " . POINT_STRIDE . ")";
		push @out, "${pad}  (points";
		push @out, map { "${pad}  " . render_point_line($_, $item->{flags_all_present}, $aux_hypothesis) } @{ $item->{points} };
		push @out, "${pad}  )";
		push @out, "${pad})";
	}
	elsif ($item->{type} eq 'image' || $item->{type} eq 'pdf-page') {
		push @out, "${pad}(" . $item->{type};
		push @out, "${pad}  (uuid " . esc_str($item->{uuid}) . ")";
		push @out, "${pad}  (page " . $item->{page} . ")" if exists $item->{page};
		push @out, "${pad}  (size " . ($item->{size} ? "(" . join(" ", map { fmt_num($_) } @{ $item->{size} }) . ")" : "nil") . ")";
		push @out, "${pad}  (crop " . esc_str($item->{crop}) . ")" if defined $item->{crop};
		push @out, "${pad}  (alpha " . fmt_num($item->{alpha}) . ")";
		push @out, "${pad}  (tool " . (defined $item->{tool} ? $item->{tool} : "nil") . ")";
		push @out, "${pad}  (transform " . fmt_transform($item->{transform}) . ") ; a b c d tx ty";
		push @out, "${pad})";
	}
	elsif ($item->{type} eq 'text') {
		push @out, "${pad}(text";
		push @out, "${pad}  (text " . esc_str($item->{text}) . ")";
		push @out, "${pad}  (color " . fmt_color($item->{color}) . ") ; r g b a, 0..1";
		push @out, "${pad}  (alpha " . fmt_num($item->{alpha}) . ")";
		push @out, "${pad}  (transform " . fmt_transform($item->{transform}) . ") ; a b c d tx ty";
		push @out, "${pad})";
	}
	elsif ($item->{type} eq 'group') {
		push @out, "${pad}(group";
		push @out, "${pad}  (name " . esc_str($item->{name}) . ")";
		push @out, "${pad}  (items";
		push @out, map { render_item_sexp($_, $indent + 2, $aux_hypothesis) } @{ $item->{items} };
		push @out, "${pad}  )";
		push @out, "${pad})";
	}
	else { # unknown
		push @out, "${pad}(unknown";
		push @out, "${pad}  (class " . esc_str($item->{classname}) . ")";
		push @out, "${pad}  (keys " . join(" ", map { esc_str($_) } @{ $item->{keys} }) . ")";
		push @out, "${pad})";
	}

	return join("\n", @out) . "\n";
}

sub render_sexp {
	my ($d) = @_;
	my @out;
	push @out, "(drawing";
	push @out, "  (source-file " . esc_str($d->{source_file}) . ")";
	push @out, "  (layers";
	for my $layer (@{ $d->{layers} }) {
		push @out, "    (layer";
		push @out, "      (name " . esc_str($layer->{name}) . ")";
		push @out, "      (items";
		push @out, map { render_item_sexp($_, 4, $aux_hypothesis) } @{ $layer->{items} };
		push @out, "      )";
		push @out, "    )";
	}
	push @out, "  )";
	push @out, ")";
	return join("\n", @out) . "\n";
}

# --- output: InkML (X/Y geometry only, flattened across layers/groups) ------

sub collect_strokes_inkml {
	my ($items, $out) = @_;
	for my $item (@$items) {
		if ($item->{type} eq 'stroke') {
			push @$out, $item->{points} if @{ $item->{points} };
		}
		elsif ($item->{type} eq 'group') {
			collect_strokes_inkml($item->{items}, $out);
		}
	}
}

sub render_inkml {
	my ($d) = @_;
	my @out;
	push @out, '<?xml version="1.0" encoding="UTF-8"?>';
	push @out, '<ink xmlns="http://www.w3.org/2003/InkML">';
	push @out, '  <traceFormat>';
	push @out, '    <channel name="X" type="decimal"/>';
	push @out, '    <channel name="Y" type="decimal"/>';
	push @out, '  </traceFormat>';
	push @out, '  <context xml:id="ctx0"/>';

	my @strokes;
	for my $layer (@{ $d->{layers} }) {
		collect_strokes_inkml($layer->{items}, \@strokes);
	}

	my $i = 0;
	for my $points (@strokes) {
		my @pts = map { sprintf("%.4f %.4f", $_->{x}, $_->{y}) } @$points;
		push @out, sprintf('  <trace id="t%d" contextRef="#ctx0">%s</trace>', $i++, join(", ", @pts));
	}

	push @out, '</ink>';
	return join("\n", @out) . "\n";
}
