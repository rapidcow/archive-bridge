package RdKw::Apple::Notes::Schema::StrokeData;

use RdKw::Apple::Notes::Schema ();
our $VERSION = $RdKw::Apple::Notes::Schema::VERSION;

=head1 NAME

Apple::Notes::Schema::StrokeData - StrokeData ProtoBuf message

=cut

use strict;
use warnings;

use File::ShareDir ();
use Google::ProtocolBuffers ();

BEGIN {
	my $proto = File::ShareDir::dist_file(
		$RdKw::Apple::Notes::Schema::DISTNAME, 'proto/StrokeData.proto');
	Google::ProtocolBuffers->parsefile($proto);
}

1;
