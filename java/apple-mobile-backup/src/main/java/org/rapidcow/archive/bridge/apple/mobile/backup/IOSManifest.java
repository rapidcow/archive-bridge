package org.rapidcow.archive.bridge.apple.mobile.backup;

import java.nio.file.Path;

public
interface
	IOSManifest
extends
	AutoCloseable
{
	Path
	filePath();
}
