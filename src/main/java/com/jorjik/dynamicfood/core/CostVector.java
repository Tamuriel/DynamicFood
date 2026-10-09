package com.jorjik.dynamicfood.core;

import java.util.EnumMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class CostVector {
    private final int economicHorizon;
    private final Map<EconomicChannel, EconomicFactor> channels;

    public CostVector(int economicHorizon, Map<?, EconomicFactor> factors) {
        if (economicHorizon < 1) {
            throw new IllegalArgumentException("economicHorizon must be positive");
        }
        if (factors == null) {
            throw new IllegalArgumentException("economic channels are required");
        }
        EnumMap<EconomicChannel, EconomicFactor> typed = new EnumMap<>(EconomicChannel.class);
        factors.forEach((key, factor) -> {
            EconomicChannel channel = key instanceof EconomicChannel economicChannel
                ? economicChannel
                : key instanceof String id ? EconomicChannel.fromId(id).orElse(null) : null;
            if (channel == null) {
                throw new IllegalArgumentException("unsupported economic channel: " + key);
            }
            if (factor == null || typed.putIfAbsent(channel, factor) != null) {
                throw new IllegalArgumentException("each economic channel must have one factor: " + channel.id());
            }
        });
        this.economicHorizon = economicHorizon;
        this.channels = Collections.unmodifiableMap(new EnumMap<>(typed));
    }

    public int economicHorizon() {
        return economicHorizon;
    }

    public Map<EconomicChannel, EconomicFactor> channels() {
        return channels;
    }

    public EconomicFactor factor(EconomicChannel channel) {
        return channels.get(channel);
    }

    public Map<String, EconomicFactor> factors() {
        Map<String, EconomicFactor> result = new LinkedHashMap<>();
        channels.forEach((channel, factor) -> result.put(channel.id(), factor));
        return Collections.unmodifiableMap(result);
    }

    public Map<String, String> unknownCoreFactors() {
        Map<String, String> unknown = new TreeMap<>();
        for (EconomicChannel channel : EconomicChannel.values()) {
            EconomicFactor factor = channels.get(channel);
            if (factor == null || factor.isUnknown()) {
                unknown.put(channel.id(), factor == null
                    ? "core factor applicability was not reported"
                    : factor.reason());
            }
        }
        return Map.copyOf(unknown);
    }

    public boolean isCoreComplete() {
        return unknownCoreFactors().isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CostVector vector
            && economicHorizon == vector.economicHorizon
            && channels.equals(vector.channels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(economicHorizon, channels);
    }

    @Override
    public String toString() {
        return "CostVector[economicHorizon=" + economicHorizon + ", factors=" + factors() + "]";
    }
}
