package com.quizopia.backend.question;

import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.domain.model.*;
import com.quizopia.backend.question.repository.*;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resolves code-review finding M1: proves the {@link QuestionVersion} entity
 * can persist and read back a JsonNode {@code answer_key} with correct JSON
 * types (number vs string) via Hibernate's Jackson 3 JSON integration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class QuestionVersionJsonNodeIntegrationTests {

    @Autowired private QuestionVersionRepository versionRepo;
    @Autowired private QuestionRepository questionRepo;
    @Autowired private QuestionBankRepository bankRepo;
    @Autowired private TeacherProfileRepository teacherRepo;
    @Autowired private SubjectRepository subjectRepo;
    @Autowired private GradeLevelRepository gradeLevelRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EntityManager entityManager;

    @Test
    void persistNumericFillAnswerKey_preservesJsonTypesOnReadBack() {
        long[] ids = seedPrerequisites();
        long bankId = ids[0];
        long userId = ids[2];

        Question question = questionRepo.saveAndFlush(
                new Question(bankId, "Q-JSON-1", userId));

        ObjectNode answerKey = JsonNodeFactory.instance.objectNode();
        answerKey.put("expectedAnswer", "2.50");        // JSON string — the only key (V12)

        QuestionVersion version = new QuestionVersion(
                question.getId(), 1, QuestionType.NUMERIC_FILL,
                "What is 2.50?", null, QuestionDifficulty.MEDIUM,
                new BigDecimal("1.00"), answerKey, null, userId);

        versionRepo.saveAndFlush(version);
        entityManager.clear();

        QuestionVersion readBack = versionRepo.findById(version.getId()).orElseThrow();

        JsonNode ak = readBack.getAnswerKey();
        assertThat(ak).isNotNull();
        assertThat(ak.get("expectedAnswer").isString()).isTrue();
        assertThat(ak.get("expectedAnswer").asString()).isEqualTo("2.50");
        assertThat(readBack.getMetadata().isObject()).isTrue();
    }

    @Test
    void persistExpectedAnswerWrongLength_rejectedByDbCheck() {
        long[] ids = seedPrerequisites();
        long bankId = ids[0];
        long userId = ids[2];

        Question question = questionRepo.saveAndFlush(
                new Question(bankId, "Q-JSON-BAD", userId));

        ObjectNode badAnswerKey = JsonNodeFactory.instance.objectNode();
        badAnswerKey.put("expectedAnswer", "1.2"); // 3 chars, not 4!

        QuestionVersion version = new QuestionVersion(
                question.getId(), 1, QuestionType.NUMERIC_FILL,
                "bad", null, QuestionDifficulty.MEDIUM,
                new BigDecimal("1.00"), badAnswerKey, null, userId);

        assertThatThrownBy(() -> versionRepo.saveAndFlush(version))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistChoiceTypeWithNullAnswerKey_succeeds() {
        long[] ids = seedPrerequisites();
        long bankId = ids[0];
        long userId = ids[2];

        Question question = questionRepo.saveAndFlush(
                new Question(bankId, "Q-JSON-CHOICE", userId));

        QuestionVersion version = new QuestionVersion(
                question.getId(), 1, QuestionType.SINGLE_CHOICE,
                "Pick one", null, QuestionDifficulty.EASY,
                new BigDecimal("2.00"), null, null, userId);

        versionRepo.saveAndFlush(version);
        entityManager.clear();

        QuestionVersion readBack = versionRepo.findById(version.getId()).orElseThrow();
        assertThat(readBack.getAnswerKey()).isNull();
        assertThat(readBack.getMetadata().isObject()).isTrue();
    }

    private long[] seedPrerequisites() {
        User user = userRepo.saveAndFlush(
                new User("json-test-user", "json@test.com", "hash", "JSON Test"));
        School school = schoolRepo.saveAndFlush(new School("JSON-SCH", "JSON School"));
        GradeLevel gl = gradeLevelRepo.saveAndFlush(
                new GradeLevel(school.getId(), "GL", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(
                new Subject(school.getId(), gl.getId(), "SUB", "Subject"));
        TeacherProfile tp = teacherRepo.saveAndFlush(
                new TeacherProfile(user.getId(), school.getId(), "TC-JSON"));
        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                school.getId(), subject.getId(), tp.getId(), "JSON-BANK", "JSON Bank"));
        return new long[]{bank.getId(), tp.getId(), user.getId()};
    }
}
