package com.quizopia.backend.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.quizopia.backend.monitoring.domain.model.AiAlertType;
import com.quizopia.backend.monitoring.domain.model.AiDetectionType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The firstDetections subquery maps alert_type → event_type in SQL. A new
 * alert type without a CASE branch would silently return no opening boxes;
 * pin the SQL to the enum mapping.
 */
class AiAlertQueryFirstDetectionCaseTest {

    @Test
    void everyAlertTypeHasItsDetectionTypeInTheFirstDetectionsCase() {
        for (AiDetectionType detection : AiDetectionType.values()) {
            AiAlertType alert = AiAlertType.fromDetection(detection);
            assertThat(AiAlertQueryService.SELECT_PAGE)
                    .as("CASE branch for %s", alert)
                    .contains("WHEN '" + alert.name() + "' THEN '" + detection.name() + "'");
        }
        assertThat(Arrays.stream(AiDetectionType.values()).map(AiAlertType::fromDetection).distinct())
                .containsExactlyInAnyOrder(AiAlertType.values());
    }
}
