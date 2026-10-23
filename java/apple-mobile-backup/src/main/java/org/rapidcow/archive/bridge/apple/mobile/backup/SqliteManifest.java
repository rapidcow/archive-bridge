package org.rapidcow.archive.bridge.apple.mobile.backup;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Path;

public
class
	SqliteManifest
implements
	IOSManifest
{
	private Path filePath;
	private Connection connection;

	private
	SqliteManifest(Path fn, Connection cx)
	{
		filePath = fn;
		connection = cx;
	}

	@Override
	public Path
	getFilePath()
	{
		return filePath;
	}

	public static
	SqliteManifest
	open(Path filePath)
	throws
		SQLException
	{
		String fileURI = filePath.toUri().toASCIIString();
		Connection connection = DriverManager.getConnection(
			"jdbc:sqlite:"
			+ fileURI + "?mode=ro");
		return new SqliteManifest(filePath, connection);
	}

	@Override
	public void
	close()
	throws
		SQLException
	{
		if (connection != null) {
			connection.close();
		}
	}
}
