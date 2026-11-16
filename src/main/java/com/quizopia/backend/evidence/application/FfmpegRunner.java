package com.quizopia.backend.evidence.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin ffmpeg ProcessBuilder wrapper for clip extraction (P4-D #93,
 * ADR-009 §2.5): stream-copy concat over presigned input URLs. The binary
 * path is configuration; environments without ffmpeg fail with
 * {@link FfmpegUnavailable} — callers translate that to an honest
 * {@code FAILED/FFMPEG_UNAVAILABLE} evidence row, never a silent skip.
 *
 * <p><b>Process discipline (gate review P2-2):</b> stderr is drained
 * asynchronously on a bounded daemon thread — {@code readAllBytes()} before
 * {@code waitFor} would block on EOF forever for a hung process. The
 * timeout is enforced by {@code waitFor(timeout)}; a timeout kills the
 * process tree and still cleans up every temp file.
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);
    private static final int STDERR_CAP = 8 * 1024;

    /** One concat input: a presigned URL plus in/out points in seconds. */
    public record SegmentInput(String url, double inSeconds, double outSeconds) {
    }

    /** Normalized exit outcome. */
    public sealed interface Result {
        record Ok(Path output, long bytes) implements Result {
        }

        record Failed(String reason) implements Result {
        }
    }

    /** Raised when the ffmpeg binary cannot be executed at all. */
    public static class FfmpegUnavailable extends RuntimeException {
        public FfmpegUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final EvidenceProperties properties;

    public FfmpegRunner(EvidenceProperties properties) {
        this.properties = properties;
    }

    /**
     * Concatenates the segments (stream copy, faststart MP4) into a fresh
     * temp file. Caller owns (and must delete) the output path.
     */
    public Result extract(List<SegmentInput> segments) {
        if (segments.isEmpty()) {
            return new Result.Failed("NO_INPUTS");
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("quizopia-clip-");
        } catch (IOException e) {
            return new Result.Failed("FFMPEG_IO_ERROR"); // nothing created — nothing to clean
        }
        Path output = workDir.resolve("clip.mp4");
        try {
            Path listFile = workDir.resolve("list.txt");
            StringBuilder list = new StringBuilder();
            for (SegmentInput segment : segments) {
                list.append("file '").append(segment.url().replace("'", "%27")).append("'\n");
                if (segment.inSeconds() > 0) {
                    list.append("inpoint ").append(String.format(Locale.ROOT, "%.3f",
                            segment.inSeconds())).append('\n');
                }
                if (segment.outSeconds() > 0) {
                    list.append("outpoint ").append(String.format(Locale.ROOT, "%.3f",
                            segment.outSeconds())).append('\n');
                }
            }
            Files.writeString(listFile, list.toString(), StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(List.of(
                    properties.getFfmpegCommand().trim().split("\\s+")));
            command.addAll(List.of("-nostdin", "-loglevel", "error", "-y",
                    // concat demuxer whitelists file protocols by default;
                    // presigned HTTP(S) inputs must be allowed explicitly.
                    "-protocol_whitelist", "file,http,https,tcp,tls,crypto,data",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-c", "copy", "-movflags", "+faststart", output.toString()));
            Process process;
            try {
                process = new ProcessBuilder(command).start();
            } catch (IOException e) {
                throw new FfmpegUnavailable("ffmpeg binary unavailable", e);
            }
            // Bounded async stderr drain: NEVER block on EOF before waitFor.
            Drain drain = new Drain(process);
            boolean finished;
            try {
                finished = process.waitFor(
                        properties.getFfmpegTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                drain.joinAndRead();
                return new Result.Failed("FFMPEG_INTERRUPTED");
            }
            if (!finished) {
                // kill FIRST — joining the drain before the kill would burn
                // its full budget waiting for a process that never exits.
                process.destroyForcibly();
                String timeoutStderr = drain.joinAndRead();
                log.warn("ffmpeg timeout after {} — killed, stderr: {}",
                        properties.getFfmpegTimeout(), tail(timeoutStderr));
                cleanup(workDir.resolve("clip.mp4"));
                return new Result.Failed("FFMPEG_TIMEOUT");
            }
            String stderr = drain.joinAndRead();
            if (process.exitValue() != 0) {
                log.warn("ffmpeg exit {}: {}", process.exitValue(), tail(stderr));
                cleanup(output);
                return new Result.Failed("FFMPEG_EXIT_" + process.exitValue());
            }
            long bytes = Files.size(output);
            if (bytes <= 0) {
                cleanup(output);
                return new Result.Failed("FFMPEG_EMPTY_OUTPUT");
            }
            return new Result.Ok(output, bytes);
        } catch (FfmpegUnavailable e) {
            throw e;
        } catch (IOException e) {
            cleanup(workDir.resolve("clip.mp4"));
            return new Result.Failed("FFMPEG_IO_ERROR");
        } finally {
            if (workDir != null && Files.exists(workDir.resolve("list.txt"))) {
                try {
                    Files.deleteIfExists(workDir.resolve("list.txt"));
                } catch (IOException e) {
                    log.debug("temp list cleanup failed: {}", e.getMessage());
                }
            }
            // output path ownership: caller deletes after upload
        }
    }

    /** Bounded stderr collector: the daemon thread fills {@code buffer}. */
    private static final class Drain {
        final byte[] buffer = new byte[STDERR_CAP];
        final Thread thread;

        Drain(Process process) {
            this.thread = new Thread(() -> {
                try (var err = process.getErrorStream()) {
                    int total = 0;
                    int read;
                    while (total < buffer.length
                            && (read = err.read(buffer, total, buffer.length - total)) > 0) {
                        total += read;
                    }
                    // swallow the rest so a chatty ffmpeg cannot block on a full pipe
                    while (err.read() != -1) {
                        // discard
                    }
                } catch (IOException e) {
                    // process died — drain ends
                }
            }, "ffmpeg-stderr-drain");
            thread.setDaemon(true);
            thread.start();
        }

        String joinAndRead() {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new String(buffer, StandardCharsets.UTF_8).trim();
        }
    }

    /** Deletes an extract() output (or any temp path), ignoring failures. */
    public void cleanup(Path output) {
        if (output == null) {
            return;
        }
        try {
            Files.deleteIfExists(output);
            Path parent = output.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith("quizopia-clip-")) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            log.debug("clip temp cleanup failed: {}", e.getMessage());
        }
    }

    private static String tail(String stderr) {
        return stderr == null ? "" : stderr.substring(Math.max(0, stderr.length() - 500));
    }
}
