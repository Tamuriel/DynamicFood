package com.jorjik.dynamicfood.core;

import java.util.Collection;
import java.util.List;

public interface MechanicProvider {
    String providerId();

    default String providerVersion() {
        return getClass().getName();
    }

    default Collection<ProviderContribution> contributions() {
        return List.of();
    }
}
