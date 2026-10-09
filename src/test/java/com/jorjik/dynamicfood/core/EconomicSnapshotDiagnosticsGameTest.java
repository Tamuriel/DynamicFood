package com.jorjik.dynamicfood.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jorjik.dynamicfood.DynamicFood;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Emits diagnostics only when explicitly executed by a GameTest server. */
@GameTestHolder(DynamicFood.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EconomicSnapshotDiagnosticsGameTest {
    private static final List<String> FACTORS = List.of(
        "quantity", "probability_burden", "material", "equipment");

    private EconomicSnapshotDiagnosticsGameTest() {}

    @GameTest(template = "bastion/blocks/air", templateNamespace = "minecraft")
    public static void writesCompleteProductionEconomicDiagnostics(GameTestHelper helper) throws IOException {
        PublishedEconomicGeneration generation = DynamicFood.ECONOMIC_GENERATIONS.current().orElse(null);
        helper.assertTrue(generation != null,
            "diagnostic report requires the normal startup production economic generation");
        EconomicSnapshot snapshot = generation.economicSnapshot();
        JsonObject report = EconomicSnapshotDiagnosticReport.create(generation);

        Path reportPath = Path.of("reports", "dynamicfood-economic-diagnostics.json");
        Files.createDirectories(reportPath.getParent());
        String serialized = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report);
        Files.writeString(reportPath, serialized, StandardCharsets.UTF_8);
        JsonObject parsed = JsonParser.parseString(
            Files.readString(reportPath, StandardCharsets.UTF_8)).getAsJsonObject();

        helper.assertTrue(parsed.get("schema").getAsInt() == 1
                && parsed.get("economicSnapshotSchema").getAsInt() == EconomicSnapshotBuilder.SCHEMA_VERSION
                && parsed.get("generation").getAsLong() == generation.generation()
                && parsed.get("economicPolicySignature").getAsString().equals(
                    com.jorjik.dynamicfood.config.DynamicFoodConfig.economicPolicySignature())
                && parsed.get("snapshotSignature").getAsString().equals(snapshot.signature()),
            "the report must be valid JSON for the exact published policy and snapshot generation");
        JsonArray resources = parsed.getAsJsonArray("resources");
        JsonObject summary = parsed.getAsJsonObject("summary");
        helper.assertTrue(resources.size() == snapshot.inputSet().resourceIds().size()
                && summary.get("resources").getAsInt() == snapshot.resources().size(),
            "the report must retain every resource in the production generation input");
        JsonObject acaciaLog = resources.asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(resource -> resource.get("resourceId").getAsString().equals("minecraft:acacia_log"))
            .findFirst().orElse(null);
        helper.assertTrue(acaciaLog != null, "the production snapshot must retain the acacia-log resource");
        JsonObject acaciaWorldgen = acaciaLog == null ? null : acaciaLog.getAsJsonArray("paths").asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(path -> path.get("sourceType").getAsString().equals("worldgen_feature")
                && path.getAsJsonObject("diagnostics").getAsJsonObject("evidence").getAsJsonObject("attributes")
                    .get("worldgen_block_id").getAsString().equals("minecraft:acacia_log"))
            .findFirst().orElse(null);
        helper.assertTrue(acaciaWorldgen != null,
            "the savanna tree feature and block-break extraction must be linked in production evidence");
        boolean activeOverworldBiomeEvidence = snapshot.resources().values().stream()
            .flatMap(resource -> resource.acquisitionPaths().stream())
            .filter(path -> path.sourceType().equals("worldgen_feature"))
            .map(path -> path.evidence().attributes().getOrDefault("dimension", ""))
            .anyMatch(dimension -> dimension.contains("minecraft:overworld"));
        helper.assertTrue(activeOverworldBiomeEvidence,
            "production worldgen paths must retain biome membership from an active Overworld biome source");
        if (acaciaWorldgen != null) {
            helper.assertTrue(acaciaWorldgen.getAsJsonObject("availability")
                    .get("classification").getAsString().equals("UNKNOWN"),
                "worldgen tree discovery alone must not establish survival availability");
        }

        long pathCount = 0;
        for (var resourceElement : resources) {
            JsonObject resource = resourceElement.getAsJsonObject();
            JsonArray paths = resource.getAsJsonArray("paths");
            pathCount += paths.size();
            for (var pathElement : paths) {
                JsonObject path = pathElement.getAsJsonObject();
                JsonObject factors = path.getAsJsonObject("costVector").getAsJsonObject("factors");
                helper.assertTrue(factors.size() == FACTORS.size()
                        && FACTORS.stream().allMatch(factors::has),
                    "every discovered path must serialize the four canonical economic channels");
                helper.assertTrue(path.has("pipeline") && path.has("acquisitionCost")
                        && path.has("economicResolution") && path.has("diagnostics"),
                    "every path must retain pipeline, cost, resource-resolution and evidence diagnostics");
            }
        }
        helper.assertTrue(pathCount == summary.get("paths").getAsLong()
                && summary.get("coreCompleteVectors").getAsLong()
                    + summary.get("coreIncompleteVectors").getAsLong() == pathCount
                && summary.get("acquisitionCostResolved").getAsLong()
                    + summary.get("acquisitionCostUnknown").getAsLong() == pathCount
                && summary.get("economicCostResolved").getAsLong()
                    + summary.get("economicCostUnknown").getAsLong() == resources.size(),
            "resource, vector and cost totals must reconcile with serialized paths");

        long analyzerPathCount = 0;
        JsonObject perAnalyzer = parsed.getAsJsonObject("perAnalyzer");
        for (String analyzer : List.of("recipe", "loot", "crop", "worldgen", "trade", "other")) {
            analyzerPathCount += perAnalyzer.getAsJsonObject(analyzer).get("paths").getAsLong();
        }
        long firstBlockers = 0;
        for (var blocker : summary.getAsJsonObject("firstBlockerCounts").entrySet()) {
            firstBlockers += blocker.getValue().getAsLong();
        }
        helper.assertTrue(analyzerPathCount == pathCount && firstBlockers == pathCount
                && summary.get("resourcesWithAtLeastOnePath").getAsLong()
                    + summary.get("resourcesWithoutPath").getAsLong() == resources.size(),
            "per-analyzer, first-blocker and no-path aggregates must reconcile");
        helper.succeed();
    }
}
