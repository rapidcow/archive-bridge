package org.rapidcow.ai.archive.bridge.mscherotter.journalist;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.rapidcow.ai.archive.bridge.microsoft.windowsink.InkDocument;
import org.rapidcow.ai.archive.bridge.microsoft.windowsink.IsfDecoder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A read-only Journalist ZIP, with pages and items in XML order.
 * Use try-with-resources to close the underlying ZIP. No files are extracted.
 * The DOM is caller-owned mutable metadata; changing it does not change the ZIP
 * or the page/item lists captured at open time. This class is not thread-safe.
 */
public final class JournalArchive implements AutoCloseable {
    /** Namespace of the observed Journalist XML format. */
    public static final String NAMESPACE = "http://www.charette.com/journalist/1.0";

    /** Allocation limits for XML and individual attachments, measured after decompression. */
    public record Limits(int maxXmlBytes, int maxAttachmentBytes, int maxEntries) {
        /** Conservative defaults; callers may supply larger limits explicitly. */
        public static final Limits DEFAULT = new Limits(32 * 1024 * 1024, 64 * 1024 * 1024, 10_000);

        public Limits {
            if (maxXmlBytes < 1 || maxAttachmentBytes < 1 || maxEntries < 1
                    || maxXmlBytes == Integer.MAX_VALUE || maxAttachmentBytes == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Limits must be positive and byte limits below Integer.MAX_VALUE");
            }
        }
    }

    /** A page's complete metadata and actual Items children (excluding Typical* templates). */
    public record Page(Element xml, List<Item> items) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** An item of any type; unknown item types retain their complete XML subtree. */
    public record Item(Element xml) {
        /** Local XML element name, such as Sketch, Text, or ShapePageItem. */
        public String type() {
            return xml.getLocalName();
        }
    }

    private final ZipFile zip;
    private final Limits limits;
    private final Document xml;
    private final List<Page> pages;
    private final List<String> entryNames;

    private JournalArchive(ZipFile zip, Limits limits) throws IOException {
        this.zip = zip;
        this.limits = limits;
        var names = new ArrayList<String>();
        Set<String> unique = new HashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (names.size() == limits.maxEntries()) {
                throw new IOException("Journalist ZIP exceeds entry limit");
            }
            if (!unique.add(entry.getName())) {
                throw new IOException("Journalist ZIP has duplicate entry names");
            }
            names.add(entry.getName());
        }
        entryNames = List.copyOf(names);
        xml = parseXml(read("journal.xml", limits.maxXmlBytes()));
        Element root = xml.getDocumentElement();
        if (!matches(root, "Journal")) {
            throw new IOException("Expected Journalist Journal root and namespace");
        }
        var foundPages = new ArrayList<Page>();
        for (Element container : children(root, "Pages")) {
            for (Element page : children(container, "JournalPage")) {
                var items = new ArrayList<Item>();
                for (Element itemContainer : children(page, "Items")) {
                    for (Node node = itemContainer.getFirstChild(); node != null; node = node.getNextSibling()) {
                        if (node instanceof Element element) {
                            items.add(new Item(element));
                        }
                    }
                }
                foundPages.add(new Page(page, items));
            }
        }
        pages = List.copyOf(foundPages);
    }

    /** Opens a document using default limits. */
    public static JournalArchive open(Path path) throws IOException {
        return open(path, Limits.DEFAULT);
    }

    /** Opens a document and validates its ZIP directory and XML. */
    public static JournalArchive open(Path path, Limits limits) throws IOException {
        java.util.Objects.requireNonNull(limits, "limits");
        ZipFile zip = new ZipFile(path.toFile());
        try {
            return new JournalArchive(zip, limits);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                zip.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /** Complete XML, including metadata and prototype/template elements. */
    public Document xml() {
        return xml;
    }

    /** Actual pages in document order. */
    public List<Page> pages() {
        return pages;
    }

    /** Exact ZIP entry names, including directory entries. */
    public List<String> entryNames() {
        return entryNames;
    }

    /** Reads an exact ZIP entry name without resolving it against the filesystem. */
    public byte[] readAttachment(String name) throws IOException {
        return read(name, limits.maxAttachmentBytes());
    }

    /**
     * Reads a Sketch's ink. A nonempty Ink attribute takes precedence over
     * InkBase64; a broken reference fails instead of silently using the fallback.
     * Base64 allows XML whitespace only. The result may be a fortified GIF,
     * rather than bare ISF. Non-Sketch items and sketches without ink return empty.
     */
    public Optional<byte[]> inkBytes(Item item) throws IOException {
        Element element = item.xml();
        if (!matches(element, "Sketch")) {
            return Optional.empty();
        }
        String reference = element.getAttribute("Ink");
        if (!reference.isEmpty()) {
            return Optional.of(readAttachment(reference));
        }
        List<Element> embedded = children(element, "InkBase64");
        if (embedded.isEmpty()) {
            return Optional.empty();
        }
        if (embedded.size() != 1) {
            throw new IOException("Sketch has multiple InkBase64 elements");
        }
        String text = embedded.get(0).getTextContent();
        var compact = new StringBuilder();
        long maxEncoded = 4L * ((limits.maxAttachmentBytes() + 2L) / 3);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                if (compact.length() >= maxEncoded) {
                    throw new IOException("Embedded ink exceeds attachment limit");
                }
                compact.append(c);
            }
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(compact.toString());
            if (bytes.length > limits.maxAttachmentBytes()) {
                throw new IOException("Embedded ink exceeds attachment limit");
            }
            return Optional.of(bytes);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid InkBase64 encoding");
        }
    }

    /** Decodes an item's ink using the Windows Ink reader, if present. */
    public Optional<InkDocument> decodeInk(Item item) throws IOException {
        Optional<byte[]> bytes = inkBytes(item);
        return bytes.isEmpty() ? Optional.empty() : Optional.of(new IsfDecoder().decode(bytes.get()));
    }

    private byte[] read(String name, int limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Missing Journalist ZIP attachment");
        }
        if (entry.getSize() > limit) {
            throw new IOException("Journalist ZIP entry exceeds byte limit");
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Journalist ZIP entry exceeds byte limit");
            }
            return bytes;
        }
    }

    private static Document parseXml(byte[] bytes) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "256");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | IllegalArgumentException exception) {
            throw new IOException("Secure XML parser unavailable", exception);
        } catch (SAXException exception) {
            // Parser messages may include personal XML contents; do not echo them.
            throw new IOException("Invalid or prohibited Journalist XML");
        }
    }

    private static boolean matches(Element element, String name) {
        return NAMESPACE.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && matches(element, name)) {
                result.add(element);
            }
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
