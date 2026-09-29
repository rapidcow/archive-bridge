package Local::Plugin::Snake;

use 5.006;
use strict;
use warnings;
use base qw( Template::Plugin );

use Exporter qw(import);
our @EXPORT_OK = qw(snake SNAKE);

=head1 NAME

Local::Plugin::Snake - camel case to snake case

=head1 SYNOPSIS

  # Perl (global pre-loading)
  
  use Local::Plugin::Snake;
  my $tt = Template->new( PLUGIN_BASE => 'Local::Plugin' );
  Local::Plugin::Snake->new( $tt->context() );
  
  # TT2 (lazy loading)
  
  [% USE snake %]
  [% chan.snake %]
  [% chan.SNAKE %]

=head1 ROUTINES

=over

=item B<new($context)>

Register the virtual methods snake and SNAKE
in the given B<$context>.

=back

=cut

sub new
{
	my ($class, $context) = @_;

	# define_vmethod has yet to be officially documented
	# in Template::Manual::VMethods, but we can see several
	# instances of it being used in, say, the Assert plugin.
	#
	# this is taken based on Template/Plugin/Assert.pm.
	$context->define_vmethod( scalar => snake => \&snake );
	$context->define_vmethod( scalar => SNAKE => \&SNAKE );

	bless \1, $class;
}

=head1 VIRTUAL METHODS

=over

=item B<snake>

Camel case to snake case.

=item B<SNAKE>

Camel case to upper-case-ified snake case.

=back

=cut

sub snake
{
	my ($ident) = @_;
	$ident =~ s/([a-z0-9])([A-Z])/${1}_${2}/g;
	$ident;
}

sub SNAKE { uc(&snake); }
