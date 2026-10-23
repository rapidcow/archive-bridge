package org.rapidcow.ai.archive.script.apple.notes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Pipes the existing Perl decoder directly into libexpr, without an intermediate export. */
final class DrawingDecoder {
    private DrawingDecoder() {}

    static List<Stroke> decode(Path database, long pk) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("apple-notes-drawing-");
        Process process = null;
        var timeout = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "drawing-decoder-timeout");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (String name : List.of("apple-notes-drawing-decode.pl", "apple-notes-drawing-2.proto")) {
                try (var resource = DrawingDecoder.class.getResourceAsStream("/drawing-decoder/" + name)) {
                    if (resource == null) throw new IOException("Missing bundled decoder: " + name);
                    Files.copy(resource, directory.resolve(name));
                }
            }
            process = new ProcessBuilder("perl", directory.resolve("apple-notes-drawing-decode.pl").toString(),
                    "--db", database.toAbsolutePath().toString(), "--immutable", "--pk", Long.toString(pk),
                    "--sexp", "--sort").start();
            Process child = process;
            child.getOutputStream().close();
            var deadline = timeout.schedule(child::destroyForcibly, 2, TimeUnit.MINUTES);
            // Drain stderr independently so a diagnostic cannot fill the pipe and deadlock stdout.
            var diagnostics = new java.io.ByteArrayOutputStream();
            var errors = new Thread(() -> {
                try (var input = child.getErrorStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        int keep = Math.min(n, 16384 - diagnostics.size());
                        if (keep > 0) diagnostics.write(buffer, 0, keep);
                    }
                } catch (IOException ignored) { /* Process cleanup can close this stream. */ }
            }, "drawing-decoder-stderr");
            errors.setDaemon(true);
            errors.start();
            List<Stroke> strokes = null;
            Exception parseError = null;
            try { strokes = DrawingPreview.read(child.getInputStream()); }
            catch (IOException | RuntimeException e) { parseError = e; child.destroy(); }
            int code = child.waitFor();
            errors.join();
            if (deadline.isDone()) throw new IOException("Drawing decoder timed out after two minutes");
            deadline.cancel(false);
            if (code != 0) throw new IOException("Perl drawing decoder exited with status " + code + ":\n"
                    + diagnostics.toString(StandardCharsets.UTF_8).strip(), parseError);
            if (parseError != null) throw new IOException("Cannot parse drawing decoder output", parseError);
            return strokes;
        } finally {
            if (process != null) process.destroyForcibly();
            timeout.shutdownNow();
            Files.deleteIfExists(directory.resolve("apple-notes-drawing-decode.pl"));
            Files.deleteIfExists(directory.resolve("apple-notes-drawing-2.proto"));
            Files.deleteIfExists(directory);
        }
    }
}
