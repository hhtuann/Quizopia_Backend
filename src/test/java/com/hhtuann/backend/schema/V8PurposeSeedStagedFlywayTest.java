package com.hhtuann.backend.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standalone, non-Spring test that proves the V8 purpose seed behaviour by staging
 * Flyway on a dedicated PostgreSQL container: migrate to V7, insert a school, then
 * continue to V8 and assert the seed created exactly the 4 default purposes for that
 * school (and is idempotent on re-run).
 *
 * <p><b>Why a separate class (helper test, under {@code schema/}, per A2 diff-guard
 * allowance):</b> {@code V8ExamSchemaIntegrationTests} is {@code @SpringBootTest} with a
 * class-level {@code @Transactional} + {@code @BeforeEach} fixture. A staged-Flyway test
 * there would need {@code @Transactional(NOT_SUPPORTED)}, which would let the
 * {@code @BeforeEach} fixture commit into the shared Spring container (no rollback),
 * polluting it and breaking later tests via the schools case-insensitive unique code.
 * Running the staged scenario on a fully isolated container here avoids that hazard and
 * keeps the Spring-managed container clean. No application context is started.
 */
@SuppressWarnings({"resource"})
class V8PurposeSeedStagedFlywayTest {

    @Test
    void seedsFourPurposesForPreExistingSchoolAndIsIdempotent() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("v8_seed").withUsername("t").withPassword("t")) {
            pg.start();
            String url = pg.getJdbcUrl();
            String user = pg.getUsername();
            String pass = pg.getPassword();

            // 1. Migrate clean DB up to V5 (no exam tables yet — exam schema is V6).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("5"))
                    .load().migrate();

            // 2. Insert a school BEFORE V6 runs.
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                st.execute("INSERT INTO schools (code, name) VALUES ('SEED-SCH','Seed School')");
            }

            // 3. Continue migration (V6 creates exam tables + seeds purposes for existing schools).
            Flyway.configure().dataSource(url, user, pass)
                    .locations("classpath:db/migration")
                    .load().migrate();

            // 4. Assert exactly 4 purposes with correct code/title/position.
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT p.code, p.title, p.position FROM exam_purposes p "
                                 + "JOIN schools s ON s.id = p.school_id WHERE s.code='SEED-SCH' ORDER BY p.position")) {
                Map<String, String> titleByCode = new HashMap<>();
                Map<String, Integer> posByCode = new HashMap<>();
                int count = 0;
                while (rs.next()) {
                    titleByCode.put(rs.getString("code"), rs.getString("title"));
                    posByCode.put(rs.getString("code"), rs.getInt("position"));
                    count++;
                }
                assertThat(count).isEqualTo(4);
                assertThat(titleByCode).containsEntry("MIDTERM", "Giữa kỳ");
                assertThat(posByCode.get("MIDTERM")).isEqualTo(0);
                assertThat(titleByCode).containsEntry("FINAL", "Cuối kỳ");
                assertThat(posByCode.get("FINAL")).isEqualTo(1);
                assertThat(titleByCode).containsEntry("QUIZ", "Bài kiểm tra");
                assertThat(posByCode.get("QUIZ")).isEqualTo(2);
                assertThat(titleByCode).containsEntry("PRACTICE", "Luyện tập");
                assertThat(posByCode.get("PRACTICE")).isEqualTo(3);
            }

            // 5. Idempotency: re-run the exact seed SQL — must add 0 rows, count stays 4.
            String seedSql = "INSERT INTO exam_purposes (school_id, code, title, position) "
                    + "SELECT s.id, v.code, v.title, v.position FROM schools s "
                    + "CROSS JOIN (VALUES ('MIDTERM','Giữa kỳ',0),('FINAL','Cuối kỳ',1),"
                    + "('QUIZ','Bài kiểm tra',2),('PRACTICE','Luyện tập',3)) AS v(code,title,position) "
                    + "WHERE NOT EXISTS (SELECT 1 FROM exam_purposes p "
                    + "WHERE p.school_id=s.id AND LOWER(p.code)=LOWER(v.code))";
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                int affected = st.executeUpdate(seedSql);
                assertThat(affected).isZero();
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM exam_purposes p "
                                + "JOIN schools s ON s.id=p.school_id WHERE s.code='SEED-SCH'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(4);
                }
            }
        }
    }

    /** A clean DB (no schools) migrates to V8 with 0 seeded purposes and no failure. */
    @Test
    void cleanDbMigratesV8WithZeroPurposes() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("v8_clean").withUsername("t").withPassword("t")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration")
                    .load().migrate();

            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM exam_purposes")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
