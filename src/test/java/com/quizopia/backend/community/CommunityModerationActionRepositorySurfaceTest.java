package com.quizopia.backend.community;

import com.quizopia.backend.community.repository.CommunityModerationActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.history.RevisionRepository;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The moderation audit trail is append-only BY REPOSITORY SURFACE
 * (ADR-006 §3 V27 / §5, issue #42): the repository must extend the bare
 * {@link Repository} marker (no {@code JpaRepository}/{@code
 * CrudRepository} inheritance — those would drag {@code delete*} along)
 * and must declare no method whose name mutates or removes rows.
 */
class CommunityModerationActionRepositorySurfaceTest {

    @Test
    void extendsOnlyTheBareRepositoryMarker() {
        assertThat(CommunityModerationActionRepository.class.getInterfaces())
                .as("no JpaRepository/CrudRepository/PagingAndSorting inheritance")
                .containsExactly(Repository.class);
        assertThat(Repository.class.isAssignableFrom(CommunityModerationActionRepository.class))
                .isTrue();
        assertThat(RevisionRepository.class
                .isAssignableFrom(CommunityModerationActionRepository.class))
                .isFalse();
    }

    @Test
    void declaredSurfaceIsSaveAndReadsOnly() {
        Method[] methods = CommunityModerationActionRepository.class.getDeclaredMethods();

        assertThat(Arrays.stream(methods).map(Method::getName))
                .as("the only declared write is save (single-row append)")
                .containsExactlyInAnyOrder(
                        "save",
                        "findByPublicationIdOrderByCreatedAtDescIdDesc",
                        "findByReportIdOrderByCreatedAtDescIdDesc");

        for (Method method : methods) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .as("method %s must not mutate or remove rows", method.getName())
                    .doesNotContain("delete")
                    .doesNotContain("update")
                    .doesNotContain("remove")
                    .doesNotContain("flush")
                    .doesNotContain("saveall");
        }

        // save() must accept exactly one argument (single-row append, no
        // batch surface either).
        Method save = Arrays.stream(methods)
                .filter(m -> m.getName().equals("save")).findFirst().orElseThrow();
        assertThat(save.getParameterCount()).isEqualTo(1);
        assertThat(save.getParameterTypes()[0])
                .isEqualTo(com.quizopia.backend.community.domain.model.CommunityModerationAction.class);
    }
}
