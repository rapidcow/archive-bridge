# Ink Serialized Format decoding

This is binary Microsoft ISF, not W3C InkML. Implementation:
`java/microsoft-windowsink-ai`, package `org.rapidcow.ai.archive.bridge.microsoft.windowsink`.

Sources: `vendor/microsoft/InkSerializedFormat_Specification.pdf` (packet
layout, compression, tag appendix) and the
[official WPF decoder](https://github.com/dotnet/wpf/tree/main/src/Microsoft.DotNet.Wpf/src/PresentationCore/MS/internal/Ink/InkSerializedFormat)
(`AlgoModule.cs`, `HuffCodec.cs`, `Codec.cs`, `GorillaCodec.cs`, `InkSerializer.cs`).
WPF supplies the eight default Huffman tables and exact delta recurrence that
are not fully described in the PDF.

`new IsfDecoder().decode(bytes)` accepts raw ISF and fortified GIF. The GIF
comment extension (`21 FE`, concatenated subblocks) contains raw ISF; the image
is a raster preview. The decoder traverses blocks rather than scanning image
bytes for signatures.

Version zero precedes an unsigned variable-length body size. Stroke blocks have
byte sizes, point counts, and compressed channel vectors. Default channels are
X/Y (tags 50/51); tag 56 is normal pressure. Descriptors add channels or omit X/Y;
indices select descriptors, drawing attributes, transforms and metrics.

Implemented packet algorithms: signed MSB-first bit packing (width zero means
32), packing with two signed-varint second-difference seeds, and all eight
default Huffman tables with bounded overflow escape decoding. Delta decoding
starts with two zero history values and uses `delta + 2*previous - previous2`.
Channels each end on a byte boundary.

Raw coordinates remain unchanged; each stroke has an affine transform. Float
and extended double transform tables are supported. Usual units are HIMETRIC
(0.01 mm); after transforming, multiply by DPI/2540 for pixel coordinates.

Drawing attributes, metric blocks, custom global properties, stroke properties
and button data remain serialized bytes. Metadata retains GUID and descriptor
blocks too. There is no rendering or custom-property decompression yet. Custom
compression tables/index mappings, unknown control tags and unsupported
transforms fail explicitly. This is a packet reader, not a complete ISF writer.

Reads are bounded by enclosing blocks. Default decoded scalar packet budget:
two million per document (configurable constructor). Callers must also bound
input bytes. Errors expose format diagnostics/offsets, not payload contents.

Synthetic inline tests cover signed packing, Huffman, delta reconstruction,
length boundaries and allocation limits. Optional private annex GIF test skips
when its content is unavailable. No personal coordinates or text are recorded
in source or notes. A read-only local corpus check decoded all 3,773 GIF ink
entries without format errors; this demonstrates encountered encoding coverage,
not independently verified visual equivalence with Windows rendering.
