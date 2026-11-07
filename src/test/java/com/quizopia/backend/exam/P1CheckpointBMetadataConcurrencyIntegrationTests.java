package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.dto.UpdateExamMetadataRequest;
import com.quizopia.backend.exam.exception.ExamException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent committed transactions exercise the actual PostgreSQL row lock. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class P1CheckpointBMetadataConcurrencyIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExamService service;

    @Test
    void sameTokenAllowsExactlyOneCommittedWriter() throws Exception {
        long user = jdbc.queryForObject("INSERT INTO users(username,email,password_hash,display_name) VALUES ('p1bc','p1bc@test.com','h','B') RETURNING id", Long.class);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='TEACHER'", user);
        long exam = jdbc.queryForObject("INSERT INTO exams(created_by_user_id,code,title) VALUES (?,?,'P1BC','Original') RETURNING id", Long.class, user);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> writer = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    service.updateExamMetadata(user, exam, new UpdateExamMetadataRequest(0, "Updated", null));
                    return "OK";
                } catch (ExamException ex) {
                    return ex.getErrorCode().name();
                }
            };
            var first = pool.submit(writer);
            var second = pool.submit(writer);
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "EXAM_CONCURRENT_MODIFICATION");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM exams WHERE id=?", Integer.class, exam)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM exams WHERE id=?", String.class, exam)).isEqualTo("Updated");
    }
}
