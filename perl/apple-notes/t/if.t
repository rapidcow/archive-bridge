#!perl
#
# t/if.t - interface

use strict;
use warnings;
use Test::More tests => 4;

use RdKw::Apple::Notes::Text;

my $node = RdKw::Apple::Notes::Text->new("Hello world!");
is $node->text, "Hello world!", ":text read";
$node->text("i ate a car", "i will eat another");
is $node->text, "i ate a car", ":text write";

{
	local $@;
	eval { RdKw::Apple::Notes::Text::new() };
	like $@, qr/missing/, "E: no arg";
}
{
	local $@;
	eval { RdKw::Apple::Notes::Text->new() };
	like $@, qr/usage:.*text/, "E: no text";
}
