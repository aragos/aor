package dev.aragos.aor.training;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aragos.aor.Commodity;
import dev.aragos.aor.CommodityAdjustment;
import dev.aragos.aor.Entry;
import dev.aragos.aor.GameHeader;
import dev.aragos.aor.GameState;
import dev.aragos.aor.Player;
import dev.aragos.aor.PlayerId;
import dev.aragos.aor.Prompt;
import dev.aragos.aor.Stage;
import dev.aragos.aor.TextCodec;
import dev.aragos.aor.TurnCommand;
import dev.aragos.aor.ai.BotCandidate;
import dev.aragos.aor.ai.BotModel;
import dev.aragos.aor.ai.BotModels;
import dev.aragos.aor.ai.BotObservation;
import dev.aragos.aor.ai.BotObservations;
import dev.aragos.aor.ai.BotPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only canonical replay plus explicitly synthetic queue mirrors; no future simulation. */
public final class AdjustmentAliasAudit {
    static void need(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static GameState initial(JsonNode node) throws Exception {
        List<Player> players = new ArrayList<>();
        for (JsonNode player : node.required("players")) {
            players.add(new Player(new PlayerId(player.required("id").required("value").asText()),
                player.required("name").asText()));
        }
        GameHeader header = new GameHeader(players,
            new PlayerId(node.required("creatorId").required("value").asText()),
            node.required("rulesVersion").asText(), node.required("dataVersion").asText(),
            node.required("drawRenaissanceFromTurn").intValue());
        need(PurchaseJson.INSTANCE.getMapper().valueToTree(header).equals(node),
            "Preserve the exact retained header.");
        Constructor<?> constructor = Arrays.stream(GameState.class.getConstructors())
            .filter(value -> value.getParameterCount() == 24).findFirst().orElseThrow();
        Object[] values = new Object[24];
        for (int i = 0; i < 24; i++) {
            if (constructor.getParameterTypes()[i] == int.class) values[i] = 0;
            if (constructor.getParameterTypes()[i] == boolean.class) values[i] = false;
        }
        values[0] = header;
        values[22] = -2; // Kotlin defaults for all 22 constructor properties except header.
        return (GameState) constructor.newInstance(values);
    }

    static CommodityAdjustment opposite(CommodityAdjustment value) {
        return value == CommodityAdjustment.SHORTAGE
            ? CommodityAdjustment.SURPLUS : CommodityAdjustment.SHORTAGE;
    }

    static GameState mirror(GameState original, Set<Commodity> affected) throws Exception {
        Map<Commodity, List<CommodityAdjustment>> queues = new LinkedHashMap<>();
        original.getAdjustments().forEach((commodity, queue) -> queues.put(commodity,
            affected.contains(commodity) ? queue.stream().map(AdjustmentAliasAudit::opposite).toList()
                : queue));
        Method copy = Arrays.stream(GameState.class.getMethods())
            .filter(method -> method.getName().equals("copy")).findFirst().orElseThrow();
        need(copy.getParameterCount() == 22, "Bind the unchanged frozen GameState owner shape.");
        Object[] components = new Object[22];
        for (int i = 0; i < 22; i++) {
            components[i] = GameState.class.getMethod("component" + (i + 1)).invoke(original);
        }
        need(components[18].equals(original.getAdjustments()), "Only adjustment queues may change.");
        components[18] = queues;
        GameState mirrored = (GameState) copy.invoke(original, components);
        for (int i = 0; i < 22; i++) {
            need(java.util.Objects.equals(components[i],
                GameState.class.getMethod("component" + (i + 1)).invoke(mirrored)),
                "Synthetic mirror must preserve every other authoritative state component.");
        }
        return mirrored;
    }

    static BotCandidate candidate(BotObservation observation, String command) {
        return observation.getCandidates().stream().filter(c -> c.getCommand().equals(command))
            .findFirst().orElseThrow();
    }

    static Map<String, Object> fixture(GameState state, PlayerId actor, String command,
                                       BotModel model) throws Exception {
        TurnCommand.CancelAdjustment cancel =
            (TurnCommand.CancelAdjustment) TextCodec.INSTANCE.parseCommand(command);
        Set<Commodity> affected = Set.of(Commodity.GOLD, Commodity.IVORY)
            .contains(cancel.getCommodity()) ? Set.of(Commodity.GOLD, Commodity.IVORY)
                : Set.of(cancel.getCommodity());
        GameState mirrored = mirror(state, affected);
        String otherCommand = new TurnCommand.CancelAdjustment(cancel.getCommodity(),
            opposite(cancel.getAdjustment())).toText();
        List<Map<String, Object>> sides = new ArrayList<>();
        List<BotObservation> observations = new ArrayList<>();
        List<BotCandidate> candidates = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            GameState current = side == 0 ? state : mirrored;
            String offered = side == 0 ? command : otherCommand;
            need(current.legalChoices(actor).getCommands().contains(offered),
                "The mirrored cancellation must remain authoritatively offered.");
            BotObservation observation = BotObservations.INSTANCE.from(current, actor);
            BotCandidate option = candidate(observation, offered);
            GameState after = current.step(new Entry.Action(actor,
                TextCodec.INSTANCE.parseCommand(offered)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", offered);
            result.put("affectedQueues", current.getAdjustments().entrySet().stream()
                .filter(e -> affected.contains(e.getKey())).toList());
            result.put("candidateFeatures", option.getFeatures());
            result.put("context", observation.getContext());
            result.put("cashCost", option.getCashCost());
            result.put("relativePassScore", BotPolicy.INSTANCE.score(option, model)
                - BotPolicy.INSTANCE.score(candidate(observation, "pass"), model));
            result.put("publicPayoutsBefore", current.publicView().getCommodityPayouts().stream()
                .filter(quote -> affected.contains(quote.getCommodity())).toList());
            result.put("publicPayoutsAfter", after.publicView().getCommodityPayouts().stream()
                .filter(quote -> affected.contains(quote.getCommodity())).toList());
            result.put("cancellationQuote", current.viewForDecision(actor).getAdjustmentOffers()
                .stream().filter(quote -> quote.getCommand().equals(offered)).toList());
            sides.add(result); observations.add(observation); candidates.add(option);
        }
        return Map.of("syntheticNotReachabilityClaim", true, "wholeAffectedQueuesMirrored", true,
            "goldIvoryTogether", affected.contains(Commodity.GOLD), "sides", sides,
            "candidateFeatureAlias", candidates.get(0).getFeatures().equals(candidates.get(1).getFeatures()),
            "contextAlias", observations.get(0).getContext().equals(observations.get(1).getContext()),
            "relativePassScoreAlias", sides.get(0).get("relativePassScore")
                .equals(sides.get(1).get("relativePassScore")));
    }

    public static void main(String[] args) throws Exception {
        need(args.length == 3, "Use pinned55 manifest, exact M8 model, new output.");
        Path manifestPath = Path.of(args[0]), modelPath = Path.of(args[1]), output = Path.of(args[2]);
        need(!Files.exists(output), "Preserve earlier audit outputs.");
        String manifestHash = PurchaseJson.INSTANCE.hash(manifestPath);
        JsonNode manifest = PurchaseJson.INSTANCE.read(manifestPath);
        for (var pin : manifest.required("completionPins").properties()) {
            need(PurchaseJson.INSTANCE.hash(Path.of(pin.getKey())).equals(pin.getValue().asText()),
                "Completed55 admission changed.");
        }
        String modelHash = PurchaseJson.INSTANCE.hash(modelPath);
        need(modelHash.equals("b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4"),
            "Use exact qualified raw M8 for descriptive scores.");
        JsonNode modelNode = PurchaseJson.INSTANCE.read(modelPath);
        Map<String, Double> weights = new LinkedHashMap<>();
        modelNode.required("weights").properties().forEach(e -> weights.put(e.getKey(), e.getValue().doubleValue()));
        BotModel model = BotModels.INSTANCE.fromSerializedWeights(weights, modelNode.required("schemaVersion").intValue());
        List<Map<String, Object>> windows = new ArrayList<>();
        List<Map<String, Object>> fixtures = new ArrayList<>();
        int entries = 0; long deadline = System.nanoTime() + 180_000_000_000L;
        need(manifest.required("origins").size() == 256, "Retain all completed55 origins.");
        for (JsonNode source : manifest.required("origins")) {
            Path path = Path.of(source.required("path").asText());
            need(PurchaseJson.INSTANCE.hash(path).equals(source.required("sha256").asText()),
                "A frozen completed origin changed.");
            JsonNode data = PurchaseJson.INSTANCE.read(path), backbone = data.required("backbone");
            List<String> history = new ArrayList<>(); backbone.required("entries").forEach(v -> history.add(v.asText()));
            need(backbone.required("replayVerified").booleanValue()
                && CardAdvantageExecutor.Companion.hash(history).equals(backbone.required("canonicalSha256").asText()),
                "Retain exact canonical backbone identity.");
            GameState state = initial(backbone.required("header"));
            for (int number = 0; number < history.size(); number++) {
                need(System.nanoTime() < deadline, "Bounded read-only audit expired.");
                Entry entry = TextCodec.INSTANCE.parse(history.get(number));
                List<Prompt> prompts = state.prompts();
                if (entry instanceof Entry.Action action && prompts.size() == 1
                    && prompts.get(0) instanceof Prompt.SinglePlayer prompt
                    && prompt.getChoice().equals("cancel-adjustment")) {
                    need(action.getActor().equals(prompt.getPlayer())
                        && action.getActor().equals(state.getTurnOrder().getFirst()),
                        "Cancellation belongs to the first player at the actual recorded window.");
                    List<String> offered = state.legalChoices(action.getActor()).getCommands();
                    String chosen = action.getCommand().toText();
                    need(offered.contains(chosen), "Recorded cancellation was not offered.");
                    windows.add(Map.of("origin", source.required("index").intValue(), "entry", number,
                        "seat", state.getPlayerIds().indexOf(action.getActor()), "turn", state.getTurnNumber(),
                        "offered", offered, "chosen", chosen));
                    if (fixtures.isEmpty() && offered.size() > 1) {
                        for (String command : offered.stream().filter(c -> !c.equals("pass")).sorted().toList()) {
                            fixtures.add(fixture(state, action.getActor(), command, model));
                        }
                    }
                }
                state = state.step(entry); entries++;
            }
            need(state.getStage() == Stage.FINISHED, "Replay must match the completed backbone.");
            need(PurchaseJson.INSTANCE.hash(path).equals(source.required("sha256").asText()),
                "Source changed during audit.");
        }
        need(PurchaseJson.INSTANCE.hash(manifestPath).equals(manifestHash), "Input manifest changed.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "adjustment-encoding-alias-audit-1"); result.put("manifestSha256", manifestHash);
        result.put("modelSha256", modelHash); result.put("canonicalEntries", entries);
        result.put("windows", windows); result.put("firstBranchingWindowFixtures", fixtures);
        result.put("syntheticOneStepProjections", fixtures.size() * 2);
        result.put("newGames", 0); result.put("newTails", 0); result.put("fits", 0);
        result.put("hiddenAuthorityFactsOutput", false);
        Files.writeString(output, PurchaseJson.INSTANCE.getMapper().writeValueAsString(result) + "\n");
    }
}
