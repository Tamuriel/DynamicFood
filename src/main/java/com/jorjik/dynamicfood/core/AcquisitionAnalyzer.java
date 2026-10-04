package com.jorjik.dynamicfood.core;

import java.util.List;
import java.util.Set;

public interface AcquisitionAnalyzer {
    boolean supports(String itemId);

    List<AcquisitionPath> analyze(String itemId);

    Set<String> indexedItemIds();
}