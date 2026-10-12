package org.rapidcow.archive.script.apple.mobile.backup;

import java.nio.file.Path;
import org.rapidcow.archive.bridge.apple.mobile.backup.IOSBackup;

public final
class
	IOSBackupExam
{
	public static void
	main(String[] args)
	throws
		Exception
	{
		int argc = args.length;
		if (argc < 1) {
			System.err.println("usage: IOSBackupExam ROOT");
			System.exit(1);
		}

		String file = args[0];
		Path filePath = Path.of(file);
		try (IOSBackup bak = IOSBackup.open(filePath)) {
			System.out.println("open " + file + " ok");
		}
	}
}
