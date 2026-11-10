package com.quizopia.backend.community.application;

import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit pin of the §3 body charset rule at its owning layer
 * ({@code CommunityCommentService.validateBody}): Jackson 3 normalizes
 * {@code \r} to {@code \n} and strips other control characters from
 * JSON strings, so the HTTP layer can no longer deliver them — this is
 * the defense-in-depth assertion that ANY control character other than
 * {@code \n}/{@code \t} is rejected before storage, plus the trim /
 * blank / 2000-bound rules.
 */
class CommunityCommentBodyValidationTest {

    @Test
    void controlCharactersOtherThanNewlineAndTabAreRejected() {
        assertThatThrownBy(() -> CommunityCommentService.validateBody("be\u0007ll"))
                .isInstanceOfSatisfying(CommunityException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR));
        assertThatThrownBy(() -> CommunityCommentService.validateBody("v\u000Bt"));
        assertThatThrownBy(() -> CommunityCommentService.validateBody("cr\rhere"));
        assertThatThrownBy(() -> CommunityCommentService.validateBody("e\u001Bsc"));
    }

    @Test
    void newlineAndTabAreAcceptedAndStoredVerbatim() {
        assertThat(CommunityCommentService.validateBody("line one\nline two\ttabbed"))
                .isEqualTo("line one\nline two\ttabbed");
    }

    @Test
    void leadingAndTrailingControlsAreTrimmedAwayHarmlessly() {
        // String.trim() strips any char <= U+0020 at the ends — including
        // control characters — so edge controls never reach storage.
        assertThat(CommunityCommentService.validateBody("bell\u0007")).isEqualTo("bell");
        assertThat(CommunityCommentService.validateBody("\u0007bell")).isEqualTo("bell");
    }

    @Test
    void boundsTrimBlankAndNull() {
        assertThatThrownBy(() -> CommunityCommentService.validateBody(null));
        assertThatThrownBy(() -> CommunityCommentService.validateBody("   "));
        assertThatThrownBy(() -> CommunityCommentService.validateBody("x".repeat(2001)));
        // Trimming happens before the bound check; 2000 is fine.
        assertThat(CommunityCommentService.validateBody("  " + "y".repeat(2000) + " "))
                .hasSize(2000);
    }
}
