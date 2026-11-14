package com.quizopia.backend.media.roomservice;

import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.token.LiveKitTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-08 invariant 6: NO LiveKit RoomService call may ever run inside an
 * active database transaction. {@link TwirpMediaRoomGateway} enforces this
 * with a fail-fast guard BEFORE any I/O, so this plain unit test verifies it
 * without an HTTP server — it marks a transaction active on the current
 * thread (exactly what {@code TransactionSynchronizationManager} sees inside
 * a Spring-managed transaction) and asserts the guard rejects the call.
 */
class TwirpMediaRoomGatewayTransactionGuardTest {

    @Test
    void rejectsGatewayCallsInsideActiveTransaction() {
        LiveKitProperties properties = new LiveKitProperties();
        TwirpMediaRoomGateway gateway = new TwirpMediaRoomGateway(properties,
                new LiveKitTokenService(properties), Clock.systemUTC());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> gateway.findParticipant("some-room", "some-identity"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inside a DB transaction");
            assertThatThrownBy(() -> gateway.removeParticipant("some-room", "some-identity"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> gateway.listParticipants("some-room"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> gateway.deleteRoom("some-room"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
