# Optional private ink sample

`single-sketch.gif` is a 388-byte fortified GIF extracted from a Journalist
archive. It was selected solely as the smallest ink entry, before checking its
geometry. Its raster is 1×1; the embedded ISF contains one stroke with two
coincident X/Y points, with pressure and packet-status channels as well. It is
a degenerate-stroke sample useful for container and minimal packet decoding,
not a representative handwriting or rendering fixture. It is retained as-is.

The content belongs in git-annex, not ordinary Git. Do not publish
the annex content or attach it to reports. No original filename, journal XML,
title, author, or page text is recorded here.

Both Java modules use this optional sample directly from the filesystem.
Their private-sample tests skip when it is absent, including when the annex
symlink exists but its target has not been obtained. Synthetic tests still run.
Keeping it outside Gradle resources also avoids copying a dangling symlink.

Override the sample location with Gradle `-PjournalistInkFixture=/path/to/sample.gif`.
A missing path skips the private-sample tests without changing the annex file.

## Provenance and reproducible extraction

Source archive annex key (intentionally no source filename):

```
SHA256E-s2198593--5052a5aab8d7f68cfb4e09aeaf1fd367dedaa00d76a9317606161965d7ac3adc
```

Selection: the smallest referenced ink ZIP member by uncompressed byte count
across the four local reference archives. Its exact location in the source is
page **1**, item **90**, both one-based. Item numbering includes every direct
element child of `Items`, not just sketches.

Mechanism: open the source as ZIP; parse `journal.xml` using namespace
`http://www.charette.com/journalist/1.0`; select
`Journal/Pages/JournalPage[1]/Items/*[90]`; require that element to be `Sketch`;
use its `Ink` attribute as the exact ZIP member name. Copy that member's
uncompressed bytes. There is no XML copied, Base64 decoding, raster conversion,
GIF re-encoding, or ISF rewriting in this extraction. ZIP decompression is the
only transformation. The extracted file is then added to local git-annex.

Output annex key:

```
SHA256E-s388--eb3a967e7f368fead2632706a67b173f83a1d08b858cef10b477ae8e23f42f57.gif
```

From this project's root, with `source_repo` set to the local private annex
repository (no network retrieval is performed):

```sh
source_repo=/path/to/private/annex/repository
source_key=SHA256E-s2198593--5052a5aab8d7f68cfb4e09aeaf1fd367dedaa00d76a9317606161965d7ac3adc
source_object=$(git -C "$source_repo" annex contentlocation "$source_key") &&
python3 ai/fixtures/journalist/extract.py \
  "$source_repo/$source_object" /tmp/reproduced-single-sketch.gif
```

The script verifies the source size and SHA-256 before reading it, and the
output size and SHA-256 before writing. It refuses to overwrite an existing
output. Keep the reproduced bytes private just like the annex content.
