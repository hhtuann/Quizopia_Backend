package com.hhtuann.backend.realtime;

import com.hhtuann.backend.realtime.event.RealtimeEventEnvelope;
import com.hhtuann.backend.realtime.event.RealtimePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.UserDestinationResult;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.DefaultSimpUserRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * B1R4-B2F7 §1 — executable proof of the {@code SERVER_TIME_SYNC} session-targeting race, using the REAL
 * Spring 7.0.8 resolver + registry (no mocking of the result under test).
 *
 * <p>Spring's {@code DefaultUserDestinationResolver.parseMessage} has two branches:
 * <pre>
 *   if (userName.equals(sessionId)) {              // userName == the /user/{user}/ path segment
 *       sessionIds = singleton(sessionId);          // DIRECT session targeting — registry-independent
 *   } else {
 *       sessionIds = getSessionIdsByUser(userName, sessionId);  // REGISTRY lookup
 *   }
 * </pre>
 * and {@code getSessionIdsByUser} returns {@code emptySet()} when the user is absent from the registry.
 *
 * <p>The current production path passes {@code principalName} as the user argument, so the
 * {@code userName.equals(sessionId)} branch is NEVER taken and resolution depends entirely on
 * {@code DefaultSimpUserRegistry} having recorded the user + session — which is populated by the same
 * {@code SessionSubscribeEvent} with no {@code @Order} guarantee relative to {@code ServerTimeSyncListener}.
 * Under package load that window opens and the resolver yields zero targets (the B2F6 "0 messages" bug).
 * Routing by {@code simpSessionId} directly takes the equality branch and bypasses the registry entirely.
 */
class RealtimePublisherSessionTargetingTests {

    // REAL resolver + REAL registry in its unpopulated state = the race window (registry not yet updated).
    private final DefaultSimpUserRegistry registry = new DefaultSimpUserRegistry();
    private final DefaultUserDestinationResolver resolver = new DefaultUserDestinationResolver(registry);

    private Message<?> userDestinationMessage(String destination, String simpSessionId) {
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        h.setDestination(destination);
        h.setSessionId(simpSessionId);
        h.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    // --- Current production pattern: user argument = principalName ---

    @Test
    void currentPrincipalNamePatternDependsOnRegistry_andYieldsZeroTargetsWhenUnpopulated() {
        // Mirrors RealtimePublisher today: convertAndSendToUser("123", "/queue/attempt", headers(simpSessionId="session-B"))
        // -> source destination /user/123/queue/attempt. Registry empty (race state: not updated yet).
        Message<?> msg = userDestinationMessage("/user/123/queue/attempt", "session-B");
        UserDestinationResult result = resolver.resolveDestination(msg);

        assertThat(result).as("resolver must always return a (possibly empty) result").isNotNull();
        assertThat(result.getTargetDestinations())
                .as("current pattern with an unpopulated registry resolves to ZERO targets — the B2F6 0-messages bug")
                .isEmpty();
        assertThat(result.getSessionIds())
                .as("no session is targeted when the registry does not know the user")
                .isEmpty();
    }

    // --- Fixed pattern: user argument = simpSessionId (direct session targeting) ---

    @Test
    void directSessionPatternBypassesRegistry_andTargetsExactlyOneSessionEvenWhenEmpty() {
        // Fixed path: convertAndSendToUser("session-B", "/queue/attempt", headers(simpSessionId="session-B"))
        // -> source destination /user/session-B/queue/attempt -> userName.equals(sessionId) -> direct branch.
        Message<?> msg = userDestinationMessage("/user/session-B/queue/attempt", "session-B");
        UserDestinationResult result = resolver.resolveDestination(msg);

        assertThat(result).as("resolver must always return a (possibly empty) result").isNotNull();
        assertThat(result.getSessionIds())
                .as("direct pattern targets exactly session-B even with an EMPTY registry")
                .containsExactly("session-B");
        assertThat(result.getTargetDestinations())
                .as("exactly one outbound target resolved")
                .hasSize(1)
                .contains("/queue/attempt-usersession-B");
        assertThat(result.getUser())
                .as("userName is nulled on the direct branch — no principal leakage in the resolved result")
                .isNull();
    }

    @Test
    void directSessionPatternGeneralizes_toAnySessionId() {
        // A second session ("session-A") resolves independently — the direct branch is not a hardcoded match.
        Message<?> msg = userDestinationMessage("/user/session-A/queue/attempt", "session-A");
        UserDestinationResult result = resolver.resolveDestination(msg);

        assertThat(result).isNotNull();
        assertThat(result.getSessionIds()).containsExactly("session-A");
        assertThat(result.getTargetDestinations())
                .hasSize(1)
                .contains("/queue/attempt-usersession-A");
    }

    // --- B1R4-B2F7 §4: production publisher routes by simpSessionId, not principalName ---

    @Test
    @SuppressWarnings("unchecked")
    void sendServerTimeSyncRoutesBySessionIdWithMatchingHeader_andCleanPayload() {
        // Verify the arguments reaching SimpMessagingTemplate — not just that sendServerTimeSync was called.
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        Clock fixed = Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC);
        RealtimePublisher publisher = new RealtimePublisher(messaging, fixed);

        publisher.sendServerTimeSync("sess-B");

        ArgumentCaptor<RealtimeEventEnvelope> payloadCap = ArgumentCaptor.forClass(RealtimeEventEnvelope.class);
        ArgumentCaptor<Map<String, Object>> headersCap = ArgumentCaptor.forClass(Map.class);
        verify(messaging).convertAndSendToUser(
                eq("sess-B"), eq("/queue/attempt"), payloadCap.capture(), headersCap.capture());

        // Routing: the user argument IS the simpSessionId (the B2F7 fix), not a principal name.
        // Header: simpSessionId carries the SAME session id so the resolver takes userName.equals(sessionId).
        assertThat(headersCap.getValue().get(SimpMessageHeaderAccessor.SESSION_ID_HEADER))
                .as("simpSessionId header must equal the routing session id")
                .isEqualTo("sess-B");

        // Payload: SERVER_TIME_SYNC only — no principal/email/token or session/attempt/student data.
        RealtimeEventEnvelope env = payloadCap.getValue();
        assertThat(env.eventType()).isEqualTo("SERVER_TIME_SYNC");
        assertThat(env.eventId()).as("fresh event id always present").isNotNull();
        assertThat(env.occurredAt()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
        assertThat(env.serverTime()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
        assertThat(env.sessionId()).isNull();
        assertThat(env.attemptId()).isNull();
        assertThat(env.studentProfileId()).isNull();
        assertThat(env.activeCount()).isNull();
    }
}
