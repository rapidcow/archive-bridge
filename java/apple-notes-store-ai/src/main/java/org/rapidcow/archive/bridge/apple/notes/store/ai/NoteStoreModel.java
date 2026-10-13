package org.rapidcow.archive.bridge.apple.notes.store.ai;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;

/**
 * The Core Data model a store was built with, as the store itself records
 * it, read without interpretation.
 *
 * <p>Core Data keeps two things about the model inside every store:
 * <ul>
 * <li>{@code Z_METADATA}: a binary plist holding, among others, a hash per
 *     entity ({@code NSStoreModelVersionHashes}) and a digest over those
 *     ({@code NSStoreModelVersionHashesDigest}). Core Data compares these
 *     against an app's model to decide whether a store needs migrating, so
 *     the {@linkplain #digest() digest} identifies the schema: equal digests
 *     mean the same entities, properties and table layout.</li>
 * <li>{@code Z_MODELCACHE}: the complete {@code NSManagedObjectModel},
 *     archived with {@code NSKeyedArchiver} and compressed (raw DEFLATE).
 *     Core Data writes it when it creates or migrates the store, and reads
 *     it to find the source model of a migration.</li>
 * </ul>
 *
 * <p>Apple attaches no version name or number to a model. A version is
 * therefore something the caller pins: map {@link #digest()} to whatever
 * the caller knows about that schema. Do not pin on the bytes of
 * {@link #modelCache()}: two stores with the same digest can hold
 * different cache bytes (one archive records class names, the other not).
 *
 * <p>Values are copied out of the store when it is {@linkplain
 * NoteStore#model() read}; this object does not hold the store open.
 * Accessors returning arrays return fresh copies.
 */
public final class NoteStoreModel {
	private static final String DIGEST_KEY = "NSStoreModelVersionHashesDigest";
	private static final String HASHES_KEY = "NSStoreModelVersionHashes";
	private static final String FRAMEWORK_VERSION_KEY = "NSPersistenceFrameworkVersion";

	private final String storeUuid;
	private final byte[] metadataPlist;
	private final String digest;
	private final Map<String, byte[]> entityVersionHashes;
	private final Integer frameworkVersion;
	private final byte[] modelCache;

	private NoteStoreModel(String storeUuid, byte[] metadataPlist, String digest,
			Map<String, byte[]> entityVersionHashes, Integer frameworkVersion, byte[] modelCache) {
		this.storeUuid = storeUuid;
		this.metadataPlist = metadataPlist;
		this.digest = digest;
		this.entityVersionHashes = entityVersionHashes;
		this.frameworkVersion = frameworkVersion;
		this.modelCache = modelCache;
	}

	/** Backs {@link NoteStore#model()}. */
	static NoteStoreModel read(Connection c, boolean hasModelCache) throws SQLException {
		String uuid;
		byte[] plist;
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT Z_UUID, Z_PLIST FROM Z_METADATA")) {
			if (!rs.next()) {
				throw new NoteStoreException("Z_METADATA is empty");
			}
			uuid = rs.getString(1);
			plist = rs.getBytes(2);
			if (rs.next()) {
				throw new NoteStoreException("Z_METADATA has more than one row");
			}
		}
		if (plist == null) {
			throw new NoteStoreException("Z_METADATA has no Z_PLIST");
		}
		NSDictionary metadata = parseDictionary(plist, "Z_METADATA.Z_PLIST");

		NSObject digest = metadata.get(DIGEST_KEY);
		if (!(digest instanceof NSString)) {
			throw new NoteStoreException("Z_METADATA has no " + DIGEST_KEY);
		}
		Map<String, byte[]> hashes = new TreeMap<>();
		if (metadata.get(HASHES_KEY) instanceof NSDictionary d) {
			for (Map.Entry<String, NSObject> e : d.entrySet()) {
				if (e.getValue() instanceof NSData data) {
					hashes.put(e.getKey(), data.bytes());
				}
			}
		}
		Integer framework = metadata.get(FRAMEWORK_VERSION_KEY) instanceof NSNumber n ? n.intValue() : null;

		byte[] cache = null;
		if (hasModelCache) {
			try (Statement st = c.createStatement();
					ResultSet rs = st.executeQuery("SELECT Z_CONTENT FROM Z_MODELCACHE")) {
				if (rs.next()) {
					cache = rs.getBytes(1);
				}
			}
		}
		return new NoteStoreModel(uuid, plist, ((NSString) digest).getContent(),
				Collections.unmodifiableMap(hashes), framework, cache);
	}

	private static NSDictionary parseDictionary(byte[] bytes, String what) {
		NSObject o;
		try {
			o = BinaryPropertyListParser.parse(bytes);
		} catch (Exception e) {
			// dd-plist signals malformed input with several checked and
			// unchecked exception types; all mean the same thing here.
			throw new NoteStoreException(what + " is not a binary plist: " + e.getMessage(), e);
		}
		if (!(o instanceof NSDictionary d)) {
			throw new NoteStoreException(what + " is not a dictionary");
		}
		return d;
	}

	/**
	 * Returns the store's UUID ({@code Z_METADATA.Z_UUID}). It identifies
	 * this store file (and its copies), not the schema.
	 *
	 * @return the UUID as stored, such as
	 *         {@code "022E10B1-4CDA-4D27-A5AD-A3CDCEF191EE"}
	 */
	public String storeUuid() {
		return storeUuid;
	}

	/**
	 * Returns the model's digest ({@code NSStoreModelVersionHashesDigest}):
	 * the key to pin a schema version on.
	 *
	 * @return the digest as stored (base64 text, 88 characters in the
	 *         stores seen so far)
	 */
	public String digest() {
		return digest;
	}

	/**
	 * Returns the per-entity version hashes ({@code NSStoreModelVersionHashes}).
	 * Comparing two models' maps tells which entities changed.
	 *
	 * @return entity name to hash, sorted by name; unmodifiable, with
	 *         fresh array copies on each call
	 */
	public Map<String, byte[]> entityVersionHashes() {
		Map<String, byte[]> copy = new TreeMap<>();
		entityVersionHashes.forEach((k, v) -> copy.put(k, v.clone()));
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Returns the build of Core Data that last wrote the store's metadata
	 * ({@code NSPersistenceFrameworkVersion}). It dates the OS, roughly; it
	 * does not identify the schema (one digest has been seen with builds
	 * 1333 and 1338).
	 *
	 * @return the build number, or empty if the metadata has none
	 */
	public OptionalInt frameworkVersion() {
		return frameworkVersion == null ? OptionalInt.empty() : OptionalInt.of(frameworkVersion);
	}

	/**
	 * Returns {@code Z_METADATA.Z_PLIST} exactly as stored: a binary plist,
	 * for whatever this class does not decode.
	 *
	 * @return the bytes
	 */
	public byte[] metadataPlist() {
		return metadataPlist.clone();
	}

	/**
	 * Returns {@code Z_MODELCACHE.Z_CONTENT} exactly as stored (raw
	 * DEFLATE, no zlib or gzip header).
	 *
	 * @return the bytes, or empty if the store has no model cache
	 */
	public Optional<byte[]> modelCache() {
		return Optional.ofNullable(modelCache).map(byte[]::clone);
	}

	/**
	 * Returns the model cache inflated: the {@code NSKeyedArchiver} binary
	 * plist of the {@code NSManagedObjectModel}. It is the same format as
	 * a compiled {@code .mom} file, except that each entity also lists its
	 * inherited properties.
	 *
	 * @return the archive, or empty if the store has no model cache
	 * @throws NoteStoreException if the cache does not inflate
	 */
	public Optional<byte[]> modelArchive() {
		return Optional.ofNullable(modelCache).map(NoteStoreModel::inflateRaw);
	}

	private static byte[] inflateRaw(byte[] compressed) {
		Inflater inflater = new Inflater(true);
		try {
			// With nowrap, zlib may need one byte past the end of the input.
			inflater.setInput(Arrays.copyOf(compressed, compressed.length + 1));
			ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
			byte[] buffer = new byte[64 * 1024];
			while (!inflater.finished()) {
				int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new NoteStoreException("Z_MODELCACHE is truncated");
				}
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		} catch (DataFormatException e) {
			throw new NoteStoreException("Z_MODELCACHE is not raw DEFLATE: " + e.getMessage(), e);
		} finally {
			inflater.end();
		}
	}

	@Override
	public String toString() {
		return "NoteStoreModel[digest=" + digest + ", entities=" + entityVersionHashes.size()
				+ ", framework=" + (frameworkVersion == null ? "?" : frameworkVersion)
				+ ", cache=" + (modelCache == null ? "none" : modelCache.length + " bytes") + "]";
	}
}
