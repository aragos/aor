package dev.aragos.aor.training;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aragos.aor.AdvanceId;
import dev.aragos.aor.Entry;
import dev.aragos.aor.GameState;
import dev.aragos.aor.GameView;
import dev.aragos.aor.Prompt;
import dev.aragos.aor.Stage;
import dev.aragos.aor.TextCodec;
import dev.aragos.aor.ai.BotModel;
import dev.aragos.aor.ai.BotModels;
import dev.aragos.aor.ai.BotObservations;
import dev.aragos.aor.ai.BotPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only Wind/Watermill coverage on the explicit completed family55 histories. */
public final class WindmillCoverageAudit {
    private static final String M8 =
        "b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4";

    private static void need(boolean value, String message) {
        AdjustmentAliasAudit.need(value, message);
    }

    private static void verifyPins(JsonNode manifest) throws Exception {
        for (var pin : manifest.required("completionPins").properties()) {
            need(PurchaseJson.INSTANCE.hash(Path.of(pin.getKey())).equals(pin.getValue().asText()),
                "The completed synthetic dataset admission changed.");
        }
    }

    private static Map<String, Object> window(
        int origin, int number, GameState state, Entry.Action action, String kind, BotModel model
    ) {
        var actor = action.getActor();
        GameView view = state.viewForDecision(actor);
        var observation = BotObservations.INSTANCE.from(state, actor);
        var offered = state.legalChoices(actor).getCommands();
        need(Set.copyOf(offered).equals(Set.copyOf(observation.getCandidates().stream()
            .map(candidate -> candidate.getCommand()).toList())),
            "Every authoritative Wind/Watermill action must be available to the ordinary actor.");
        String chosen = action.getCommand().toText();
        need(offered.contains(chosen), "The recorded Wind/Watermill action was not offered.");
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (var candidate : observation.getCandidates()) {
            candidates.add(Map.of("command", candidate.getCommand(),
                "features", candidate.getFeatures(), "score", BotPolicy.INSTANCE.score(candidate, model)));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("origin", origin);
        row.put("entry", number);
        row.put("seat", state.getPlayerIds().indexOf(actor));
        row.put("turn", state.getTurnNumber());
        row.put("epoch", state.getCurrentEpoch().getNumber());
        row.put("decision", kind);
        row.put("offered", offered);
        row.put("chosen", chosen);
        row.put("ownHandCount", view.getHand().size());
        row.put("ownBurdenCount", view.getBurdenReferences().size());
        row.put("publicHandCounts", view.getPlayers().stream().map(player ->
            Map.of("player", player.getId().getValue(), "handCount", player.getHandCount())).toList());
        row.put("candidateScores", candidates);
        if (view.getWindmillOffer() != null) {
            row.put("publicOffer", view.getWindmillOffer());
        }
        // No rival card identity, hidden draw, seed or future return is inspected or emitted.
        return row;
    }

    public static void main(String[] args) throws Exception {
        need(args.length == 3, "Use explicit family55 manifest, raw M8 model, and new output.");
        Path manifestPath = Path.of(args[0]), modelPath = Path.of(args[1]), output = Path.of(args[2]);
        need(!Files.exists(output), "Preserve earlier audit outputs.");
        String manifestHash = PurchaseJson.INSTANCE.hash(manifestPath);
        JsonNode manifest = PurchaseJson.INSTANCE.read(manifestPath);
        need(manifest.required("kind").asText().equals("renaissance-synthetic-coverage-input-1"),
            "Only the explicitly admitted completed family55 manifest is supported.");
        verifyPins(manifest);
        need(PurchaseJson.INSTANCE.hash(modelPath).equals(M8), "Use exact raw M8 for descriptive scores.");
        JsonNode modelNode = PurchaseJson.INSTANCE.read(modelPath);
        Map<String, Double> weights = new LinkedHashMap<>();
        modelNode.required("weights").properties().forEach(entry ->
            weights.put(entry.getKey(), entry.getValue().doubleValue()));
        BotModel model = BotModels.INSTANCE.fromSerializedWeights(weights,
            modelNode.required("schemaVersion").intValue());
        List<Map<String, Object>> windows = new ArrayList<>();
        List<Map<String, Object>> origins = new ArrayList<>();
        int entries = 0;
        long deadline = System.nanoTime() + 180_000_000_000L;
        need(manifest.required("origins").size() == 256, "Retain all256 completed synthetic origins.");
        for (JsonNode source : manifest.required("origins")) {
            int index = source.required("index").intValue();
            need(index == origins.size(), "Prospective origin order must remain exact.");
            Path path = Path.of(source.required("path").asText());
            String expected = source.required("sha256").asText();
            need(PurchaseJson.INSTANCE.hash(path).equals(expected), "A completed origin changed.");
            JsonNode data = PurchaseJson.INSTANCE.read(path), backbone = data.required("backbone");
            need(data.required("request").required("index").intValue() == index,
                "Completed origin identity changed.");
            List<String> history = new ArrayList<>();
            backbone.required("entries").forEach(value -> history.add(value.asText()));
            need(backbone.required("replayVerified").booleanValue()
                && CardAdvantageExecutor.Companion.hash(history).equals(
                    backbone.required("canonicalSha256").asText()),
                "The complete canonical history receipt changed.");
            GameState state = AdjustmentAliasAudit.initial(backbone.required("header"));
            Set<Integer> owners = new java.util.TreeSet<>();
            int before = windows.size();
            for (int number = 0; number < history.size(); number++) {
                need(System.nanoTime() < deadline, "The read-only three-minute audit expired.");
                Entry entry = TextCodec.INSTANCE.parse(history.get(number));
                var prompts = state.prompts();
                if (entry instanceof Entry.Action action && prompts.size() == 1
                    && prompts.getFirst() instanceof Prompt.SinglePlayer prompt
                    && Set.of("windmill", "offer-card").contains(prompt.getChoice())) {
                    need(action.getActor().equals(prompt.getPlayer()),
                        "The recorded action must belong to the Wind/Watermill actor.");
                    windows.add(window(index, number, state, action, prompt.getChoice(), model));
                }
                state = state.step(entry);
                for (int seat = 0; seat < state.getPlayerIds().size(); seat++) {
                    if (state.playerState(state.getPlayerIds().get(seat)).getAdvances()
                        .owns(AdvanceId.WIND_WATERMILL)) {
                        owners.add(seat);
                    }
                }
                entries++;
            }
            need(state.getStage() == Stage.FINISHED, "Every completed backbone must replay to its end.");
            need(PurchaseJson.INSTANCE.hash(path).equals(expected), "An origin changed during replay.");
            origins.add(Map.of("index", index, "sourceSha256", expected,
                "everWindmillSeats", owners, "canonicalEntries", history.size(),
                "windows", windows.size() - before));
        }
        verifyPins(manifest);
        need(PurchaseJson.INSTANCE.hash(manifestPath).equals(manifestHash), "The manifest changed.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "windmill-read-only-coverage-1");
        result.put("manifestSha256", manifestHash);
        result.put("scoreModelSha256", M8);
        result.put("origins", origins);
        result.put("windows", windows);
        result.put("canonicalEntries", entries);
        result.put("newGames", 0);
        result.put("newTails", 0);
        result.put("fits", 0);
        result.put("controllerCalls", 0);
        result.put("hiddenRivalCardIdentitiesOutput", false);
        result.put("terminalReturnsInspected", false);
        Files.writeString(output, PurchaseJson.INSTANCE.getMapper().writeValueAsString(result) + "\n");
    }
}
