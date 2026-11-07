package com.quizopia.backend.exam;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PostgresTestContainerConfiguration.class)
class P1CheckpointBMetadataIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        jdbc.update("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (8200001,'p1b','p1b@test.com','h','B'),(8200002,'p1b-other','p1b-other@test.com','h','Other')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u CROSS JOIN roles r WHERE u.id IN (8200001,8200002) AND r.code='TEACHER'");
        jdbc.update("INSERT INTO exams(id,created_by_user_id,code,title,description,instructions,duration_minutes) VALUES (8200020,8200001,'P1B','Original','Description','Pinned',45)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED"})
    void updatesOnlyMetadataAndRejectsStaleReplay(String state) throws Exception {
        if (state.equals("PUBLISHED")) jdbc.update("UPDATE exams SET status='PUBLISHED',published_at=now(),total_points=3 WHERE id=8200020");
        var pinned = jdbc.queryForMap("SELECT to_jsonb(e)-'title'-'description'-'version'-'updated_at' AS value FROM exams e WHERE id=8200020");
        update(8200001, "{\"expectedExamVersion\":0,\"title\":\"Updated\",\"description\":null}", 200);
        assertThat(jdbc.queryForObject("SELECT title FROM exams WHERE id=8200020", String.class)).isEqualTo("Updated");
        assertThat(jdbc.queryForObject("SELECT description FROM exams WHERE id=8200020", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM exams WHERE id=8200020", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT to_jsonb(e)-'title'-'description'-'version'-'updated_at' AS value FROM exams e WHERE id=8200020")).isEqualTo(pinned);
        update(8200001, "{\"expectedExamVersion\":0,\"title\":\"Stale\"}", 409);
    }

    @Test void archivedIsReadOnly() throws Exception {
        jdbc.update("UPDATE exams SET status='ARCHIVED',published_at=now(),total_points=3 WHERE id=8200020");
        update(8200001, "{\"expectedExamVersion\":0,\"title\":\"Denied\"}", 409);
    }

    @Test void creatorCanWriteAndOutsiderCannot() throws Exception {
        // Only the creator may manage the exam; any other user gets 403.
        update(8200002, "{\"expectedExamVersion\":0,\"title\":\"Denied\"}", 403);
        update(8200001, "{\"expectedExamVersion\":0,\"title\":\"Creator\"}", 200);
        assertThat(jdbc.queryForObject("SELECT title FROM exams WHERE id=8200020", String.class)).isEqualTo("Creator");
    }

    @Test void identicalReplacementStillConsumesToken() throws Exception {
        update(8200001, "{\"expectedExamVersion\":0,\"title\":\"Original\",\"description\":\"Description\"}", 200);
        assertThat(jdbc.queryForObject("SELECT version FROM exams WHERE id=8200020", Integer.class)).isEqualTo(1);
        update(8200001, "{\"expectedExamVersion\":0,\"title\":\"Original\",\"description\":\"Description\"}", 409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":\"Missing token\"}", "{\"title\":\" \",\"expectedExamVersion\":0}", "{\"title\":\"X\",\"expectedExamVersion\":-1}", "{\"title\":\"X\",\"expectedExamVersion\":0,\"instructions\":\"Changed\"}", "{\"title\":\"X\",\"expectedExamVersion\":0,\"tfMatrixScoring\":{}}", "{\"title\":\"X\",\"expectedExamVersion\":0,\"workspaceId\":1}"})
    void invalidOrUnsupportedFieldsDoNotSucceed(String body) throws Exception {
        update(8200001, body, 400);
        assertThat(jdbc.queryForObject("SELECT title FROM exams WHERE id=8200020", String.class)).isEqualTo("Original");
    }

    private void update(long user, String body, int expected) throws Exception {
        mvc.perform(put("/api/exams/8200020").with(jwt().jwt(j -> j.subject(Long.toString(user))))
                .contentType("application/json").content(body)).andExpect(status().is(expected));
    }
}
