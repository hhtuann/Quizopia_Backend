package com.quizopia.backend.evidence.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin ffmpeg ProcessBuilder wrapper for clip extraction (P4-D #93,
 * ADR-009 §2.5): stream-copy concat over presigned input URLs. The binary
 * path is configuration; environments without ffmpeg fail with
 * {@link FfmpegUnavailable} — callers translate that to an honest
 * {@code FAILED/FFMPEG_UNAVAILABLE} evidence row, never a silent skip.
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

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
            Path listFile = workDir.resolve("list.txt");
            Path output = workDir.resolve("clip.mp4");
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
            String stderr;
            try (var err = process.getErrorStream()) {
                stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(
                    properties.getFfmpegTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result.Failed("FFMPEG_TIMEOUT");
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg exit {}: {}", process.exitValue(), tail(stderr));
                return new Result.Failed("FFMPEG_EXIT_" + process.exitValue());
            }
            long bytes = Files.size(output);
            if (bytes <= 0) {
                return new Result.Failed("FFMPEG_EMPTY_OUTPUT");
            }
            return new Result.Ok(output, bytes);
        } catch (FfmpegUnavailable e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Failed("FFMPEG_INTERRUPTED");
        } catch (IOException e) {
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
