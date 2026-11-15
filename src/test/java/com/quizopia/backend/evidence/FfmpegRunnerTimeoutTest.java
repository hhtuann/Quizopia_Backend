package com.quizopia.backend.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quizopia.backend.evidence.application.EvidenceProperties;
import com.quizopia.backend.evidence.application.FfmpegRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P2-2: the ffmpeg timeout must be ENFORCED even when the process never
 * exits and never closes stderr — a naive {@code readAllBytes()} before
 * {@code waitFor(timeout)} blocks on EOF forever. A hanging fake binary
 * must produce {@code FFMPEG_TIMEOUT} within the configured budget and
 * clean up its temp list file.
 */
class FfmpegRunnerTimeoutTest {

    @TempDir
    Path tempDir;

    private EvidenceProperties properties(Duration timeout) {
        EvidenceProperties props = new EvidenceProperties();
        props.setFfmpegTimeout(timeout);
        return props;
    }

    /**
     * Cross-platform hanging "binary". Windows: JDK 21 refuses to exec .bat/
     * .cmd directly, so the command IS {@code cmd /c <script>} (the runner
     * splits the template on whitespace and appends its ffmpeg args, which
     * the script ignores). Unix: a shebang script that ignores trailing args.
     */
    private String hangingCommand() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            Path script = tempDir.resolve("hang-ffmpeg.cmd");
            Files.writeString(script, "@echo off\r\nping -n 60 127.0.0.1 > nul\r\n");
            return "cmd /c " + script.toAbsolutePath();
        }
        Path script = tempDir.resolve("hang-ffmpeg.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 60\n");
        script.toFile().setExecutable(true);
        return script.toAbsolutePath().toString();
    }

    @Test
    void hangingProcessIsKilledWithinTimeoutBudget() throws Exception {
        EvidenceProperties props = properties(Duration.ofSeconds(2));
        props.setFfmpegCommand(hangingCommand());
        FfmpegRunner runner = new FfmpegRunner(props);

        long startedAt = System.nanoTime();
        FfmpegRunner.Result result = runner.extract(List.of(
                new FfmpegRunner.SegmentInput("http://storage/seg.mp4", 0, 0)));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result).isInstanceOf(FfmpegRunner.Result.Failed.class)
                .satisfies(r -> assertThat(((FfmpegRunner.Result.Failed) r).reason())
                        .isEqualTo("FFMPEG_TIMEOUT"));
        // enforced: 2s timeout + kill/drain slack — nowhere near the 60s hang
        assertThat(elapsedMs).isLessThan(15_000);
        // no temp list files leaked
        try (var stream = Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            long leaked = stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("quizopia-clip-")
                        && Files.isDirectory(p)
                        && listFilePresent(p);
            }).count();
            assertThat(leaked).as("temp list.txt cleaned after timeout").isZero();
        }
    }

    private boolean listFilePresent(Path dir) {
        try (var inner = Files.list(dir)) {
            return inner.anyMatch(p -> p.getFileName().toString().equals("list.txt"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void unavailableBinaryFailsHonestly() {
        EvidenceProperties props = properties(Duration.ofSeconds(2));
        props.setFfmpegCommand(tempDir.resolve("definitely-not-here-ffmpeg").toString());
        FfmpegRunner runner = new FfmpegRunner(props);
        // Either honest outcome is acceptable, never a hang: on Windows a
        // missing binary throws IOException → FfmpegUnavailable; on Linux the
        // shell may report spawn failure the same way.
        assertThatThrownBy(() -> runner.extract(List.of(
                new FfmpegRunner.SegmentInput("http://storage/seg.mp4", 0, 0))))
                .isInstanceOf(FfmpegRunner.FfmpegUnavailable.class);
    }
}
