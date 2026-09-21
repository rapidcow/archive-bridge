#!perl
#
# t/00-load.t - basic load

use strict;
use warnings;
use Test::More tests => 1;

BEGIN {
	my @module = qw(
		RdKw::Apple::Notes::Schema
	);
	foreach my $module (@module) {
		use_ok( $module ) or BAIL_OUT;
	}
}
