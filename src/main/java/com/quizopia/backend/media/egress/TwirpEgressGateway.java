package com.quizopia.backend.media.egress;

import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.egress.EgressGateway.EgressFile;
import com.quizopia.backend.media.egress.EgressGateway.EgressInfo;
import com.quizopia.backend.media.egress.EgressGateway.EgressRun;
import com.quizopia.backend.media.egress.EgressGateway.S3Output;
import com.quizopia.backend.media.token.LiveKitTokenService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * {@link EgressGateway} over the LiveKit Egress Twirp HTTP/JSON API
 * ({@code POST {httpUrl}/twirp/livekit.Egress/<Method>}) — the RPC set
 * verified on the pinned livekit-server v1.9.12 protocol
 * (StartTrackCompositeEgress / StopEgress / ListEgress; NO unified
 * StartEgress at this pin). Requests carry a server-only admin JWT and
 * protojson field names.
 */
@Component
public class TwirpEgressGateway implements EgressGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final LiveKitTokenService tokens;
    private final LiveKitProperties properties;

    public TwirpEgressGateway(LiveKitProperties properties,
            LiveKitTokenService tokens) {
        this.tokens = tokens;
        this.properties = properties;
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
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
    public EgressRun startVideoTrackRecording(String roomName, String videoTrackSid,
            String filepathPrefix, S3Output s3) {
        // audio_track_id is DELIBERATELY absent — video-only by construction
        // (issue #92 / ADR-009 §2.2 no-audio rule).
        Map<String, Object> s3Body = new HashMap<>();
        s3Body.put("endpoint", s3.endpoint());
        s3Body.put("access_key", s3.accessKey());
        s3Body.put("secret", s3.secretKey());
        s3Body.put("bucket", s3.bucket());
        s3Body.put("region", s3.region());
        s3Body.put("force_path_style", s3.forcePathStyle());
        Map<String, Object> fileOutput = new HashMap<>();
        fileOutput.put("file_type", "MP4");
        fileOutput.put("filepath", filepathPrefix + "-{time}.mp4");
        fileOutput.put("s3", s3Body);
        Map<String, Object> body = new HashMap<>();
        body.put("room_name", roomName);
        body.put("video_track_id", videoTrackSid);
        body.put("file_outputs", List.of(fileOutput));
        Map<String, Object> response = request("StartTrackCompositeEgress", roomName, body);
        return new EgressRun(str(response.get("egress_id")), str(response.get("status")));
    }

    @Override
    public void stop(String egressId) {
        request("StopEgress", null, Map.of("egress_id", egressId));
    }

    @Override
    public List<EgressInfo> list(String roomName, boolean activeOnly) {
        Map<String, Object> body = new HashMap<>();
        body.put("room_name", roomName);
        if (activeOnly) {
            body.put("active", true);
        }
        Map<String, Object> response = request("ListEgress", roomName, body);
        List<EgressInfo> result = new ArrayList<>();
        Object items = response.get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) map;
                    result.add(toInfo(info));
                }
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> request(String method, String room, Map<String, Object> body) {
        assertNoTransaction();
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/twirp/livekit.Egress/{m}", method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + tokens.mintAdminToken(room, java.time.Instant.now()))
                    .body(body)
                    .retrieve()
                    .body(MAP);
            if (response == null) {
                throw new LiveKitEgressException("Egress " + method + " returned no body");
            }
            return response;
        } catch (LiveKitEgressException e) {
            throw e;
        } catch (Exception e) {
            throw new LiveKitEgressException("Egress " + method + " failed: " + e.getMessage(), e);
        }
    }

    /** Parses an {@code EgressInfo}-shaped map (webhook body or ListEgress item). */
    public static EgressInfo toInfo(Map<String, Object> info) {
        List<EgressFile> files = new ArrayList<>();
        Object fileResults = info.get("file_results");
        if (fileResults instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> f = (Map<String, Object>) map;
                    files.add(new EgressFile(
                            str(f.get("filename")),
                            longVal(f.get("size")),
                            longVal(f.get("duration")),
                            longVal(f.get("started_at")),
                            longVal(f.get("ended_at"))));
                }
            }
        }
        return new EgressInfo(
                str(info.get("egress_id")),
                str(info.get("status")),
                longVal(info.get("started_at")),
                longVal(info.get("ended_at")),
                str(info.get("error")),
                List.copyOf(files));
    }

    private static long longVal(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Egress call attempted inside a DB transaction (ADR-08 invariant 6)");
        }
    }

    /** Raised when the egress service cannot be reached or errors (maps to 503). */
    public static class LiveKitEgressException extends RuntimeException {
        public LiveKitEgressException(String message) {
            super(message);
        }

        public LiveKitEgressException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
