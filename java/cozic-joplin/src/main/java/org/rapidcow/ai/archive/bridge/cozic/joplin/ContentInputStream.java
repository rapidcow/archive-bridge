package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Objects;

/** An owned file handle limited to the body range discovered during metadata parsing. */
final class ContentInputStream extends InputStream {
    private final RandomAccessFile file;
    private long remaining;
    private boolean closed;

    ContentInputStream(Path path, long start, long size) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r");
        try {
            file.seek(start);
        } catch (IOException error) {
            try {
                file.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
        remaining = size;
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Content stream is closed");
    }

    @Override
    public int read() throws IOException {
        requireOpen();
        if (remaining == 0) return -1;
        int value = file.read();
        if (value == -1) throw new EOFException("Note content was truncated");
        remaining--;
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        requireOpen();
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int count = file.read(bytes, offset, (int) Math.min(length, remaining));
        if (count == -1) throw new EOFException("Note content was truncated");
        remaining -= count;
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        requireOpen();
        long skipped = Math.min(Math.max(0, count), remaining);
        file.seek(file.getFilePointer() + skipped);
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        requireOpen();
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            file.close();
        }
    }
}
