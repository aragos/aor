import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Outcome-blind public projection of immutable expansion-paired-origin-1 gzip records.
 *
 * <p>CLI: INPUT.gz OUTPUT.json [INPUT.gz OUTPUT.json ...]. Each output has a separate
 * OUTPUT.json.metadata.json sidecar. Outputs must not exist. The caller owns split admission:
 * this low-level reader must only receive TEST paths after the separate positive-selection gate.
 * It never constructs engine state, a model, an outcome object, or a canonical history.
 */
public final class ExpansionPairedPublic {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final String ENCODER = "aor-expansion-paired-observation-1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private ExpansionPairedPublic() {}

    public static void main(String[] args) throws Exception {
        require(args.length > 0 && args.length % 2 == 0,
                "Expected one or more INPUT.gz OUTPUT.json pairs.");
        Set<Path> outputs = new HashSet<>();
        Set<Path> inputs = new HashSet<>();
        for (int i = 0; i < args.length; i += 2) {
            Path input = Path.of(args[i]).toAbsolutePath().normalize();
            require(Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS),
                    "Input must be a regular immutable gzip file.");
            inputs.add(input.toRealPath());
            Path output = Path.of(args[i + 1]).toAbsolutePath().normalize();
            Path parent = output.getParent().toRealPath();
            output = parent.resolve(output.getFileName());
            for (Path target : List.of(output, metadataPath(output))) {
                require(outputs.add(target) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                        "Every public and metadata output must be new and distinct.");
            }
        }
        require(outputs.stream().noneMatch(inputs::contains), "Inputs cannot be output files.");
        for (int i = 0; i < args.length; i += 2) {
            project(Path.of(args[i]), Path.of(args[i + 1]).toAbsolutePath().normalize());
        }
    }

    private static Path metadataPath(Path output) {
        return output.resolveSibling(output.getFileName() + ".metadata.json");
    }

    private static void project(Path input, Path output) throws Exception {
        require(Files.size(input) <= MAX_BYTES, "Compressed origin exceeds the native 64 MiB cap.");
        String sourceSha = sha(input);
        ObjectNode safe = JSON.createObjectNode();
        try (InputStream raw = Files.newInputStream(input);
                InputStream gzip = new LimitedInput(new GZIPInputStream(raw));
                JsonParser parser = JSON.getFactory().createParser(gzip)) {
            require(parser.nextToken() == JsonToken.START_OBJECT, "Origin must be a JSON object.");
            Set<String> fields = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(parser, fields);
                switch (field) {
                    case "kind", "signature", "request", "streams", "status" ->
                            safe.set(field, JSON.readTree(parser));
                    case "actorInput" -> safe.set("encoded", encoded(parser));
                    case "arms" -> safe.set("proposals", proposals(parser));
                    default -> parser.skipChildren();
                }
            }
            require(parser.nextToken() == null, "Trailing content after origin is not allowed.");
        }
        fields(safe, "kind", "signature", "request", "streams", "status", "encoded", "proposals");
        require(text(safe.get("kind")).equals("expansion-paired-origin-1"),
                "Only the original expansion paired origin contract is accepted.");
        String signature = text(safe.get("signature"));
        require(signature.matches("[0-9a-f]{64}"), "Origin signature must be a raw SHA-256.");
        int index = validateRequest(safe.get("request"));
        validateStreams(safe.get("streams"));
        String status = text(safe.get("status"));
        require(status.equals("COLLECTED") || status.equals("MISSING"), "Unknown origin status.");
        ArrayNode indices = JSON.createArrayNode();
        ArrayNode commands = JSON.createArrayNode();
        JsonNode encoded = safe.get("encoded");
        Integer originalBaseline = null;
        if (status.equals("MISSING")) {
            require(encoded.isNull() && safe.get("proposals").isEmpty(),
                    "Missing origins cannot contain invented actor inputs or proposals.");
        } else {
            originalBaseline = integer(encoded.get("baselineIndex"), 0, 1023, "Original baseline");
            encoded = select(encoded, safe.get("proposals"), indices, commands);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("kind", "expansion-paired-public-1");
        result.put("index", index);
        result.put("signature", signature);
        result.put("sourceSha256", sourceSha);
        result.put("status", status);
        result.set("encoded", encoded);
        result.set("actionIndices", indices);
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("kind", "expansion-paired-public-metadata-1");
        metadata.put("index", index);
        metadata.put("signature", signature);
        metadata.put("sourceSha256", sourceSha);
        metadata.put("status", status);
        metadata.set("request", safe.get("request"));
        metadata.set("streams", safe.get("streams"));
        metadata.set("split", safe.get("request").get("split"));
        metadata.set("seat", safe.get("request").get("seat"));
        metadata.set("futureSeeds", safe.get("streams").get("future"));
        metadata.set("commands", commands);
        metadata.set("actionIndices", indices);
        metadata.set("baselineIndex", encoded.isNull() ? JSON.nullNode() : encoded.get("baselineIndex"));
        metadata.put("originalBaselineIndex", originalBaseline);
        require(sha(input).equals(sourceSha), "Original gzip changed during public extraction.");
        publish(output, result);
        publish(metadataPath(output), metadata);
    }

    /** Only encoded is decoded; view, observation, bonus and other actor fields stay opaque. */
    private static JsonNode encoded(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return JSON.nullNode();
        }
        require(parser.currentToken() == JsonToken.START_OBJECT, "actorInput must be an object or null.");
        JsonNode result = null;
        Set<String> fields = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = field(parser, fields);
            if (name.equals("encoded")) {
                result = JSON.readTree(parser);
            } else {
                parser.skipChildren();
            }
        }
        require(result != null && result.isObject(), "Captured actorInput requires its exact encoded object.");
        return result;
    }

    /** Per-arm outcomes, including all terminal and canonical evidence, are structurally skipped. */
    private static ArrayNode proposals(JsonParser parser) throws IOException {
        require(parser.currentToken() == JsonToken.START_ARRAY, "Arms must be an array.");
        ArrayNode result = JSON.createArrayNode();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            require(parser.currentToken() == JsonToken.START_OBJECT, "Each arm must be an object.");
            JsonNode proposal = null;
            Set<String> fields = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = field(parser, fields);
                if (name.equals("proposal")) {
                    proposal = JSON.readTree(parser);
                } else {
                    parser.skipChildren();
                }
            }
            require(proposal != null && proposal.isObject(), "Each arm requires one public proposal.");
            result.add(proposal);
            require(result.size() <= 8, "At most eight original proposals are allowed.");
        }
        return result;
    }

    private static ObjectNode select(
            JsonNode encoded, JsonNode proposals, ArrayNode indices, ArrayNode commands) {
        fields(encoded, "encoderVersion", "stateFeatures", "actions", "baselineIndex");
        require(text(encoded.get("encoderVersion")).equals(ENCODER), "Research encoder tag differs.");
        features(encoded.get("stateFeatures"));
        JsonNode actions = encoded.get("actions");
        require(actions.isArray() && actions.size() >= 2 && actions.size() <= 1024,
                "The original complete canonical action list is required.");
        List<String> originalCommands = new ArrayList<>();
        for (JsonNode action : actions) {
            fields(action, "command", "features");
            String command = text(action.get("command"));
            require(originalCommands.isEmpty()
                            || originalCommands.getLast().compareTo(command) < 0,
                    "Original offered commands must be distinct and canonically sorted.");
            originalCommands.add(command);
            features(action.get("features"));
        }
        int baseline = integer(encoded.get("baselineIndex"), 0, actions.size() - 1, "E baseline");
        require(proposals.isArray() && proposals.size() >= 2 && proposals.size() <= 8,
                "Captured origins require two through eight original proposed actions.");
        TreeMap<Integer, JsonNode> selected = new TreeMap<>();
        Set<String> reasons = Set.of("INCUMBENT", "PASS", "CARD_PURCHASE",
                "DESTINATION_SAMPLE", "REMAINING_COMMAND_SAMPLE");
        for (JsonNode proposal : proposals) {
            fields(proposal, "actionIndex", "command", "reason");
            int index = integer(proposal.get("actionIndex"), 0, actions.size() - 1, "Proposal index");
            require(text(proposal.get("command")).equals(originalCommands.get(index)),
                    "Proposal command must match its original canonical action index.");
            String reason = text(proposal.get("reason"));
            require(reasons.contains(reason), "Unknown original proposal reason.");
            require(reason.equals("INCUMBENT") == selected.isEmpty(),
                    "Only the first original proposal is the exact E incumbent.");
            require(!selected.isEmpty() || index == baseline, "First proposal differs from E baseline.");
            require(selected.put(index, actions.get(index)) == null, "Proposal indices must be distinct.");
        }
        int pass = originalCommands.indexOf("pass");
        int card = originalCommands.indexOf("buy-expansion-card");
        require(pass >= 0 && selected.containsKey(pass), "Original offered PASS must remain proposed.");
        require(card < 0 || selected.containsKey(card), "Original offered card buy must remain proposed.");
        ArrayNode subset = JSON.createArrayNode();
        int remapped = -1;
        for (var entry : selected.entrySet()) {
            if (entry.getKey() == baseline) {
                remapped = subset.size();
            }
            indices.add(entry.getKey());
            commands.add(originalCommands.get(entry.getKey()));
            subset.add(entry.getValue());
        }
        require(remapped >= 0, "E baseline cannot be dropped.");
        ObjectNode result = JSON.createObjectNode();
        result.set("encoderVersion", encoded.get("encoderVersion"));
        result.set("stateFeatures", encoded.get("stateFeatures"));
        result.set("actions", subset);
        result.put("baselineIndex", remapped);
        return result;
    }

    private static int validateRequest(JsonNode request) {
        fields(request, "index", "seat", "epoch", "ordinal", "split");
        int index = integer(request.get("index"), 0, 1023, "Origin index");
        String split = index < 768 ? "TRAIN" : index < 896 ? "VALIDATION" : "TEST";
        require(integer(request.get("seat"), 0, 3, "Seat") == index % 4
                        && integer(request.get("epoch"), 1, 3, "Epoch") == 1 + (index / 4) % 3
                        && integer(request.get("ordinal"), 0, 6, "Ordinal") == 2 * ((index / 12) % 4)
                        && text(request.get("split")).equals(split),
                "Original prospective seat, epoch, ordinal and split must remain unchanged.");
        return index;
    }

    private static void validateStreams(JsonNode streams) {
        fields(streams, "gameSeed", "proposalSeed", "future");
        long game = integer(streams.get("gameSeed"), Integer.MIN_VALUE, Integer.MAX_VALUE, "Game seed");
        long proposal = exactLong(streams.get("proposalSeed"));
        JsonNode future = streams.get("future");
        require(future.isArray() && future.size() == 2, "Two original shared future streams are required.");
        Set<Long> unique = new HashSet<>(List.of(game, proposal));
        unique.add(exactLong(future.get(0)));
        unique.add(exactLong(future.get(1)));
        require(unique.size() == 4, "Origin, proposal and future streams must be distinct.");
    }

    private static void features(JsonNode node) {
        require(node != null && node.isObject(), "Encoder features must be a numeric object.");
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            require(!entry.getKey().isEmpty() && value.isNumber()
                            && Double.isFinite(value.doubleValue())
                            && value.doubleValue() >= -16 && value.doubleValue() <= 16,
                    "Only finite original numeric features in [-16,16] are allowed.");
        });
    }

    private static String field(JsonParser parser, Set<String> seen) throws IOException {
        require(parser.currentToken() == JsonToken.FIELD_NAME, "Expected a JSON field name.");
        String name = parser.currentName();
        require(seen.add(name), "Duplicate field at a public extraction boundary.");
        require(parser.nextToken() != null, "Missing JSON field value.");
        return name;
    }

    private static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject(), "Expected a public JSON object.");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(names)), "Public JSON fields differ from the frozen contract.");
    }

    private static String text(JsonNode node) {
        require(node != null && node.isTextual() && !node.textValue().isEmpty(),
                "Expected a nonempty literal string.");
        return node.textValue();
    }

    private static int integer(JsonNode node, int min, int max, String subject) {
        require(node != null && node.isIntegralNumber() && node.canConvertToInt()
                        && node.intValue() >= min && node.intValue() <= max,
                subject + " must be an exact integer in range.");
        return node.intValue();
    }

    private static long exactLong(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong(),
                "Streams must be exact signed 64-bit integers.");
        return node.longValue();
    }

    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void publish(Path path, JsonNode value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".expansion-public-", ".json");
        try {
            Files.write(temporary, JSON.writeValueAsBytes(value));
            Files.move(temporary, path); // No replacement: a partial earlier batch is not resumed.
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long bytes;

        LimitedInput(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            count(value < 0 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            count(Math.max(count, 0));
            return count;
        }

        private void count(int count) {
            bytes += count;
            require(bytes <= MAX_BYTES, "Expanded origin exceeds the native 64 MiB cap.");
        }
    }
}
