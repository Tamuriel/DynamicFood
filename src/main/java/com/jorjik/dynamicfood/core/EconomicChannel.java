package com.jorjik.dynamicfood.core;

import java.util.Arrays;
import java.util.Optional;

public enum EconomicChannel {
    QUANTITY("quantity_cost"),
    PROBABILITY_BURDEN("probability_burden"),
    MATERIAL_CONSUMPTION("material_cost"),
    EQUIPMENT_ECONOMIC_BURDEN("equipment_cost");

    private final String id;

    EconomicChannel(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<EconomicChannel> fromId(String id) {
        return Arrays.stream(values()).filter(channel -> channel.id.equals(id)).findFirst();
    }
}
