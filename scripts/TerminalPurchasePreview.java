import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aragos.aor.ActivityPlanning;
import dev.aragos.aor.GameHeader;
import dev.aragos.aor.GameState;
import dev.aragos.aor.PlanBoundary;
import dev.aragos.aor.PlannedAction;
import dev.aragos.aor.Player;
import dev.aragos.aor.PlayerId;
import dev.aragos.aor.ReliefOfferView;
import dev.aragos.aor.TextCodec;
import dev.aragos.aor.TextCodecKt;
import dev.aragos.aor.ai.BotCandidate;
import dev.aragos.aor.ai.BotObservations;
import dev.aragos.aor.ai.PurchaseAction;
import dev.aragos.aor.ai.PurchaseObservationEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Four local, deterministic Written Record previews; never continues a hypothetical game. */
public final class TerminalPurchasePreview {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Integer> ROOTS = List.of(733, 735, 742, 745);
    private static final String SNAPSHOT =
        "3cde7b3311b09efe2dcffbe0a948e8f4e8658958580dd2125fdf653f70cbd455";

    private static Map<String, Object> fields(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void require(boolean condition, String diagnostic) {
        if (!condition) throw new IllegalArgumentException(diagnostic);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "Provide sanitized snapshot, prior audit and new output paths.");
        Path source = Path.of(args[0]), auditPath = Path.of(args[1]), output = Path.of(args[2]);
        require(!Files.exists(output), "Preserve completed preview evidence; output must be new.");
        require(sha(source).equals(SNAPSHOT), "Only the frozen sanitized local G2 snapshot is admitted.");
        JsonNode save = JSON.readTree(source.toFile()), audit = JSON.readTree(auditPath.toFile());
        require(audit.get("sourceSha256").asText().equals(SNAPSHOT), "Prior replay binds another snapshot.");
        require(save.get("baseRevision").asInt() == 0 && save.get("previousArchive").isNull(),
            "This diagnostic requires the complete effective prefix.");
        JsonNode header = save.get("header");
        var players = new ArrayList<Player>();
        for (JsonNode player : header.get("players")) {
            players.add(new Player(new PlayerId(player.get("id").asText()), player.get("name").asText()));
        }
        var gameHeader = new GameHeader(players, new PlayerId(header.get("creatorId").asText()),
            header.get("rulesVersion").asText(), header.get("dataVersion").asText(),
            header.path("drawRenaissanceFromTurn").asInt(1));
        List<String> history = new ArrayList<>();
        for (JsonNode revision : save.get("revisions")) {
            if (!revision.get("replaceFrom").isNull()) {
                history = new ArrayList<>(history.subList(0, revision.get("replaceFrom").asInt()));
            }
            for (JsonNode entry : revision.get("entries")) history.add(entry.asText());
        }
        var recorded = new LinkedHashMap<Integer, JsonNode>();
        for (JsonNode action : audit.get("actions")) {
            if (ROOTS.contains(action.get("entry").asInt())) {
                recorded.put(action.get("entry").asInt(), action);
            }
        }
        require(recorded.size() == ROOTS.size(), "All four prescribed roots must exist.");
        GameState state = TextCodecKt.replay(gameHeader, List.of());
        var results = new ArrayList<Map<String, Object>>();
        for (int index = 0; index <= ROOTS.getLast(); index++) {
            if (recorded.containsKey(index)) {
                JsonNode row = recorded.get(index);
                PlayerId actor = new PlayerId(row.get("player").asText());
                var view = state.viewForPlayer(actor);
                var self = state.playerState(actor);
                var publicSelf = view.getPlayers().stream().filter(p -> p.getId().equals(actor))
                    .findFirst().orElseThrow();
                JsonNode expectedSelf = null;
                for (JsonNode p : row.get("before")) {
                    if (p.get("player").asText().equals(actor.getValue())) expectedSelf = p;
                }
                require(JSON.valueToTree(FullGameAudit.player(publicSelf)).equals(expectedSelf),
                    "The canonical prefix must match the existing public actor projection.");
                var observation = BotObservations.INSTANCE.from(state, actor);
                var candidateCommands = observation.getCandidates().stream()
                    .map(BotCandidate::getCommand).toList();
                var actorCommands = PurchaseObservationEncoder.INSTANCE.project(view, actor, observation)
                    .getActions().stream().map(PurchaseAction::getCommand).toList();
                require(JSON.valueToTree(candidateCommands).equals(row.get("legalCommands")),
                    "The original candidate set changed.");
                require(JSON.valueToTree(actorCommands).equals(row.get("purchaseCommands")),
                    "The original purchase encoder action support changed.");
                var engineCommands = state.legalChoices(actor).getCommands();
                require(engineCommands.contains("buy-advance N"), "Written Record must be engine-legal.");
                var preview = ActivityPlanning.INSTANCE.preview(state, actor,
                    List.of(new PlannedAction("purchase-advances", "buy-advance N")));
                require(preview.getDiagnostic() == null && preview.getEffects().size() == 1
                    && preview.getBoundary() == PlanBoundary.INCOMPLETE,
                    "Exactly one deterministic purchase must complete without another information boundary.");
                var after = preview.getEffects().getFirst().getAfter();
                var relief = preview.getGame().getReliefOffers();
                int maximum = relief.stream().filter(ReliefOfferView::getAffordable)
                    .mapToInt(ReliefOfferView::getSpaces).max().orElse(0);
                results.add(fields("entry", index, "player", actor.getValue(), "turn", state.getTurnNumber(),
                    "recordedCommand", row.get("command").asText(), "previewCommands", List.of("buy-advance N"),
                    "prefixParity", true, "engineCommands", engineCommands, "actorCommands", actorCommands,
                    "beforeCash", self.getCash(), "beforeMisery", self.getMisery(),
                    "beforeReliefTiers", self.getAdvances().getTierCount(),
                    "beforeAdvanceOffers", view.getAdvanceOffers(), "beforeReliefOffers", view.getReliefOffers(),
                    "ownHand", view.getHand().stream().map(c -> fields("reference", c.getReference(),
                        "definition", c.getDefinition().getId())).toList(),
                    "purchaseQuote", preview.getEffects().getFirst().getAdvanceQuote(),
                    "afterCash", after.getCash(), "afterMisery", after.getMisery(),
                    "afterReliefTiers", after.getAdvances().getTierCount(),
                    "afterReliefOffers", relief, "maximumAffordableReliefSpaces", maximum,
                    "stabilizationCostNotYetPaid", preview.getGame().getStabilizationCost(),
                    "nextDecision", preview.getDecision()));
            }
            if (index < ROOTS.getLast()) state = state.step(TextCodec.INSTANCE.parse(history.get(index)));
        }
        require(results.size() == 4 && sha(source).equals(SNAPSHOT), "Prescribed previews or source changed.");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), fields(
            "kind", "local-terminal-written-record-preview-1", "sourceSha256", SNAPSHOT,
            "priorAuditSha256", sha(auditPath), "sourceRevision", save.get("revision").asInt(),
            "historicalPrefixEntriesReplayed", 745, "deterministicProjectionCount", 4,
            "hypotheticalActions", 4, "hypotheticalPasses", 0,
            "fullGameAlternativeRollouts", 0, "fits", 0, "localOnly", true, "previews", results,
            "limitation", "Relief quotes are authoritative at the post-Written-Record state. Stabilization is not executed; no later cards, relief payments, or terminal outcomes are simulated."));
        System.out.println("Completed exactly four deterministic Written Record previews.");
    }
}
