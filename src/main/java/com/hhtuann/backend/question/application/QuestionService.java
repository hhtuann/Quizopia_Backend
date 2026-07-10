package com.hhtuann.backend.question.application;

import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.identity.repository.RolePermissionRepository;
import com.hhtuann.backend.identity.repository.UserRoleRepository;
import com.hhtuann.backend.question.domain.model.*;
import com.hhtuann.backend.question.dto.QuestionDetailResponse;
import com.hhtuann.backend.question.dto.QuestionDetailResponse.OptionView;
import com.hhtuann.backend.question.dto.UpdateQuestionRequest;
import com.hhtuann.backend.question.dto.UpdateQuestionRequest.OptionPart;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import com.hhtuann.backend.question.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Application service for single-question operations: GET detail + PUT edit.
 * Editing updates the latest {@link QuestionVersion} in place (content,
 * explanation, difficulty, answerKey) and replaces its {@link QuestionOption}s.
 * The question TYPE is immutable.
 */
@Service
public class QuestionService {

    private static final String NUMERIC_REGEX = "^-?[0-9]+([.][0-9]+)?$";

    private final QuestionRepository questionRepository;
    private final QuestionBankRepository questionBankRepository;
    private final QuestionVersionRepository versionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final Clock clock;

    public QuestionService(QuestionRepository questionRepository,
                           QuestionBankRepository questionBankRepository,
                           QuestionVersionRepository versionRepository,
                           QuestionOptionRepository optionRepository,
                           TeacherProfileRepository teacherProfileRepository,
                           UserRoleRepository userRoleRepository,
                           RolePermissionRepository rolePermissionRepository,
                           Clock clock) {
        this.questionRepository = questionRepository;
        this.questionBankRepository = questionBankRepository;
        this.versionRepository = versionRepository;
        this.optionRepository = optionRepository;
        this.teacherProfileRepository = teacherProfileRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuestionDetailResponse getQuestionDetail(Long userId, Long questionId) {
        Question question = requireOwnedQuestion(userId, questionId);
        return toDetail(question);
    }

    @Transactional
    public QuestionDetailResponse editQuestion(Long userId, Long questionId, UpdateQuestionRequest req) {
        Question question = requireOwnedQuestion(userId, questionId);
        QuestionVersion version = versionRepository
                .findByQuestionIdAndVersionNumber(question.getId(), question.getCurrentVersionNumber())
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_NOT_FOUND));

        QuestionType type = version.getQuestionType();
        validateRequestShape(type, req);

        // Update scalar fields on the latest version.
        version.setContent(req.content().trim());
        version.setExplanation(req.explanation() != null ? req.explanation().trim() : null);
        version.setDifficulty(req.difficulty());

        // Replace options for choice/TF; update answerKey for NUMERIC.
        if (type == QuestionType.NUMERIC_FILL) {
            var key = JsonNodeFactory.instance.objectNode();
            key.put("expectedAnswer", req.expectedAnswer().trim());
            version.setAnswerKey(key);
        } else {
            version.setAnswerKey(null);
            replaceOptions(version.getId(), req.options());
        }

        versionRepository.saveAndFlush(version);
        return toDetail(question);
    }

    // ============================================================
    // Validation (mirrors ExamService.validatePublishedQuestionType shape)
    // ============================================================

    private void validateRequestShape(QuestionType type, UpdateQuestionRequest req) {
        List<OptionPart> opts = req.options();
        switch (type) {
            case SINGLE_CHOICE -> {
                if (opts == null || opts.size() != 4
                        || opts.stream().filter(o -> Boolean.TRUE.equals(o.isCorrect())).count() != 1) {
                    throw new QuestionException(QuestionErrorCode.QUESTION_VALIDATION_ERROR,
                            "SINGLE_CHOICE requires exactly 4 options with exactly 1 correct");
                }
            }
            case MULTIPLE_CHOICE -> {
                if (opts == null || opts.size() != 4
                        || opts.stream().filter(o -> Boolean.TRUE.equals(o.isCorrect())).count() < 2) {
                    throw new QuestionException(QuestionErrorCode.QUESTION_VALIDATION_ERROR,
                            "MULTIPLE_CHOICE requires exactly 4 options with at least 2 correct");
                }
            }
            case TRUE_FALSE_MATRIX -> {
                if (opts == null || opts.size() != 4) {
                    throw new QuestionException(QuestionErrorCode.QUESTION_VALIDATION_ERROR,
                            "TRUE_FALSE_MATRIX requires exactly 4 options");
                }
            }
            case NUMERIC_FILL -> {
                if (req.expectedAnswer() == null || req.expectedAnswer().isBlank()
                        || req.expectedAnswer().trim().length() != 4
                        || !req.expectedAnswer().trim().matches(NUMERIC_REGEX)) {
                    throw new QuestionException(QuestionErrorCode.QUESTION_VALIDATION_ERROR,
                            "NUMERIC_FILL requires a 4-character numeric expectedAnswer");
                }
            }
        }
    }

    private void replaceOptions(Long versionId, List<OptionPart> newOptions) {
        // Delete existing options for this version.
        List<QuestionOption> existing = optionRepository.findByQuestionVersionId(versionId);
        if (!existing.isEmpty()) {
            optionRepository.deleteAll(existing);
            optionRepository.flush();
        }
        // Insert new options (position = index).
        for (int i = 0; i < newOptions.size(); i++) {
            OptionPart p = newOptions.get(i);
            optionRepository.saveAndFlush(new QuestionOption(
                    versionId, p.optionKey(), p.content(), i, Boolean.TRUE.equals(p.isCorrect())));
        }
    }

    // ============================================================
    // Response mapping
    // ============================================================

    private QuestionDetailResponse toDetail(Question question) {
        QuestionVersion version = versionRepository
                .findByQuestionIdAndVersionNumber(question.getId(), question.getCurrentVersionNumber())
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_NOT_FOUND));
        List<OptionView> options = optionRepository.findByQuestionVersionId(version.getId()).stream()
                .sorted(Comparator.comparingInt(QuestionOption::getPosition))
                .map(o -> new OptionView(o.getOptionKey(), o.getContent(), o.getIsCorrect(), o.getPosition()))
                .toList();
        return new QuestionDetailResponse(
                question.getId(), question.getCode(), version.getQuestionType(),
                version.getContent(), version.getDifficulty(), version.getExplanation(),
                version.getDefaultPoints(), question.getCurrentVersionNumber(),
                options, version.getAnswerKey());
    }

    // ============================================================
    // Authorization (deny by default — mirrors QuestionImportService)
    // ============================================================

    private Question requireOwnedQuestion(Long userId, Long questionId) {
        Instant now = Instant.now(clock);
        requireActiveTeacherRole(userId, now);
        requirePermission(userId, "QUESTION_CREATE", now);
        TeacherProfile profile = resolveTeacherProfile(userId, now);

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_NOT_FOUND));

        QuestionBank bank = questionBankRepository.findById(question.getQuestionBankId())
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_BANK_NOT_FOUND));

        if (!bank.getOwnerTeacherId().equals(profile.getId())) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        if (!bank.getSchoolId().equals(profile.getSchoolId())) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
        return question;
    }

    private void requireActiveTeacherRole(Long userId, Instant now) {
        List<String> roles = userRoleRepository.findActiveRoleCodesByUserId(userId, now);
        if (!roles.contains("TEACHER")) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }

    private void requirePermission(Long userId, String permission, Instant now) {
        List<String> perms = rolePermissionRepository.findEffectivePermissionCodesByUserId(userId, now);
        if (!perms.contains(permission)) {
            throw new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }
    }

    private TeacherProfile resolveTeacherProfile(Long userId, Instant now) {
        return teacherProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new QuestionException(QuestionErrorCode.QUESTION_TEACHER_PROFILE_NOT_FOUND));
    }
}
