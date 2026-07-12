package com.quizopia.backend.attempt.application;

import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Strict validator + canonicalizer for autosave answer payloads.
 *
 * <p><b>Snapshot validation (STRICT — no silent repair):</b> before checking the payload, the
 * persisted {@code option_order} snapshot is validated per type. Any corruption (non-array,
 * non-string element, duplicate, wrong cardinality, keys outside A–F / not {A,B,C,D} for TF,
 * non-null for NUMERIC) → {@code ATTEMPT_VALIDATION_ERROR}. This means a clear payload with a
 * corrupted snapshot is STILL rejected.
 *
 * <p><b>Payload validation:</b> after the snapshot passes, the payload is validated + canonicalized.
 * Shape/key/value mismatch → {@code ATTEMPT_INVALID_ANSWER_PAYLOAD}.
 */
@Component
public class AnswerPayloadValidator {

    private static final Pattern NUMERIC = Pattern.compile("^-?[0-9]+([.,][0-9]+)?$");
    private static final String[] TF_ORDER = {"A", "B", "C", "D"};
    private static final Set<String> VALID_OPTION_KEYS = Set.of("A", "B", "C", "D", "E", "F");

    private final ObjectMapper objectMapper;

    public AnswerPayloadValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode validateAndCanonicalize(String questionType, JsonNode optionOrder, JsonNode payload) {
        // 1. Validate the persisted snapshot FIRST (before clear check) — corrupted snapshot → 400 even for clear.
        Set<String> validKeys = validateSnapshot(questionType, optionOrder);
        // 2. Clear answer (Java null OR JSON null/NullNode) — valid only if snapshot passed.
        if (payload == null || payload.isNull()) {
            return null;
        }
        // 3. Payload must be a JSON object.
        if (!payload.isObject()) {
            throw payloadError();
        }
        // 4. Validate + canonicalize per type.
        return switch (questionType) {
            case "SINGLE_CHOICE" -> single(payload, validKeys);
            case "MULTIPLE_CHOICE" -> multiple(payload, validKeys);
            case "TRUE_FALSE_MATRIX" -> tf(payload, validKeys);
            case "NUMERIC_FILL" -> numeric(payload);
            default -> throw payloadError();
        };
    }

    // === strict snapshot validation ===

    private Set<String> validateSnapshot(String questionType, JsonNode optionOrder) {
        return switch (questionType) {
            case "SINGLE_CHOICE", "MULTIPLE_CHOICE" -> optionBasedSnapshot(optionOrder, 4, 6);
            case "TRUE_FALSE_MATRIX" -> tfSnapshot(optionOrder);
            case "NUMERIC_FILL" -> numericSnapshot(optionOrder);
            default -> throw snapshotError();
        };
    }

    private Set<String> optionBasedSnapshot(JsonNode optionOrder, int min, int max) {
        if (optionOrder == null || !optionOrder.isArray()) throw snapshotError();
        int size = optionOrder.size();
        if (size < min || size > max) throw snapshotError();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode k : optionOrder) {
            if (!k.isString()) throw snapshotError();
            String key = k.asString();
            if (!keys.add(key)) throw snapshotError(); // duplicate
            if (!VALID_OPTION_KEYS.contains(key)) throw snapshotError(); // outside A-F
        }
        return keys;
    }

    private Set<String> tfSnapshot(JsonNode optionOrder) {
        if (optionOrder == null || !optionOrder.isArray()) throw snapshotError();
        if (optionOrder.size() != 4) throw snapshotError();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode k : optionOrder) {
            if (!k.isString()) throw snapshotError();
            if (!keys.add(k.asString())) throw snapshotError();
        }
        if (!keys.equals(Set.of(TF_ORDER))) throw snapshotError();
        return keys;
    }

    private Set<String> numericSnapshot(JsonNode optionOrder) {
        if (optionOrder != null) throw snapshotError();
        return Set.of();
    }

    // === payload validation ===

    private JsonNode single(JsonNode payload, Set<String> validKeys) {
        if (payload.size() != 1) throw payloadError();
        JsonNode key = payload.get("selectedOptionKey");
        if (key == null || !key.isString()) throw payloadError();
        String k = key.asString();
        if (!validKeys.contains(k)) throw payloadError();
        return objectMapper.createObjectNode().put("selectedOptionKey", k);
    }

    private JsonNode multiple(JsonNode payload, Set<String> validKeys) {
        if (payload.size() != 1) throw payloadError();
        JsonNode arr = payload.get("selectedOptionKeys");
        if (arr == null || !arr.isArray()) throw payloadError();
        TreeSet<String> sorted = new TreeSet<>();
        for (JsonNode e : arr) {
            if (!e.isString()) throw payloadError();
            String k = e.asString();
            if (!validKeys.contains(k)) throw payloadError();
            sorted.add(k);
        }
        ArrayNode canon = objectMapper.createArrayNode();
        for (String k : sorted) canon.add(k);
        return objectMapper.createObjectNode().set("selectedOptionKeys", canon);
    }

    private JsonNode tf(JsonNode payload, Set<String> validKeys) {
        if (payload.size() != 1) throw payloadError();
        JsonNode responses = payload.get("responses");
        if (responses == null || !responses.isObject()) throw payloadError();
        Set<String> present = new HashSet<>();
        for (Map.Entry<String, JsonNode> f : responses.properties()) {
            if (!validKeys.contains(f.getKey())) throw payloadError();
            if (!f.getValue().isBoolean()) throw payloadError();
            present.add(f.getKey());
        }
        ObjectNode ordered = objectMapper.createObjectNode();
        for (String k : TF_ORDER) {
            if (present.contains(k)) ordered.set(k, responses.get(k));
        }
        TreeSet<String> extra = new TreeSet<>(present);
        extra.removeAll(Set.of(TF_ORDER));
        for (String k : extra) ordered.set(k, responses.get(k));
        return objectMapper.createObjectNode().set("responses", ordered);
    }

    private JsonNode numeric(JsonNode payload) {
        if (payload.size() != 1) throw payloadError();
        JsonNode value = payload.get("value");
        if (value == null || !value.isString()) throw payloadError();
        String raw = value.asString();
        if (raw.length() != 4) throw payloadError();
        if (!NUMERIC.matcher(raw).matches()) throw payloadError();
        return objectMapper.createObjectNode().put("value", raw);
    }

    private static AttemptException snapshotError() {
        return new AttemptException(AttemptErrorCode.ATTEMPT_VALIDATION_ERROR);
    }

    private static AttemptException payloadError() {
        return new AttemptException(AttemptErrorCode.ATTEMPT_INVALID_ANSWER_PAYLOAD);
    }
}
