package com.jorjik.dynamicfood.config;

public record ItemFoodProfile(
    String itemId,
    double nutrition,
    double saturation,
    int difficulty,
    boolean foodComponent,
    boolean enabled
) {
    public static ItemFoodProfile parse(String entry) {
        String[] fields = entry == null ? new String[0] : entry.split("\\|", -1);
        if (fields.length != 6) {
            return null;
        }
        try {
            String itemId = fields[0].trim();
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                || !isBoolean(fields[4]) || !isBoolean(fields[5])) {
                return null;
            }
            int difficulty = Integer.parseInt(fields[3].trim());
            if (difficulty < 0 || difficulty > 5) {
                return null;
            }
            double nutrition = Double.parseDouble(fields[1].trim());
            double saturation = Double.parseDouble(fields[2].trim());
            if (!Double.isFinite(nutrition) || !Double.isFinite(saturation)
                || nutrition < -1.0D || saturation < -1.0D
                || (nutrition < 0.0D && nutrition != -1.0D)
                || (saturation < 0.0D && saturation != -1.0D)) {
                return null;
            }
            return new ItemFoodProfile(
                itemId,
                nutrition,
                saturation,
                difficulty,
                Boolean.parseBoolean(fields[4].trim()),
                Boolean.parseBoolean(fields[5].trim())
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isBoolean(String value) {
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("false");
    }

    public double nutritionOverrideOr(double automaticValue) {
        return nutrition < 0.0D ? Math.max(0.0D, automaticValue) : nutrition;
    }

    public double saturationOverrideOr(double automaticValue) {
        return saturation < 0.0D ? Math.max(0.0D, automaticValue) : saturation;
    }
}
