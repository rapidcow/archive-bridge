package RdKw::Apple::Notes::Text;

use RdKw::Apple::Notes ();
our $VERSION = $RdKw::Apple::Notes::VERSION;

use strict;
use warnings;
use Carp;

sub new
{
	@_ or croak(__PACKAGE__ . "::new: missing class/instance");
	my $cls = shift;

	if (my $class = ref $cls) {
		return bless { %$cls }, $class;
	}

	@_ or croak("usage: ${cls}->new( text, ... )");
	my $text = shift;
	my $self = { text => $text, @_ };

	bless $self, $cls;
}

sub text  # ha ha
{
	my $self = shift;
	if (@_) {
		$self->{text} = shift;
		return $self;  # chain
	}

	$self->{text}
}

1;
