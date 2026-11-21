import dev.aragos.aor.*;
import dev.aragos.aor.ai.*;
import java.io.*;
import java.util.*;
import java.util.stream.*;

class CheckCheckpointParity {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        var json = JsonKt.getJson();
        int count = Integer.parseInt(args[0]);
        var shipped = policy(json.readTree(java.nio.file.Path.of(args[1]).toFile()), count, false);
        var candidate = policy(json.readTree(java.nio.file.Path.of(args[2]).toFile()), count, Boolean.parseBoolean(args[3]));
        for (var map : MapRule.values()) {
            var random = new Random(20261120L + count + map.ordinal());
            var driver = new GameDriver(new SetupRandom() {
                public List<String> shuffle(List<String> cards) {
                    var shuffled = new ArrayList<>(cards);
                    Collections.shuffle(shuffled, random);
                    return shuffled;
                }
                public List<DieRoll> roll(int amount) {
                    return IntStream.range(0, amount).mapToObj(i -> new DieRoll(1 + random.nextInt(6))).toList();
                }
            });
            var seats = IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Player(new PlayerId("p" + i), "Seat " + i)).toList();
            var header = new GameHeader(seats, seats.getFirst().getId(),
                GameKt.RULES_VERSION, GameKt.DATA_VERSION, 1, 1);
            var rules = new RuleOptions(map.ordinal() != 0, InitialCardRule.ANY, false, map, map.ordinal() != 0);
            var game = driver.create(UUID.randomUUID().toString(), header, Map.of(), rules, UndoPolicy.LINEAR_GAME);
            int steps = 0;
            int timingQuotes = 0;
            int finishQuotes = 0;
            while (game.state().getStage() != Stage.FINISHED && steps < 5000) {
                var state = game.state();
                var prompt = state.prompts().getFirst();
                var prior = game.getPendingBids();
                var actor = switch (prompt) {
                    case Prompt.SinglePlayer one -> one.getPlayer();
                    case Prompt.MultiPlayer many -> many.getPlayers().stream().filter(p -> !prior.containsKey(p)).findFirst().orElseThrow();
                    default -> throw new IllegalStateException("Expected player prompt");
                };
                var observation = BotObservations.INSTANCE.from(state, actor);
                var timing = observation.getDecision().equals("buy-tokens")
                    ? PhaseInitiativePolicy.INSTANCE.project(state.viewForDecision(actor), actor, true) : null;
                var finish = observation.getDecision().equals("purchase-advances")
                    ? FinalAdvancePlan.Companion.quote(state, state.viewForDecision(actor), actor) : null;
                if (timing != null) timingQuotes++;
                if (finish != null) finishQuotes++;
                var message = json.createObjectNode().put("count", count).put("seed", random.nextInt());
                message.put("old",shipped.choose(observation,timing,finish,message.get("seed").intValue()));
                message.put("market",candidate.choose(observation,timing,finish,message.get("seed").intValue()));
                message.set("observation", json.valueToTree(observation));
                message.set("initiative", json.valueToTree(timing));
                message.set("finish", json.valueToTree(finish));
                System.out.println(message);
                var command = input.readLine();
                if (command == null) throw new IllegalStateException("No browser command received");
                game = driver.submit(game, actor, command);
                steps++;
            }
            if (game.state().getStage() != Stage.FINISHED) throw new IllegalStateException("Game did not finish");
            if (timingQuotes == 0) throw new IllegalStateException("Game did not exercise initiative");
            System.err.printf("%d seats / %s: %d browser decisions, %d timing inputs, %d finish quotes%n",
                count, map, steps, timingQuotes, finishQuotes);
        }
    }

    private static CheckpointPolicy policy(com.fasterxml.jackson.databind.JsonNode asset, int count, boolean market) {
        var weights = new HashMap<String,Double>();
        asset.required("weights").properties().forEach(field -> weights.put(field.getKey(),field.getValue().doubleValue()));
        var profile = asset.get("marketProfile");
        MarketProfile tuning = new MarketProfile();
        if (profile != null) {
            var expansion = new ArrayList<Double>();
            profile.required("expansion").forEach(value -> expansion.add(value.doubleValue()));
            tuning = new MarketProfile(profile.required("markerScale").doubleValue(),
                profile.required("commodityScale").doubleValue(), profile.required("earlyShift").doubleValue(), expansion);
        }
        return new CheckpointPolicy(BotModels.INSTANCE.fromSerializedWeights(weights, 1), count, market, tuning);
    }
}
