package com.jorjik.dynamicfood.config;

import com.jorjik.dynamicfood.core.IngredientContribution;
import net.neoforged.neoforge.fluids.FluidStack;

public record FluidFoodProfile(String fluidId, double nutrition, double effectiveSaturation,
    double valueUnitMb, boolean foodComponent, boolean enabled) {
    public FluidFoodProfile {
        if (fluidId == null || !fluidId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
            || !Double.isFinite(nutrition) || nutrition < 0.0D
            || !Double.isFinite(effectiveSaturation) || effectiveSaturation < 0.0D
            || !Double.isFinite(valueUnitMb) || valueUnitMb <= 0.0D) {
            throw new IllegalArgumentException("invalid fluid food profile");
        }
    }

    public static FluidFoodProfile parse(String entry) {
        String[] parts = entry == null ? new String[0] : entry.split("\\|", -1);
        if (parts.length != 6 || parts[0].isBlank()) {
            return null;
        }
        try {
            if (!isBoolean(parts[4]) || !isBoolean(parts[5])) {
                return null;
            }
            return new FluidFoodProfile(parts[0].trim(), Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()),
                Boolean.parseBoolean(parts[4].trim()), Boolean.parseBoolean(parts[5].trim()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isBoolean(String value) {
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("false");
    }

    public IngredientContribution contribution(FluidStack stack) {
        if (!enabled || !foodComponent || stack.isEmpty()) {
            return new IngredientContribution(fluidId, 0.0D, 0.0D, 1, false, "fluid_profile_disabled");
        }
        double units = stack.getAmount() / valueUnitMb;
        return new IngredientContribution(fluidId, nutrition * units, effectiveSaturation * units,
            1, true, "fluid_profile:" + fluidId);
    }
}