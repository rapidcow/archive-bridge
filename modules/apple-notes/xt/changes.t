#!perl
#
# xt/changes.t - change log format check

use 5.006;
use strict;
use warnings;
use Test::More;

eval "use Test::CPAN::Changes";
plan skip_all => "Test::CPAN::Changes required" if $@;

changes_ok();
