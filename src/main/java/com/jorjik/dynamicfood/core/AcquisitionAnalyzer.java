package com.jorjik.dynamicfood.core;

import java.util.List;

public interface AcquisitionAnalyzer {
    boolean supports(String itemId);

    List<AcquisitionPath> analyze(String itemId);
}