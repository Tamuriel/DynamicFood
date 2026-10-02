package com.jorjik.dynamicfood.provenance;

public final class SaturationConverter {
    private static final double EFFECTIVE_SATURATION_FACTOR = 2.0D;

    private SaturationConverter() {}

    public static double modifierToEffective(int nutrition, double saturationModifier) {
        if (nutrition <= 0 || !Double.isFinite(saturationModifier)) {
            return 0.0D;
        }
        return EFFECTIVE_SATURATION_FACTOR * nutrition * Math.max(0.0D, saturationModifier);
    }

    public static double effectiveToModifier(int nutrition, double effectiveSaturation) {
        if (nutrition <= 0 || !Double.isFinite(effectiveSaturation)) {
            return 0.0D;
        }
        return Math.max(0.0D, effectiveSaturation) / (EFFECTIVE_SATURATION_FACTOR * nutrition);
    }
    public static double foodPropertiesToEffective(int nutrition, double storedSaturation) {
        return nutrition <= 0 || !Double.isFinite(storedSaturation)
            ? 0.0D : Math.max(0.0D, storedSaturation);
    }
    public static double effectiveToFoodProperties(int nutrition, double effectiveSaturation) {
        return nutrition <= 0 || !Double.isFinite(effectiveSaturation)
            ? 0.0D : Math.max(0.0D, effectiveSaturation);
    }
}