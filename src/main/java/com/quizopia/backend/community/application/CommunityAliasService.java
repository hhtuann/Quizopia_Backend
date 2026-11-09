package com.quizopia.backend.community.application;

import com.quizopia.backend.community.application.CommunityAuthorizationService.PublisherAccount;
import com.quizopia.backend.community.domain.model.CommunityMemberProfile;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityMemberProfileRepository;
import org.springframework.stereotype.Service;

/**
 * The §4 alias consent lifecycle (ADR-007, issue #66): validation,
 * profile upsert and the cross-namespace impersonation probes.
 *
 * <p><b>Validation</b> (create and every alias change): 1–80 chars
 * after trim (bounds → COMMUNITY_VALIDATION_ERROR); printable visible
 * charset only — no control characters, no zero-width, no bidi
 * (Unicode {@code FORMAT} category), no {@code @} — and no
 * case-insensitive equality with the requester's own email or
 * username, ANY {@code users.username} or ANY
 * {@code community_publications.author_alias} (charset and identity →
 * COMMUNITY_ALIAS_INVALID). The tag-charset discipline, mirrored.
 *
 * <p><b>Upsert</b>: the first comment creates the profile with
 * {@code INSERT … ON CONFLICT (user_id) DO NOTHING} + re-select, so
 * concurrent first comments from one user collapse onto one identity;
 * an alias change on an existing profile runs
 * {@code UPDATE … WHERE user_id = ?} after the same validation. Both
 * run INSIDE the caller's comment-create transaction — a different-user
 * ci-unique collision aborts it atomically and the caller maps the
 * {@code DataIntegrityViolationException} on
 * {@code uk_community_member_profiles_alias_ci} to 409
 * COMMUNITY_ALIAS_TAKEN (fork-precedent recovery, never a 500).
 *
 * <p><b>Reverse check</b>: {@link #assertAliasNotTakenByMember} backs
 * the publication publish/PATCH {@code authorAlias} validation (the
 * single ADR-sanctioned P3-A touch) — a publication author may not
 * take a member's alias either.
 */
@Service
public class CommunityAliasService {

    /** ADR-007 §3: alias VARCHAR(80). */
    static final int ALIAS_MAX_LENGTH = 80;

    private final CommunityMemberProfileRepository profiles;
    private final CommunityAuthorizationService auth;

    public CommunityAliasService(CommunityMemberProfileRepository profiles,
            CommunityAuthorizationService auth) {
        this.profiles = profiles;
        this.auth = auth;
    }

    /**
     * Resolves the STORED alias a new comment must snapshot (§4).
     * Requires {@code alias} when the caller has no profile yet
     * (first comment); later comments default to the stored alias.
     * Must run inside the comment-create transaction.
     */
    public String resolveAliasForNewComment(Long userId, String requestedAlias) {
        CommunityMemberProfile stored = profiles.findByUserId(userId).orElse(null);
        String desired = requestedAlias == null ? null : requestedAlias.trim();

        if (stored != null && (desired == null || desired.isEmpty())) {
            // Subsequent comments default to the stored pseudonym.
            return stored.getAlias();
        }
        if (desired == null || desired.isEmpty()) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "alias is required on your first comment");
        }

        if (stored != null && desired.equals(stored.getAlias())) {
            // Re-submitting the stored pseudonym is not a change: no
            // probe, no write (§4 — a change is a DIFFERENT alias;
            // the FE re-shows the consent copy exactly then).
            return stored.getAlias();
        }

        validateAlias(desired, userId);

        if (stored == null) {
            // §4 first-comment upsert: the idempotent insert + re-select
            // collapses concurrent first comments onto one identity; a
            // different-user ci collision raises DataIntegrityViolationException
            // on uk_community_member_profiles_alias_ci for the caller to map.
            profiles.insertProfileIfAbsent(userId, desired);
        } else {
            profiles.updateAliasByUserId(userId, desired);
        }
        // The snapshot is ALWAYS the post-upsert STORED alias (§4).
        return profiles.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("member profile missing after upsert"))
                .getAlias();
    }

    /**
     * §4 alias validation, shared by the first-comment upsert and every
     * alias change. Bounds → 400 COMMUNITY_VALIDATION_ERROR; charset
     * and identity → 400 COMMUNITY_ALIAS_INVALID.
     */
    private void validateAlias(String alias, Long userId) {
        if (alias.isEmpty() || alias.length() > ALIAS_MAX_LENGTH) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "alias must be 1 to " + ALIAS_MAX_LENGTH + " characters after trim");
        }
        alias.codePoints().forEach(CommunityAliasService::requireVisibleCodePoint);

        PublisherAccount account = auth.account(userId);
        if (alias.equalsIgnoreCase(account.email()) || alias.equalsIgnoreCase(account.username())) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID);
        }
        if (profiles.anyUsernameMatchesIgnoreCase(alias)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID);
        }
        if (profiles.anyPublicationAuthorAliasMatchesIgnoreCase(alias)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID);
        }
    }

    /**
     * Printable visible charset only (§4): rejects ISO control
     * characters (an alias never admits even {@code \n}/{@code \t}),
     * the Unicode FORMAT category — zero-width spaces/joiners and the
     * bidi controls — and {@code @} (namespace separator, mail
     * impersonation).
     */
    private static void requireVisibleCodePoint(int codePoint) {
        if (codePoint == '@') {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID,
                    "alias must not contain '@'");
        }
        if (Character.isISOControl(codePoint)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID,
                    "alias must not contain control characters");
        }
        if (Character.getType(codePoint) == Character.FORMAT) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID,
                    "alias must not contain zero-width or bidirectional characters");
        }
    }

    /**
     * The §4 reverse ci-check for publication publish/PATCH
     * {@code authorAlias} (ADR-007's single P3-A touch): a publication
     * author may not take a member's pseudonym. Null (alias cleared)
     * is not this check's concern.
     */
    public void assertAliasNotTakenByMember(String alias) {
        if (alias != null && profiles.aliasExistsIgnoreCase(alias)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ALIAS_INVALID,
                    "Author alias must not match a community member's public alias");
        }
    }
}
