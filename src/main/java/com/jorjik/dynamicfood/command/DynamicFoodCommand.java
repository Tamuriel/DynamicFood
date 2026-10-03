package com.jorjik.dynamicfood.command;

import com.jorjik.dynamicfood.data.DynamicFoodDataComponents;
import com.jorjik.dynamicfood.DynamicFood;
import com.jorjik.dynamicfood.core.CalibrationSnapshot;
import com.jorjik.dynamicfood.core.CalibratedFoodValue;
import com.jorjik.dynamicfood.provenance.DynamicFoodValue;
import com.jorjik.dynamicfood.provenance.SaturationConverter;
import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class DynamicFoodCommand {
    private DynamicFoodCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dynamicfood")
            .then(Commands.literal("inspect")
                .executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var stack = player.getMainHandItem();
                    String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    DynamicFoodValue value = stack.get(DynamicFoodDataComponents.VALUE.get());
                    var vanillaFood = stack.get(DataComponents.FOOD);
                    StringBuilder output = new StringBuilder("Item: ").append(itemId);
                    var economic = DynamicFood.ENGINE.recipeEconomicResult(itemId);
                    var resolvedEconomic = DynamicFood.ENGINE.economicCostResolution(itemId);
                    var resourceDifficulty = DynamicFood.ENGINE.resourceDifficulty(itemId);
                    output.append("\nEconomic cost: ").append(economic.economicCost() == null ? "UNKNOWN" : economic.economicCost())
                        .append(" (status=").append(economic.status()).append(')');
                    output.append("\nAcquisition resolution: ").append(resolvedEconomic.economicCost() == null
                        ? "UNKNOWN" : resolvedEconomic.economicCost())
                        .append(" (status=").append(resolvedEconomic.status())
                        .append(", horizon=").append(resolvedEconomic.economicHorizon())
                        .append(", confidence=").append(resolvedEconomic.confidence()).append(')');
                    if (!resolvedEconomic.reasons().isEmpty()) {
                        output.append("\nAcquisition diagnostics: ")
                            .append(String.join("; ", resolvedEconomic.reasons()));
                    }
                    var survival = DynamicFood.ENGINE.survivalAcquirability(itemId);
                    output.append("\nSurvival acquirability: ").append(survival.state())
                        .append(" (").append(survival.explanation()).append(')');
                    DynamicFood.ENGINE.economicProfile(itemId).ifPresent(profile -> output
                        .append("\nEconomic resource identity: ").append(profile.economicResourceIdentity())
                        .append("\nEconomic profile: terminal=").append(profile.terminal())
                        .append(", calibration eligible=").append(profile.calibrationEligible())
                        .append(", calibration weight=").append(profile.calibrationWeight())
                        .append(", technical=").append(profile.technical())
                        .append(", derived=").append(profile.derived()));
                    if (resolvedEconomic.primaryPath() != null) {
                        output.append("\nAcquisition path: ").append(resolvedEconomic.primaryPath().sourceType())
                            .append('/').append(resolvedEconomic.primaryPath().sourceId());
                    }
                    output.append("\nResource difficulty: ")
                        .append(resourceDifficulty.score() == null ? "UNKNOWN" : resourceDifficulty.score())
                        .append(" / 5 (confidence=").append(resourceDifficulty.confidence()).append(')');
                    output.append("\nManual override: ")
                        .append(resourceDifficulty.manualOverride()
                            ? resourceDifficulty.manualOverrideValue() : "none")
                        .append("; final difficulty: ")
                        .append(resourceDifficulty.score() == null ? "UNKNOWN" : resourceDifficulty.score());
                    if (resourceDifficulty.paths().isEmpty()) {
                        output.append("\nAcquisition paths: none (unknown acquisition source)");
                    } else {
                        output.append("\nAcquisition paths:");
                        for (var path : resourceDifficulty.paths()) {
                            output.append("\n  ").append(path.path().sourceType()).append('/')
                                .append(path.path().sourceId()).append(" [confidence=")
                                .append(path.path().confidence()).append(", feasibility=")
                                .append(path.feasibility().feasibility() == null
                                    ? "UNKNOWN" : path.feasibility().feasibility())
                                .append(", feasibility status=").append(path.feasibility().status())
                                .append(", coverage=").append(path.feasibility().coverage())
                                .append(", acquisition cost=")
                                .append(path.cost().cost() == null ? "UNKNOWN" : path.cost().cost())
                                .append(", cost status=").append(path.cost().status()).append(']');
                            if (!path.feasibility().missingFactors().isEmpty()) {
                                output.append("\n    Missing feasibility factors: ")
                                    .append(String.join(", ", path.feasibility().missingFactors()));
                            }
                            if (!path.feasibility().notApplicableFactors().isEmpty()) {
                                output.append("\n    Not-applicable feasibility factors: ")
                                    .append(String.join(", ", path.feasibility().notApplicableFactors()));
                            }
                            if (!path.cost().normalizedFactors().isEmpty()) {
                                output.append("\n    Cost factors: ").append(path.cost().normalizedFactors());
                            }
                            if (!path.cost().missingFactors().isEmpty()) {
                                output.append("\n    Unknown cost factors: ").append(path.cost().missingFactors());
                            }
                            if (!path.cost().notApplicableFactors().isEmpty()) {
                                output.append("\n    Not-applicable cost factors: ")
                                    .append(path.cost().notApplicableFactors());
                            }
                            if (!path.path().evidence().measurements().isEmpty()) {
                                output.append("\n    Observed mechanics: ")
                                    .append(path.path().evidence().measurements());
                            }
                            if (!path.path().evidence().attributes().isEmpty()) {
                                output.append("\n    Source attributes: ")
                                    .append(path.path().evidence().attributes());
                            }
                            for (int horizon : List.of(1, 10, 100)) {
                                var vector = path.path().costsByHorizon().get(horizon);
                                if (vector != null) {
                                    output.append("\n    Horizon ").append(horizon)
                                        .append(" cost vector: ").append(vector.factors());
                                }
                            }
                        }
                    }
                    if (!economic.recipePath().isEmpty()) {
                        output.append("\nEconomic path: ").append(String.join(" -> ", economic.recipePath()));
                    }
                    if (!economic.detectedCycles().isEmpty()) {
                        output.append("\nEconomic cycles: ").append(String.join("; ", economic.detectedCycles()));
                    }
                    if (!economic.missingInputs().isEmpty()) {
                        output.append("\nUnknown economic inputs: ").append(String.join("; ", economic.missingInputs()));
                    }
                    output.append("\nAcquisition discovery:");
                    DynamicFood.ENGINE.calibrationDiscoveryDiagnostics()
                        .forEach(line -> output.append("\n  ").append(line));
                    List<String> recipeTree = DynamicFood.ENGINE.recipeTree(itemId);
                    if (!recipeTree.isEmpty()) {
                        output.append("\nRecipe/operation tree:");
                        recipeTree.forEach(line -> output.append("\n").append(line));
                    }
                    if (vanillaFood != null) {
                        output.append("\nFoodProperties: nutrition=").append(vanillaFood.nutrition())
                            .append(", effective saturation points=").append(SaturationConverter.foodPropertiesToEffective(
                                vanillaFood.nutrition(), vanillaFood.saturation()))
                            .append(", equivalent builder saturation modifier=").append(SaturationConverter.effectiveToModifier(
                                vanillaFood.nutrition(), vanillaFood.saturation()));
                    }
                    if (value != null) {
                        output.append("\nDynamic: raw nutrition=").append(value.rawNutrition())
                            .append(", nutrition=").append(value.nutrition())
                            .append(", raw effective saturation=").append(value.rawSaturation())
                            .append(", effective saturation=").append(value.saturation())
                            .append(", equivalent builder saturation modifier=").append(SaturationConverter.effectiveToModifier(
                                    value.nutrition(), value.saturation()))
                            .append("\nRecipe: ").append(value.sourceRecipe())
                            .append("\nOutput count: ").append(value.outputCount());
                        double componentNutrition = value.components().stream()
                            .mapToDouble(component -> component.nutrition() * component.count()).sum();
                        double componentSaturation = value.components().stream()
                            .mapToDouble(component -> component.saturation() * component.count()).sum();
                        output.append("\nComponent sum: nutrition=").append(componentNutrition)
                            .append(", effective saturation=").append(componentSaturation);
                        value.operationSnapshot().ifPresent(operation -> output
                            .append("\nOperation: type=").append(operation.recipeType())
                            .append(", station=").append(operation.station())
                            .append(", station difficulty=").append(operation.stationDifficulty())
                            .append("\n  Actual item inputs: ").append(operation.itemInputs().isEmpty()
                                ? "none" : operation.itemInputs())
                            .append("\n  Fluid inputs: ").append(operation.fluidInputs().isEmpty()
                                ? "none" : operation.fluidInputs())
                            .append("\n  Processing metadata: ").append(operation.processingMetadata().isEmpty()
                                ? "none" : operation.processingMetadata())
                            .append("\n  Output allocation share: ").append(operation.allocationShare())
                            .append("\n  Operation component sum: nutrition=").append(operation.componentNutrition())
                            .append(", effective saturation=").append(operation.componentSaturation())
                            .append("\n  Processing/station/complexity bonus: nutrition=")
                                .append(operation.processingNutritionBonus())
                                .append(", effective saturation=").append(operation.processingSaturationBonus()));
                        if (value.operationSnapshot().isEmpty()) {
                            output.append("\nOperation breakdown: not captured for this stack's source");
                        }
                        for (var component : value.components()) {
                            output.append("\n  ").append(component.itemId()).append(" x").append(component.count())
                                .append(" [nutrition=").append(component.nutrition())
                                .append(", saturation=").append(component.saturation())
                                .append(", difficulty=").append(component.difficulty())
                                .append(", source=").append(component.sourceRecipe()).append(']');
                        }
                    } else {
                        DynamicFood.ENGINE.calibratedBaseFoodValue(itemId).ifPresent(base -> output
                            .append("\nBase: economic cost=").append(base.economicCost())
                            .append(", difficulty=").append(base.difficulty())
                            .append(", FoodIndex=").append(base.foodIndex())
                            .append(", nutrition=").append(base.nutrition())
                            .append(", effective saturation points=").append(base.effectiveSaturation())
                            .append(", calibration status=").append(base.calibrationStatus())
                            .append(", source=").append(base.source()));
                    }
                    DynamicFood.ENGINE.calibrationSnapshot().ifPresent(snapshot -> {
                        var calibrated = snapshot.calibratedValues().get(itemId);
                        if (calibrated != null) {
                            var profile = snapshot.population().stream()
                                .filter(candidate -> candidate.resourceId().equals(itemId))
                                .findFirst().orElse(null);
                            output.append("\nSelf-calibration: status=").append(snapshot.status())
                                .append(", population=").append(snapshot.populationSize())
                                .append(", candidate weight=").append(snapshot.candidatePopulationWeight())
                                .append(", resolved weight=").append(snapshot.resolvedPopulationWeight())
                                .append(", coverage=").append(snapshot.calibrationCoverage())
                                .append(", P05/P50/P95=").append(Math.expm1(snapshot.p05())).append('/')
                                .append(Math.expm1(snapshot.p50())).append('/')
                                .append(Math.expm1(snapshot.p95()))
                                .append(", magnitude/rank weights=").append(snapshot.magnitudeWeight())
                                .append('/').append(snapshot.rankWeight())
                                .append(", magnitude/rank=").append(calibrated.magnitudeComponent())
                                .append('/').append(calibrated.rankComponent())
                                .append(", FoodIndex=").append(calibrated.foodIndex())
                                .append(", calibration weight=").append(profile == null ? "unknown" : profile.calibrationWeight())
                                .append(", calibration group=").append(profile == null
                                    ? "unknown" : profile.economicResourceIdentity())
                                .append(", gameplay preset=").append(snapshot.gameplayPreset())
                                .append(", rank tie mode=").append(snapshot.rankTieMode())
                                .append(", hunger anchors=").append(snapshot.hungerAnchors())
                                .append(", saturation anchors=").append(snapshot.saturationAnchors());
                        }
                    });
                    context.getSource().sendSuccess(() -> Component.literal(
                        output.toString()), false);
                    return 1;
                }))
            .then(Commands.literal("calibration")
                .executes(context -> {
                    String report = calibrationReport();
                    context.getSource().sendSuccess(() -> Component.literal(report), false);
                    return 1;
                })));
    }

    private static String calibrationReport() {
        var snapshotOptional = DynamicFood.ENGINE.calibrationSnapshot();
        var settings = DynamicFood.ENGINE.calibrationSettings();
        if (snapshotOptional.isEmpty()) {
            return "Food calibration is disabled; mode=" + settings.disabledMode()
                + ", preset=" + settings.gameplayPreset();
        }
        CalibrationSnapshot snapshot = snapshotOptional.get();
        StringBuilder report = new StringBuilder("Calibration Status: ").append(snapshot.status())
            .append("\nPopulation Size: ").append(snapshot.populationSize())
            .append("\nCandidate Weight: ").append(snapshot.candidatePopulationWeight())
            .append("\nResolved Weight: ").append(snapshot.resolvedPopulationWeight())
            .append("\nCoverage: ").append(snapshot.calibrationCoverage()).append(" (").append(snapshot.coverageStatus()).append(')')
            .append("\nP05 Economic Cost: ").append(Math.expm1(snapshot.p05()))
            .append("\nP50 Economic Cost: ").append(Math.expm1(snapshot.p50()))
            .append("\nP95 Economic Cost: ").append(Math.expm1(snapshot.p95()))
            .append("\nMagnitude Weight: ").append(snapshot.magnitudeWeight())
            .append("\nRank Weight: ").append(snapshot.rankWeight())
            .append("\nGameplay Preset: ").append(snapshot.gameplayPreset())
            .append("\nRank Tie Mode: ").append(snapshot.rankTieMode())
            .append("\nHunger Anchors: ").append(snapshot.hungerAnchors())
            .append("\nSaturation Anchors: ").append(snapshot.saturationAnchors())
            .append("\nFoodIndex Distribution:");
        for (double quantile : new double[] {0.00D, 0.10D, 0.25D, 0.50D, 0.75D, 0.90D, 0.95D, 0.99D, 1.00D}) {
            report.append("\n  P").append((int) Math.round(quantile * 100.0D)).append(": ")
                .append(weightedFoodIndexQuantile(snapshot, quantile));
        }
        report.append("\nSignature: ").append(snapshot.signature());
        DynamicFood.ENGINE.calibrationDiscoveryDiagnostics()
            .forEach(line -> report.append("\n").append(line));
        return report.toString();
    }

    private static double weightedFoodIndexQuantile(CalibrationSnapshot snapshot, double quantile) {
        List<WeightedIndex> values = snapshot.population().stream()
            .map(profile -> {
                CalibratedFoodValue calibrated = snapshot.calibratedValues().get(profile.resourceId());
                return calibrated == null ? null : new WeightedIndex(calibrated.foodIndex(), profile.calibrationWeight());
            })
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingDouble(WeightedIndex::index))
            .toList();
        double totalWeight = values.stream().mapToDouble(WeightedIndex::weight).sum();
        if (values.isEmpty() || totalWeight <= 0.0D) {
            return 0.0D;
        }
        double threshold = quantile * totalWeight;
        double cumulative = 0.0D;
        for (WeightedIndex value : values) {
            cumulative += value.weight();
            if (cumulative >= threshold) {
                return value.index();
            }
        }
        return values.getLast().index();
    }

    private record WeightedIndex(double index, double weight) {
    }
}
