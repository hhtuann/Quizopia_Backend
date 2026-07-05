package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.ExcelExportService;
import com.hhtuann.backend.attempt.application.SessionResultService;
import com.hhtuann.backend.attempt.application.SessionStatisticsService;
import com.hhtuann.backend.attempt.exception.AttemptErrorCode;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 8 R4 Excel SXSSF lifecycle evidence (FINDING 1D). Substitutes a tracking workbook via the
 * protected {@link ExcelExportService#createWorkbook()} seam (overridden in a {@code @Primary} subclass)
 * and proves that {@code dispose()} AND {@code close()} are invoked by the {@code finally} block on
 * BOTH the success path and a forced-failure path (write throws), so no SXSSF temp resource leaks.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        ExcelExportCleanupIntegrationTests.CleanupTestConfig.class})
@Transactional
class ExcelExportCleanupIntegrationTests {

    /** Test subclass of ExcelExportService that returns a controllable tracking workbook. */
    static class TestExcelExportService extends ExcelExportService {
        /** Static ThreadLocal so the override (which runs on the @Transactional CGLIB target instance,
         *  not the injected proxy) reads the workbook the test set. */
        static final ThreadLocal<SXSSFWorkbook> NEXT = new ThreadLocal<>();

        TestExcelExportService(SessionResultService srs, SessionStatisticsService sss) {
            super(srs, sss);
        }

        @Override
        protected SXSSFWorkbook createWorkbook() {
            SXSSFWorkbook w = NEXT.get();
            if (w != null) {
                NEXT.remove();
                return w;
            }
            return super.createWorkbook();
        }
    }

    @TestConfiguration
    static class CleanupTestConfig {
        @Bean
        @Primary
        TestExcelExportService testExcelExportService(SessionResultService srs, SessionStatisticsService sss) {
            return new TestExcelExportService(srs, sss);
        }
    }

    /** Tracking SXSSF workbook: records dispose/close and can force a write failure. */
    static final class TrackingWorkbook extends SXSSFWorkbook {
        volatile boolean disposed;
        volatile boolean closed;
        final boolean failOnWrite;

        TrackingWorkbook(boolean failOnWrite) {
            super(100);
            this.failOnWrite = failOnWrite;
        }

        @Override
        public boolean dispose() {
            disposed = true;
            return super.dispose();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        @Override
        public void write(OutputStream stream) throws IOException {
            if (failOnWrite) throw new IOException("forced SXSSF write failure");
            super.write(stream);
        }
    }

    @Autowired private TestExcelExportService exportService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    @Test
    void successPathDisposesAndClosesWorkbook() {
        long[] fix = ownSessionFixture("OK");
        TrackingWorkbook wb = new TrackingWorkbook(false);
        TestExcelExportService.NEXT.set(wb);

        byte[] bytes = exportService.export(fix[0], "TEACHER", fix[1]);

        assertThat(bytes).isNotEmpty();
        assertThat(wb.disposed).as("dispose() must run on success").isTrue();
        assertThat(wb.closed).as("close() must run on success").isTrue();
    }

    @Test
    void forcedFailureStillDisposesAndClosesAndWrapsAsExportError() {
        long[] fix = ownSessionFixture("FAIL");
        TrackingWorkbook wb = new TrackingWorkbook(true);
        TestExcelExportService.NEXT.set(wb);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> exportService.export(fix[0], "TEACHER", fix[1]));

        assertThat(thrown).isInstanceOf(AttemptException.class);
        assertThat(((AttemptException) thrown).getErrorCode())
                .isEqualTo(AttemptErrorCode.EXPORT_FAILED_INTERNAL);

        assertThat(wb.disposed).as("dispose() must run even when write() throws").isTrue();
        assertThat(wb.closed).as("close() must run even when write() throws").isTrue();
    }

    /** Builds a teacher-owned OPEN session (no students required — empty results/statistics are valid). Returns [teacherUserId, sessionId]. */
    private long[] ownSessionFixture(String tag) {
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        clock.setInstant(now);
        long teacher = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x" + tag + "','x" + tag + "@t.com','h','T')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacher + "," + tr + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','S')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacher + "," + school + ",'TC" + tag + "')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacher + ")");
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S" + tag + "','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + teacher + ",'" + now.minusSeconds(3600) + "')");
        return new long[]{teacher, session};
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
