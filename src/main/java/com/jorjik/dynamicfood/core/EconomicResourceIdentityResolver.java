package com.jorjik.dynamicfood.core;

import java.util.Objects;

public final class EconomicResourceIdentityResolver {
    public EconomicResourceIdentity resolve(ResourceEconomicProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new EconomicResourceIdentity(profile.economicResourceIdentity());
    }
}
