package org.rapidcow.archive.bridge.apple.mobile.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;   /* ENOENT */
import java.nio.file.NotDirectoryException;  /* ENOTDIR */

/**
 * Top-level iOS backup object.
 */
public
class
	IOSBackup
{
	private Path rootDir;

	/**
	 * Constructs a new iOS backup object rooted
	 * at the given file path.
	 *
	 * @param filePath file path of the backup directory
	 * @throws NotDirectoryException if the file
	 *    path is not a directory
	 */
	public
	IOSBackup(Path filePath)
	throws
		IOException
	{
		if (!Files.exists(filePath)) {
			throw new NoSuchFileException(rootDir.toString());
		}
		if (!Files.isDirectory(filePath)) {
			throw new NotDirectoryException(rootDir.toString());
		}
		rootDir = filePath;
	}

	/**
	 * {@return the backup directory}
	 */
	public Path
	rootDirectory()
	{
		return rootDir;
	}
}
