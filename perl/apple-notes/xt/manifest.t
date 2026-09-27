#!perl
#
# xt/manifest.t - git-aware manifest check

use 5.006;
use strict;
use warnings;

use File::Find; # find
use File::Spec;
use IPC::Open3 qw(open3);
use Test::More;

# Explicitly load IO::Handle for perls older than v5.14.0.
use IO::Handle;

my $min_tcm = 0.9;
eval "use Test::CheckManifest $min_tcm";
plan skip_all => "Test::CheckManifest $min_tcm required" if $@;

my ($igndirs, $ignfile) = git_ls_ignore();
push @$igndirs, '/.git';
push @$ignfile, '.gitignore';

# We have to ignore directories with 'exclude', and
# files with 'filter' (we could do dirs with 'filter'
# too?  But that would be hell, and I'd rather not.)
ok_manifest({
	# This is checked relative to the current directory
	# (technically, the same directory it finds MANIFEST in,
	# but since I know MANIFEST is here I just know it's here.)
	exclude => $igndirs,
	bool => 'or',
	# This is checked against absolute path.  We must also
	# pass blessed Regexp references, or risk having them
	# ruthlessly stripped by the validation function.
	filter => [ map {;
		my $file = File::Spec->rel2abs($_);
		qr/\A\Q$file\E\z/;
	} @$ignfile ],
});

sub git_ls_ignore
{
	my ($v0id, $vo1d, @out);
	my ($pid, $pin, $put);
	my (@igndirs, @ignfile);

	open $v0id, '<', File::Spec->devnull();
	open $vo1d, '>', File::Spec->devnull();

	$pid = eval {
		open3($v0id, $put, $vo1d, qw( git version ));
	};
	goto SLEEP if $@;  # Definitely not running Git
	chomp (@out = <$put>);
	close $put;
	waitpid $pid, 0;
	if ($? >> 8 || !@out || $out[0] !~ /git version .+/) {
		goto SLEEP;  # Probably not running Git
	}

	$pid = eval {
		open3($v0id, $put, $vo1d, qw( git rev-parse --is-inside-work-tree ));
	};
	goto SLEEP if $@;
	chomp (@out = <$put>);
	close $put;
	waitpid $pid, 0;
	if ($? >> 8 || !@out || $out[0] eq 'false') {
		goto SLEEP;
	}

	$pid = eval {
		open3($pin, $put, $vo1d, qw( git check-ignore -z --stdin --verbose --non-matching ));
	};
	goto SLEEP if $@;

{
	local $/ = "\0";

	find({
		no_chdir => 1,
		wanted => sub {
			my $ign = $_;

			# git-check-ignore(1): If -z is specified, the pathnames in the output
			# are delimited by the null character; if --verbose is also specified
			# then null characters are also used instead of colons and hard tabs:
			# <source> <NULL> <linenum> <NULL> <pattern> <NULL> <pathname> <NULL>
			print $pin $ign;
			print $pin "\0";
			$pin->flush();

			chomp (my $source   = <$put>);
			chomp (my $linenum  = <$put>);
			chomp (my $pattern  = <$put>);
			chomp (my $pathname = <$put>);

			if (grep !!$_ => map {length} $source, $linenum, $pattern) {
				# This ensures that the case in the (but not unlikely)
				# circumstance that an entire directory is gitignored
				# is accounted for.  (I of course assumed that we are
				# using the Unix path separator, though I don't really
				# see a way around it... sorry Windows. :c)
				if (-d) {
					$ign =~ s!^.(/|$)!$1!;
					# Of course, if the gitignored directory is us,
					# something might be wrong....
					if ($ign eq '') {
						warn <<EOM;
warning: the whole root directory is gitignored? anyways...
EOM
						# ... but we persist >:D  Just because!
						$ign = '/';
					}
					push @igndirs, $ign;
					# Quit searching contents of this directory too
					$File::Find::prune = 1;
				}
				else {
					push @ignfile, $ign;
				}
			}
		}
	}, '.');
}

	close $pin;
	waitpid $pid, 0;

SLEEP:
	close $v0id;
	close $vo1d;
	(\@igndirs, \@ignfile);
}
