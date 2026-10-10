package com.jorjik.dynamicfood.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record ProviderContribution(
    String providerId,
    String mechanicId,
    ProviderContributionOperation operation,
    String sourceType,
    String sourceId,
    Map<String, Object> payload,
    Map<String, String> provenance
) {
    public ProviderContribution {
        Objects.requireNonNull(providerId, "providerId required");
        Objects.requireNonNull(mechanicId, "mechanicId required");
        operation = operation == null ? ProviderContributionOperation.ADD : operation;
        sourceType = sourceType == null ? "unknown" : sourceType;
        sourceId = sourceId == null ? mechanicId : sourceId;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    public String canonicalKey() {
        return providerId + ":" + mechanicId + ":" + operation + ":" + sourceType + ":" + sourceId;
    }

    public String canonicalPayload() {
        return new Gson().toJson(canonicalPayloadObject(payload));
    }

    static JsonObject canonicalPayloadObject(Map<String, Object> payload) {
        JsonObject canonical = new JsonObject();
        new TreeMap<>(payload).forEach((key, value) -> canonical.add(key, canonicalJson(value)));
        return canonical;
    }

    private static JsonElement canonicalJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement element) {
            return canonicalJson(element);
        }
        if (value instanceof String string) {
            return new JsonPrimitive(string);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("provider payload numbers must be finite");
            }
            return new JsonPrimitive(number);
        }
        if (value instanceof Enum<?> enumeration) {
            return new JsonPrimitive(enumeration.name());
        }
        if (value instanceof AcquisitionMeasurement measurement) {
            JsonObject result = new JsonObject();
            result.add("value", measurement.value() == null
                ? JsonNull.INSTANCE : new JsonPrimitive(measurement.value()));
            result.addProperty("unknownReason", measurement.unknownReason());
            result.addProperty("estimateKind", measurement.estimateKind()
                .map(Enum::name).orElse(null));
            result.add("estimateMetadata", measurement.estimateMetadata()
                .map(ProviderContribution::canonicalJson).orElse(JsonNull.INSTANCE));
            return result;
        }
        if (value instanceof EstimateMetadata metadata) {
            JsonObject result = new JsonObject();
            result.addProperty("method", metadata.method());
            result.addProperty("evaluatorVersion", metadata.evaluatorVersion());
            result.add("parameters", canonicalJson(metadata.parameters()));
            result.add("assumptions", canonicalJson(metadata.assumptions()));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("provider payload object keys must be strings");
                }
                sorted.put(stringKey, nested);
            });
            JsonObject result = new JsonObject();
            sorted.forEach((key, nested) -> result.add(key, canonicalJson(nested)));
            return result;
        }
        if (value instanceof Set<?> set) {
            return canonicalArray(set.stream().map(ProviderContribution::canonicalJson)
                .sorted(java.util.Comparator.comparing(JsonElement::toString)).toList());
        }
        if (value instanceof Collection<?> collection) {
            return canonicalArray(collection.stream().map(ProviderContribution::canonicalJson).toList());
        }
        throw new IllegalArgumentException("unsupported provider payload value type: "
            + value.getClass().getName());
    }

    private static JsonArray canonicalArray(java.util.List<JsonElement> elements) {
        JsonArray array = new JsonArray();
        elements.forEach(array::add);
        return array;
    }

    private static JsonElement canonicalJson(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            new TreeMap<>(element.getAsJsonObject().asMap())
                .forEach((key, value) -> result.add(key, canonicalJson(value)));
            return result;
        }
        if (element.isJsonArray()) {
            return canonicalArray(element.getAsJsonArray().asList().stream()
                .map(ProviderContribution::canonicalJson).toList());
        }
        return element.deepCopy();
    }
}
