import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aragos.aor.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Local read-only recorded replay: no generated outcomes, policy calls, or save writes. */
public final class ThreatReplayAudit {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> row(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<String> owners(GameView view, String province) {
        for (ProvinceView place : view.getBoard()) {
            if (!place.getId().getValue().equals(province)) continue;
            if (place.getMarker() != null) return List.of(place.getMarker().getOwner().getValue());
            return place.getTokens().stream().map(token -> token.getOwner().getValue()).toList();
        }
        return List.of();
    }

    private static List<Map<String, Object>> scores(GameView view) {
        return view.getScores().stream().map(score -> row("player", score.getPlayer().getValue(),
            "advancePoints", score.getAdvancePoints(), "cash", score.getCash(),
            "misery", score.getMisery(), "total", score.getTotal(), "winner", score.getWinner())).toList();
    }

    private static Map<String, Object> audit(Path source) throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        JsonNode save = JSON.readTree(bytes);
        if (save.required("baseRevision").asInt() != 0 || !save.required("previousArchive").isNull()) {
            throw new IllegalArgumentException("A complete non-archived recorded history is required.");
        }
        JsonNode headerNode = save.required("header");
        List<Player> players = new ArrayList<>();
        for (JsonNode player : headerNode.required("players")) {
            players.add(new Player(new PlayerId(player.required("id").asText()),
                player.required("name").asText()));
        }
        GameHeader header = new GameHeader(players,
            new PlayerId(headerNode.required("creatorId").asText()),
            headerNode.required("rulesVersion").asText(),
            headerNode.required("dataVersion").asText(),
            headerNode.path("drawRenaissanceFromTurn").asInt(1));
        List<String> entries = new ArrayList<>();
        List<Integer> revisions = new ArrayList<>();
        for (JsonNode revision : save.required("revisions")) {
            if (!revision.required("replaceFrom").isNull()) {
                int retained = revision.required("replaceFrom").asInt();
                entries = new ArrayList<>(entries.subList(0, retained));
                revisions = new ArrayList<>(revisions.subList(0, retained));
            }
            for (JsonNode entry : revision.required("entries")) {
                entries.add(entry.asText());
                revisions.add(revision.required("id").asInt());
            }
        }
        Set<String> bots = new HashSet<>();
        save.required("bots").fieldNames().forEachRemaining(bots::add);
        GameState state = TextCodecKt.replay(header, List.of());
        List<Map<String, Object>> attacks = new ArrayList<>();
        List<Map<String, Object>> offeredLeaderAttacks = new ArrayList<>();
        List<Map<String, Object>> orders = new ArrayList<>();
        Set<String> sampledContexts = new HashSet<>();
        int eligibleDecisions = 0;
        int ownLeaderDecisions = 0;
        int choseLeaderAttack = 0;
        int choseOtherAttack = 0;
        int chosePass = 0;
        int expansionActions = 0;
        for (int index = 0; index < entries.size(); index++) {
            String text = entries.get(index);
            int separator = text.indexOf(": ");
            String actor = separator < 0 ? "" : text.substring(0, separator);
            String command = separator < 0 ? "" : text.substring(separator + 2);
            if (bots.contains(actor) && state.getStage() == Stage.EXPANSION) {
                GameView view = state.viewForPlayer(new PlayerId(actor));
                List<ScoreView> finite = view.getScores().stream()
                    .filter(score -> score.getTotal() != null).toList();
                int maximum = finite.stream().mapToInt(ScoreView::getTotal).max().orElse(Integer.MIN_VALUE);
                List<String> leaders = finite.stream().filter(score -> score.getTotal() == maximum)
                    .map(score -> score.getPlayer().getValue()).toList();
                expansionActions++;
                if (leaders.contains(actor)) ownLeaderDecisions++;
                Set<String> legal = new HashSet<>(state.legalChoices(new PlayerId(actor)).getCommands());
                List<Map<String, Object>> offers = new ArrayList<>();
                for (ExpansionOfferView offer : view.getExpansionOffers()) {
                    String destination = offer.getDestination().getValue();
                    if (offer.getPrimaryAction() != ExpansionAction.ATTACK ||
                        !legal.contains("attack " + destination) ||
                        owners(view, destination).stream().noneMatch(leaders::contains)) continue;
                    offers.add(row("province", destination, "owners", owners(view, destination),
                        "requiredTokens", offer.getRequiredTokens(),
                        "winProbability", offer.getCompetitionWinProbability()));
                }
                List<String> attackedOwners = List.of();
                if (command.startsWith("attack ")) {
                    attackedOwners = owners(view, command.split(" ")[1]);
                    attacks.add(row("entry", index, "revision", revisions.get(index),
                        "turn", state.getTurnNumber(), "actor", actor, "command", command,
                        "defenders", attackedOwners, "publicScoreLeaders", leaders,
                        "targetsPublicLeader", attackedOwners.stream().anyMatch(leaders::contains),
                        "actorIsPublicLeader", leaders.contains(actor), "scores", scores(view)));
                }
                if (!leaders.contains(actor) && !offers.isEmpty()) {
                    eligibleDecisions++;
                    boolean chosenLeader = attackedOwners.stream().anyMatch(leaders::contains);
                    if (chosenLeader) choseLeaderAttack++;
                    else if (command.startsWith("attack ")) choseOtherAttack++;
                    if (command.equals("pass")) chosePass++;
                    String context = state.getTurnNumber() + ":" + actor;
                    if (!chosenLeader && sampledContexts.add(context)) {
                        offeredLeaderAttacks.add(row("entry", index, "revision", revisions.get(index),
                            "turn", state.getTurnNumber(), "actor", actor, "command", command,
                            "publicScoreLeaders", leaders, "scores", scores(view), "leaderAttackOffers", offers));
                    }
                }
            }
            GameState next = state.step(TextCodec.INSTANCE.parse(text));
            if (!next.getTurnOrder().equals(state.getTurnOrder()) && !next.getTurnOrder().isEmpty()) {
                orders.add(row("turn", next.getTurnNumber(), "entry", index,
                    "order", next.getTurnOrder().stream().map(PlayerId::getValue).toList(),
                    "revealedBids", next.publicView().getRevealedTokenPurchases()));
            }
            state = next;
        }
        if (!TextCodecKt.replay(header, entries).equals(state)) {
            throw new IllegalStateException("Incremental and complete recorded replay differ.");
        }
        return row("gameId", save.required("id").asText(), "sourceRevision", save.required("revision").asInt(),
            "sourceSha256", sha(bytes), "effectiveEntries", entries.size(), "replayVerified", true,
            "stage", state.getStage().name(), "turn", state.getTurnNumber(), "scores", scores(state.publicView()),
            "botExpansionActions", expansionActions, "actorLeadingExpansionActions", ownLeaderDecisions,
            "behindWithLegalLeaderAttackDecisions", eligibleDecisions, "choseLeaderAttack", choseLeaderAttack,
            "choseOtherAttack", choseOtherAttack, "chosePass", chosePass,
            "attacks", attacks, "sampledUnchosenLeaderAttackContexts", offeredLeaderAttacks, "orders", orders);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Supply local save directory and new report path.");
        List<Path> sources;
        try (var paths = Files.list(Path.of(args[0]))) {
            sources = paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        List<Map<String, Object>> games = new ArrayList<>();
        for (Path source : sources) {
            games.add(audit(source));
            System.out.println("Recorded replay complete: " + source.getFileName());
        }
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Retain previous audit reports; output must be new.");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), row(
            "kind", "local-public-leader-denial-recorded-replay-1", "localOnly", true,
            "generatedGames", 0, "policyCalls", 0, "saveWrites", 0, "games", games,
            "interpretation", "Public score is a descriptive proxy, not predicted winning probability. Legal unchosen attacks are not established mistakes; no counterfactuals were run."));
    }
}
