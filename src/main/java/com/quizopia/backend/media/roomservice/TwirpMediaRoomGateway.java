package com.quizopia.backend.media.roomservice;

import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.token.LiveKitTokenService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MediaRoomGateway} over the LiveKit RoomService Twirp HTTP/JSON API
 * ({@code POST {httpUrl}/twirp/livekit.RoomService/<Method>}). Requests are
 * authorized with a short-lived server-only admin JWT. Bodies are read as
 * untyped maps (protojson field superset tolerance) and projected into
 * {@link LkParticipant}/{@link LkTrack} records.
 *
 * <p><b>Transaction guard:</b> every call asserts no active Spring-managed
 * DB transaction — callers must do media I/O outside their transactional
 * boundaries (ADR-08 invariant 6). Violations fail fast with
 * {@link IllegalStateException}.</p>
 */
@Component
public class TwirpMediaRoomGateway implements MediaRoomGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final LiveKitTokenService tokens;
    private final LiveKitProperties properties;
    private final Clock clock;

    public TwirpMediaRoomGateway(LiveKitProperties properties,
                                 LiveKitTokenService tokens,
                                 Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getRequestTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getHttpUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public Optional<LkParticipant> findParticipant(String room, String identity) {
        return request("GetParticipant", Map.of("room", room, "identity", identity))
                .map(TwirpMediaRoomGateway::toParticipant);
    }

    @Override
    public List<LkParticipant> listParticipants(String room) {
        return request("ListParticipants", Map.of("room", room))
                .map(body -> {
                    Object participants = body.get("participants");
                    if (!(participants instanceof List<?> list)) {
                        return List.<LkParticipant>of();
                    }
                    List<LkParticipant> result = new ArrayList<>(list.size());
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            result.add(toParticipant(cast(map)));
                        }
                    }
                    return List.copyOf(result);
                })
                .orElse(List.of());
    }

    @Override
    public boolean removeParticipant(String room, String identity) {
        return request("RemoveParticipant", Map.of("room", room, "identity", identity))
                .isPresent();
    }

    @Override
    public boolean deleteRoom(String room) {
        return request("DeleteRoom", Map.of("room", room)).isPresent();
    }

    /**
     * POSTs a Twirp method; empty Optional means "not found" (HTTP 404);
     * connectivity/5xx errors propagate as {@link LiveKitUnavailableException}
     * so services can map them to 503 MONITORING_INFRA_UNAVAILABLE.
     */
    private Optional<Map<String, Object>> request(String method, Map<String, String> body) {
        assertNoTransaction();
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/twirp/livekit.RoomService/{m}", method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.mintAdminToken(body.get("room"), clock.instant()))
                    .body(body)
                    .retrieve()
                    .body(MAP);
            return Optional.ofNullable(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new LiveKitUnavailableException("LiveKit " + method + " failed: HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new LiveKitUnavailableException("LiveKit " + method + " unreachable", e);
        }
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Media call attempted inside a DB transaction (ADR-08 invariant 6)");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static LkParticipant toParticipant(Map<String, Object> body) {
        Object tracksRaw = body.get("tracks");
        List<LkTrack> tracks = List.of();
        if (tracksRaw instanceof List<?> list) {
            List<LkTrack> parsed = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> t = cast(map);
                    parsed.add(new LkTrack(
                            str(t.get("sid")),
                            enumName(t.get("type"), "AUDIO", "VIDEO", "DATA"),
                            enumName(t.get("source"), "CAMERA", "MICROPHONE", "SCREEN_SHARE",
                                    "SCREEN_SHARE_AUDIO", "UNKNOWN"),
                            Boolean.TRUE.equals(t.get("muted"))));
                }
            }
            tracks = List.copyOf(parsed);
        }
        return new LkParticipant(str(body.get("identity")),
                enumName(body.get("state"), "JOINED", "ACTIVE", "DISCONNECTED"),
                tracks);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Normalises a protojson enum that may arrive as a name ("VIDEO") or as
     * a numeric value; unknown/missing values fall back to {@code fallbackOnUnknown}
     * handling by returning the raw string (so callers can still log it).
     */
    private static String enumName(Object value, String... known) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        for (String k : known) {
            if (k.equalsIgnoreCase(s)) {
                return k;
            }
        }
        return s;
    }

    /** Raised when the media server cannot be reached or errors (maps to 503). */
    public static class LiveKitUnavailableException extends RuntimeException {
        public LiveKitUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
