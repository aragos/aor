package dev.aragos.aor.training;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aragos.aor.AdjustmentCancellationContext;
import dev.aragos.aor.AdjustmentCancellationProjection;
import dev.aragos.aor.AdjustmentCancellationQuote;
import dev.aragos.aor.GameState;
import dev.aragos.aor.PlayerId;
import dev.aragos.aor.TextCodec;
import dev.aragos.aor.ai.BotObservations;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Once-only TEST prefix projection; the reviewed helper skips every outcome/tail tree. */
public final class CancellationTestProjection {
    public static void main(String[] args) throws Exception {
        NativeProjectionAudit.need(args.length == 2, "Use frozen TEST manifest and new output.");
        JsonNode manifest = NativeProjectionAudit.M.readTree(Files.readAllBytes(Path.of(args[0])));
        NativeProjectionAudit.need(manifest.required("kind").asText()
            .equals("cancellation-fixed-test-prefix-1"), "Require the frozen TEST contract.");
        Set<Integer> indices = new HashSet<>();
        int captured = 0, missing = 0, entries = 0, projections = 0;
        try (BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]),
                StandardOpenOption.CREATE_NEW)) {
            for (JsonNode row : manifest.required("origins")) {
                int index = row.required("index").intValue();
                NativeProjectionAudit.need(index >= 224 && index < 256 && indices.add(index)
                    && row.required("split").asText().equals("TEST"),
                    "Retain each of the 32 original whole TEST origins exactly once.");
                Path path = Path.of(row.required("path").asText());
                String digest = row.required("sha256").asText();
                NativeProjectionAudit.need(NativeProjectionAudit.sha(path).equals(digest),
                    "Original TEST source bytes changed.");
                Map<String, JsonNode> fields = NativeProjectionAudit.prefixFields(path, true);
                String status = fields.get("status").asText();
                if (status.equals("MISSING")) {
                    output.write(NativeProjectionAudit.M.writeValueAsString(Map.of(
                        "index", index, "split", "TEST", "sourceSha256", digest,
                        "status", "MISSING")));
                    output.newLine(); missing++; continue;
                }
                NativeProjectionAudit.need(status.equals("COLLECTED"), "Unknown root status.");
                JsonNode prefix = fields.get("prefix"), input = fields.get("actorInput");
                JsonNode provenance = fields.get("provenance");
                List<String> canonical = new ArrayList<>();
                for (JsonNode entry : prefix.required("entries")) canonical.add(entry.asText());
                NativeProjectionAudit.need(CardAdvantageExecutor.Companion.hash(canonical)
                    .equals(prefix.required("canonicalSha256").asText()), "Prefix hash changed.");
                GameState state = NativeProjectionAudit.initial(prefix.required("header"));
                for (String entry : canonical) state = state.step(TextCodec.INSTANCE.parse(entry));
                PlayerId actor = new PlayerId(provenance.required("actor").asText());
                NativeProjectionAudit.need(state.getPlayerIds().indexOf(actor)
                    == provenance.required("seat").intValue(), "Actual root actor changed.");
                NativeProjectionAudit.need(NativeProjectionAudit.json(state.viewForDecision(actor))
                    .equals(input.required("view")), "Actor-safe view differs from canonical prefix.");
                NativeProjectionAudit.need(NativeProjectionAudit.json(BotObservations.INSTANCE
                    .from(state, actor)).equals(input.required("observation")),
                    "Ordinary observation differs from canonical prefix.");
                NativeProjectionAudit.need(provenance.required("exactPrefixAndIncumbentVerified")
                    .booleanValue(), "The retained root requires original incumbent parity.");
                AdjustmentCancellationContext context = AdjustmentCancellationContext.Companion
                    .from(state.viewForDecision(actor), actor);
                List<AdjustmentCancellationQuote> quotes = AdjustmentCancellationProjection.INSTANCE
                    .quotes(context);
                Set<String> commands = new HashSet<>();
                for (AdjustmentCancellationQuote quote : quotes) {
                    commands.add(quote.getCommand());
                    NativeProjectionAudit.compareTransition(state, actor, quote); projections++;
                }
                NativeProjectionAudit.need(commands.equals(new HashSet<>(state.legalChoices(actor)
                    .getCommands())), "Project every offered action including PASS.");
                output.write(NativeProjectionAudit.M.writeValueAsString(Map.ofEntries(
                    Map.entry("index", index), Map.entry("split", "TEST"),
                    Map.entry("sourceSha256", digest), Map.entry("status", "COLLECTED"),
                    Map.entry("prefixSha256", prefix.required("canonicalSha256").asText()),
                    Map.entry("actorInput", input), Map.entry("provenance", provenance),
                    Map.entry("context", context), Map.entry("quotes", quotes))));
                output.newLine(); captured++; entries += canonical.size();
            }
        }
        NativeProjectionAudit.need(indices.size() == 32, "Missing prospective TEST origins.");
        System.out.println(NativeProjectionAudit.M.writeValueAsString(Map.ofEntries(
            Map.entry("passed", true), Map.entry("origins", 32), Map.entry("captured", captured),
            Map.entry("missing", missing), Map.entry("canonicalPrefixEntries", entries),
            Map.entry("deterministicOneStepProjections", projections),
            Map.entry("tailTreesDecoded", 0), Map.entry("newGames", 0), Map.entry("newFits", 0))));
    }
}
