#!/usr/bin/env perl

use strict;
use warnings;

use Config;
use File::Basename;
use File::Spec;
use Getopt::Long;

my @modules = qw(
	apple-notes
	apple-notes-drawing
	apple-notes-schema
);

my $Me = basename($0);
my $usage = "usage: $Me [-s SHELL]\n";
my $shell;

my $sep = $Config{ path_sep };

my %shellstyle = (
	bourne => \&export_bourne,
	csh => \&export_csh,
	fish => \&export_fish,
);

GetOptions("s=s" => \$shell) or die $usage;
@ARGV and die "$Me: too many arguments\n";

unless (defined $shell) {
	$shell = guess_shell() or die "$Me: could not infer your shell.  Override with -s SHELL.\n";
}

unless (exists $shellstyle{$shell}) {
	die "$Me: Shell type \`$shell' is not implemented.\n";
}

print $shellstyle{$shell}->(
	prepend_path(
		PERL5LIB => map { (
			File::Spec->rel2abs(File::Spec->catfile($_, "blib")),
			File::Spec->rel2abs(File::Spec->catfile($_, "lib")),
		) } @modules,
	),
);

# Partially adopted from local/lib.pm, guess_shelltype()
sub guess_shell
{
	my $shell_bin;

	if (defined $ENV{SHELL} && length $ENV{SHELL}) {
		($shell_bin) = ($ENV{SHELL} =~ /([\w.]+)$/);
	}

	defined $shell_bin or return;

	for ($shell_bin) {
		return (
			/csh$/ ? 'csh'
			: /fish$/ ? 'fish'
			: 'bourne'
		);
	}
}

sub prepend_path
{
	my ($name, @value) = @_;
	my %ihaz;
	my @path;
	if (defined $ENV{$name}) {
		push @path, grep { length && !$ihaz{$_}++ } split( /\Q${sep}\E/, $ENV{$name} );
	}
	unshift @path, grep { length && !$ihaz{$_}++ } @value;
	( $name => \@path );
}

sub export_bourne
{
	my $buf = '';
	while (my ($name, $values) = splice (@_, 0, 2)) {
		(my $value = join( $sep, @$values )) =~ s/(["\\\$!`])/\\$1/g;
		$buf .= qq[export $name="$value";\n];
	}
	$buf;
}

sub export_csh
{
	my $buf = '';
	while (my ($name, $values) = splice (@_, 0, 2)) {
		(my $value = join( $sep, @$values )) =~ s/(["\\\$!`])/\\$1/g;
		$buf .= qq[setenv $name "$value";\n];
	}
	$buf;
}

sub export_fish
{
	my $buf = '';
	while (my ($name, $values) = splice (@_, 0, 2)) {
		(my $value = join( $sep, @$values )) =~ s/(["\\\$!])/\\$1/g;
		$buf .= qq[set -x $name "$value";\n];
	}
	$buf;
}
