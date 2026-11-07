package com.quizopia.backend.question.application;

import com.quizopia.backend.common.BusinessCodes;
import com.quizopia.backend.exam.application.ExamCommunityPort;
import com.quizopia.backend.question.domain.model.Question;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.domain.model.QuestionOption;
import com.quizopia.backend.question.domain.model.QuestionStatus;
import com.quizopia.backend.question.domain.model.QuestionVersion;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.question.repository.QuestionOptionRepository;
import com.quizopia.backend.question.repository.QuestionRepository;
import com.quizopia.backend.question.repository.QuestionVersionRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link QuestionCommunityPort} implementation (ADR-006 §6, issue
 * #47): clones a published composition snapshot into one fresh
 * owner-scoped bank. Joins the caller's fork transaction — never
 * opens one (§8 atomicity); {@code saveAndFlush} discipline mirrors
 * the exam module's composition writer so every child row exists
 * before its parent is referenced. Source rows are never written.
 */
@Component
public class QuestionCommunityPortImpl implements QuestionCommunityPort {

    /** Column limit of {@code question_banks.name}. */
    private static final int BANK_NAME_MAX = 255;
    private static final String FORK_SUFFIX = " (Fork)";

    private final QuestionBankRepository banks;
    private final QuestionRepository questions;
    private final QuestionVersionRepository versions;
    private final QuestionOptionRepository options;

    public QuestionCommunityPortImpl(QuestionBankRepository banks,
            QuestionRepository questions,
            QuestionVersionRepository versions,
            QuestionOptionRepository options) {
        this.banks = banks;
        this.questions = questions;
        this.versions = versions;
        this.options = options;
    }

    @Override
    public QuestionCloneResult cloneForFork(ExamCommunityPort.PublishedCompositionSnapshot snapshot,
            Long ownerUserId) {
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");

        QuestionBank bank = new QuestionBank(ownerUserId, snapshot.subjectId(),
                BusinessCodes.readableCode("QB", 8,
                        c -> banks.existsByCreatedByUserUserIdAndCodeIgnoreCase(ownerUserId, c)),
                forkBankName(snapshot.title()));
        bank = banks.saveAndFlush(bank);

        Map<Long, ClonedQuestion> bySourceQuestionId = new LinkedHashMap<>();
        for (ExamCommunityPort.SnapshotSection section : snapshot.sections()) {
            for (ExamCommunityPort.SnapshotQuestion question : section.questions()) {
                bySourceQuestionId.put(question.sourceQuestionId(),
                        cloneQuestion(bank.getId(), question, ownerUserId));
            }
        }
        return new QuestionCloneResult(bank.getId(), bySourceQuestionId);
    }

    private ClonedQuestion cloneQuestion(Long bankId, ExamCommunityPort.SnapshotQuestion question,
            Long ownerUserId) {
        Question clone = new Question(bankId,
                BusinessCodes.readableCode("QU", 8,
                        c -> questions.existsByQuestionBankIdAndCodeIgnoreCase(bankId, c)),
                ownerUserId);
        clone.setStatus(QuestionStatus.ACTIVE);
        clone = questions.saveAndFlush(clone);

        QuestionVersion version = new QuestionVersion(clone.getId(), 1,
                question.questionType(), question.content(), question.explanation(),
                question.difficulty(), question.points(),
                deepCopy(question.answerKey()), deepCopy(question.metadata()), ownerUserId);
        QuestionVersion savedVersion = versions.saveAndFlush(version);

        if (!question.options().isEmpty()) {
            options.saveAll(question.options().stream()
                    .map(o -> new QuestionOption(savedVersion.getId(), o.optionKey(), o.content(),
                            o.position(), o.isCorrect()))
                    .toList());
            options.flush();
        }
        return new ClonedQuestion(clone.getId(), savedVersion.getId(), clone.getCode());
    }

    /** {@code "<source title> (Fork)"}, truncated to the column limit. */
    private static String forkBankName(String sourceTitle) {
        String name = (sourceTitle == null || sourceTitle.isBlank() ? "Community fork" : sourceTitle)
                + FORK_SUFFIX;
        return name.length() <= BANK_NAME_MAX ? name : name.substring(0, BANK_NAME_MAX);
    }

    /** Never alias snapshot JSON into a persisted row. */
    private static tools.jackson.databind.JsonNode deepCopy(tools.jackson.databind.JsonNode node) {
        return node != null ? node.deepCopy() : null;
    }
}
