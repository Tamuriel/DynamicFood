package com.jorjik.dynamicfood.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AcquisitionPathDeduplicator {
    private AcquisitionPathDeduplicator() {
    }

    public static List<AcquisitionPath> deduplicate(List<AcquisitionPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Map<String, AcquisitionPath> deduped = new LinkedHashMap<>();
        for (AcquisitionPath path : paths) {
            String identity = PathIdentity.fromMechanicalIdentity(
                path.itemId(),
                path.sourceType(),
                path.sourceId(),
                AcquisitionRequirements.empty(),
                null,
                path.economicCostSchedule() == null ? "default" : String.valueOf(path.economicCostSchedule().hashCode())
            ).canonicalKey();
            deduped.putIfAbsent(identity, path);
        }
        return List.copyOf(new ArrayList<>(deduped.values()));
    }
}
