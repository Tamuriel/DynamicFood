package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Set;

/** Applies resolved provider evidence to the production paths identified by the contribution source. */
final class EffectiveMechanicAcquisitionAnalyzer implements AcquisitionAnalyzer {
    private final AcquisitionAnalyzer delegate;
    private final EffectiveMechanicModel model;

    EffectiveMechanicAcquisitionAnalyzer(AcquisitionAnalyzer delegate, EffectiveMechanicModel model) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate is required");
        this.model = java.util.Objects.requireNonNull(model, "effective mechanic model is required");
    }

    @Override
    public boolean supports(String itemId) {
        return delegate.supports(itemId);
    }

    @Override
    public List<AcquisitionPath> analyze(String itemId) {
        return delegate.analyze(itemId).stream().map(model::applyTo).toList();
    }

    @Override
    public Set<String> indexedItemIds() {
        return delegate.indexedItemIds();
    }
}
