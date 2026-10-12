package org.rapidcow.archive.bridge.apple.mobile.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.nio.file.NoSuchFileException;   /* ENOENT */
import java.nio.file.NotDirectoryException;  /* ENOTDIR */

/**
 * Top-level iOS backup object.
 */
public
class
	IOSBackup
implements
	AutoCloseable
{
	private IOSManifest manifest;
	private Path rootDir;

	private IOSBackup(IOSManifest mf, Path fn)
	{
		manifest = mf;
		rootDir = fn;
	}

	/**
	 * {@return the backup directory}
	 */
	public Path
	filePath()
	{
		return rootDir;
	}

	/**
	 * Constructs a new iOS backup object rooted
	 * at the given file path.
	 *
	 * @param filePath file path to the backup directory
	 * @throws NotDirectoryException if the file
	 *    path is not a directory
	 */
	public static IOSBackup
	open(Path filePath)
	throws
		IOException,
		SQLException
	{
		if (!Files.exists(filePath)) {
			throw new NoSuchFileException(filePath.toString());
		}
		if (!Files.isDirectory(filePath)) {
			throw new NotDirectoryException(filePath.toString());
		}

		final Path sqlitePath = filePath.resolve("Manifest.db");
		if (!Files.exists(sqlitePath)) {
			throw new NoSuchFileException(sqlitePath.toString());
		}

		IOSManifest manifest = SqliteManifest.open(sqlitePath);
		return new IOSBackup(manifest, filePath);
	}

	@Override
	public void
	close()
	throws
		Exception
	{
		manifest.close();
	}
}
