package com.jorjik.dynamicfood.config;

import com.jorjik.dynamicfood.core.ResourceEconomicProfile;
import com.jorjik.dynamicfood.core.SurvivalAcquirability;

public record EconomicProfileOverride(String itemId, double economicCost, double calibrationWeight,
    String calibrationGroup, SurvivalAcquirability survivalAcquirability) {
    public EconomicProfileOverride {
        if (itemId == null || itemId.isBlank() || !Double.isFinite(economicCost)
            || economicCost < 0.0D || economicCost > 1.0D
            || !Double.isFinite(calibrationWeight) || calibrationWeight <= 0.0D
            || calibrationGroup == null || calibrationGroup.isBlank() || survivalAcquirability == null) {
            throw new IllegalArgumentException("economic profile override requires valid identity, cost in [0,1], "
                + "positive calibration weight, group and survival state");
        }
    }

    public static EconomicProfileOverride parse(String entry) {
        String[] parts = entry == null ? new String[0] : entry.split("\\|", -1);
        if (parts.length != 5 || parts[0].isBlank()) {
            return null;
        }
        try {
            double cost = Double.parseDouble(parts[1].trim());
            double weight = Double.parseDouble(parts[2].trim());
            if (!Double.isFinite(cost) || cost < 0.0D || cost > 1.0D
                || !Double.isFinite(weight) || weight <= 0.0D) {
                return null;
            }
            SurvivalAcquirability survival = SurvivalAcquirability.valueOf(parts[4].trim().toUpperCase(java.util.Locale.ROOT));
            String group = parts[3].isBlank() || parts[3].equals("-") ? parts[0].trim() : parts[3].trim();
            return new EconomicProfileOverride(parts[0].trim(), cost, weight, group, survival);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public ResourceEconomicProfile toProfile() {
        return new ResourceEconomicProfile(itemId, calibrationGroup, economicCost, 1.0D, calibrationWeight,
            true, true, survivalAcquirability, false, false);
    }
}