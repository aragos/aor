package dev.aragos.aor.training;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.xml.stream.XMLOutputFactory;

/**
 * Standalone admission-shape checks against the unchanged, frozen runtime JAR.
 *
 * <p>Run in a dedicated JVM. Only the four expected original hashes are temporarily substituted
 * with invented fixture hashes; they are restored in finally. This proves receipt/lineage/tensor
 * joins, not scientific-original admission, ES arithmetic, or sixteen executed generations.
 */
public final class CoordinatedEsFinalAdmissionShape {
    private static final ObjectMapper JSON = PurchaseJson.INSTANCE.getMapper();
    private static final CoordinatedEsWire WIRE = CoordinatedEsWire.INSTANCE;
    private static final String RUNTIME_SHA =
            "f35e48eb03c93b8e159c94de5d1a7ca3d29fff04bad999f97ed865093f2adf11";
    private static final String ORIGINAL_REJECTION = "Original four-head subject pins";
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static Path output;
    private static ObjectNode headReceipt;
    private static TokenPairedSubject arena;
    private static TokenPairedSubject original;
    private static CoordinatedEsScope scope;

    private CoordinatedEsFinalAdmissionShape() {}

    public static void main(String[] args) throws Exception {
        assertThat(args).hasLength(2);
        output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        assertThat(Files.exists(output.resolve("proof.json"))).isFalse();
        Path runtime = Path.of(CoordinatedEsAdmission.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertThat(PurchaseJson.INSTANCE.hash(runtime)).isEqualTo(RUNTIME_SHA);
        headReceipt = (ObjectNode) JSON.readTree(Path.of(args[1]).toFile());

        // Reuse constructed fixture creation only; no choose(), forward(), or Simulation call.
        Path inputs = output.resolve("inputs");
        Files.createDirectories(inputs);
        try (CoordinatedEsFixture fixture = new CoordinatedEsFixture()) {
            try (var paths = Files.walk(fixture.getFiles().getDirectory())) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    Path relative = fixture.getFiles().getDirectory().relativize(path);
                    Path target = inputs.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        arena = WIRE.source(inputs.resolve("arena.json"));
        original = WIRE.source(inputs.resolve("coordinated-bundle.json"));
        Map<CoordinatedEsHead, String> expected =
                CoordinatedEsBundle.Companion.getORIGINAL_HASHES();
        Map<CoordinatedEsHead, String> scientificPins = new LinkedHashMap<>(expected);
        Map<CoordinatedEsHead, String> constructedPins = new LinkedHashMap<>();
        JsonNode descriptor = JSON.readTree(original.getPath().toFile());
        for (CoordinatedEsHead head : CoordinatedEsHead.values()) {
            constructedPins.put(head, descriptor.path("heads").path(head.name())
                    .path("sha256").asText());
        }
        check("scientificOriginalGuardRejectsToyBeforeSubstitution", () ->
                rejects(() -> CoordinatedEsScope.Companion.read(arena, original),
                        ORIGINAL_REJECTION));
        try {
            expected.clear();
            expected.putAll(constructedPins);
            scope = CoordinatedEsScope.Companion.read(arena, original);
            checks();
        } finally {
            expected.clear();
            expected.putAll(scientificPins);
        }
        check("scientificOriginalGuardRestoredAndRejectsToy", () -> {
            assertThat(expected).containsExactlyEntriesIn(scientificPins);
            rejects(() -> CoordinatedEsScope.Companion.read(arena, original), ORIGINAL_REJECTION);
        });
        long failures = RESULTS.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed"))).count();
        ObjectNode proof = obj(
                "kind", "coordinated-es-final-admission-shape-proof-1",
                "passed", failures == 0, "constructedOnly", true,
                "scientificOriginalAdmission", false, "executedGenerations", 0,
                "constructedLineageGenerations", 16, "newGames", 0, "policyCalls", 0,
                "actualFits", 0, "optimizerSteps", 0, "runtimeJar", runtime.toString(),
                "runtimeSha256", PurchaseJson.INSTANCE.hash(runtime),
                "substitutedExpectedOriginalPins", constructedPins,
                "restoredScientificExpectedOriginalPins", scientificPins,
                "originalPinsRestored", expected.equals(scientificPins),
                "limitations", List.of(
                        "Only expected original hashes are substituted in this dedicated JVM.",
                        "Historical, population, update and materialization claims are invented.",
                        "No numerical update or projection-to-final-weight calculation is proved.",
                        "The separate joined numeric proof owns actual constructed ES arithmetic."),
                "cases", RESULTS, "files", inventory(output));
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("proof.json").toFile(), proof);
        xml(failures);
        System.out.println("Constructed final-admission shape: " + RESULTS.size()
                + " cases, " + failures + " failures; no games, forwards or numerical updates.");
        assertThat(failures).isEqualTo(0);
    }

    private static void checks() {
        for (int version : List.of(1, 2)) {
            check("v" + version + "CompleteFinalAdmission", () -> {
                Fixture fixture = new Fixture("positive-v" + version, version, version);
                CoordinatedEsAdmission admitted = fixture.read();
                assertThat(admitted.getCalibration().getVersion().getReceiptKind())
                        .isEqualTo("coordinated-es-calibration-" + version);
                assertThat(admitted.getFinalBundle().getSource().getSha256())
                        .isEqualTo(fixture.subject("finalBundle").getSha256());
                admitted.verify();
            });
        }
        check("v1ProtocolRejectsV2Seal", () ->
                rejects(new Fixture("cross-v1-v2", 1, 2)::read, "exact sealed receipt version"));
        check("v2ProtocolRejectsV1Seal", () ->
                rejects(new Fixture("cross-v2-v1", 2, 1)::read, "exact sealed receipt version"));
        check("partialLineageRejected", () -> {
            Fixture fixture = new Fixture("partial-lineage", 2, 2);
            fixture.edit("generationAudit", node -> node.put("completedGenerations", 15));
            rejects(fixture::read, "all16 complete banks");
        });
        check("resealedWrongPredecessorRejected", () -> {
            Fixture fixture = new Fixture("wrong-predecessor", 2, 2);
            ObjectNode audit = fixture.node("generationAudit");
            ObjectNode row = (ObjectNode) audit.path("generations").path(6);
            Path path = fixture.directory.resolve("update-7.json");
            ObjectNode update = (ObjectNode) JSON.readTree(path.toFile());
            update.put("previousCenterSha256", "f".repeat(64));
            row.set("update", ref(write(path, update)));
            fixture.replace("generationAudit", audit);
            rejects(fixture::read, "exact next center");
        });
        check("generationAuditBindsExactCalibration", () -> {
            Fixture fixture = new Fixture("wrong-audit-calibration", 2, 2);
            fixture.edit("generationAudit", node -> node.put("calibrationSha256", "f".repeat(64)));
            rejects(fixture::read, "all16 complete banks");
        });
        check("finalMaterializationBindsCenter", () -> {
            Fixture fixture = new Fixture("wrong-materialization-center", 2, 2);
            fixture.edit("centerMaterialization", node -> node.put("centerSha256", "f".repeat(64)));
            rejects(fixture::read, "exact numerical materialization");
        });
        check("finalMaterializationBindsBundle", () -> {
            Fixture fixture = new Fixture("wrong-materialization-bundle", 2, 2);
            fixture.edit("centerMaterialization", node -> node.put("bundleSha256", "f".repeat(64)));
            rejects(fixture::read, "exact numerical materialization");
        });
        check("finalMaterializationBindsCalibration", () -> {
            Fixture fixture = new Fixture("wrong-materialization-calibration", 2, 2);
            fixture.edit("centerMaterialization", node ->
                    node.put("calibrationSha256", "f".repeat(64)));
            rejects(fixture::read, "exact numerical materialization");
        });
        check("finalTensorValueMutationRejectedAfterAllReceiptJoins", () -> {
            Fixture fixture = new Fixture("wrong-final-value", 2, 2);
            ObjectNode bundle = fixture.node("finalBundle");
            ObjectNode entry = (ObjectNode) bundle.path("heads").path("TOKEN");
            Path modelPath = fixture.directory.resolve(entry.path("path").asText());
            ObjectNode model = (ObjectNode) JSON.readTree(modelPath.toFile());
            model.put("valueBias", model.path("valueBias").asDouble() + 1.0);
            entry.put("sha256", write(modelPath, model).getSha256());
            fixture.replace("finalBundle", bundle);
            fixture.edit("centerMaterialization", node ->
                    node.put("bundleSha256", fixture.subject("finalBundle").getSha256()));
            rejects(fixture::read, "original token value arrays");
        });
        check("admittedPinsRejectLaterMutation", () -> {
            Fixture fixture = new Fixture("later-mutation", 2, 2);
            CoordinatedEsAdmission admitted = fixture.read();
            Files.writeString(fixture.subject("center").getPath(), "{}\n");
            rejects(admitted::verify, "Admitted final subject changed");
        });
    }

    private static final class Fixture {
        final Path directory;
        final Map<String, TokenPairedSubject> subjects = new LinkedHashMap<>();

        Fixture(String name, int protocolVersion, int sealVersion) throws Exception {
            directory = output.resolve("cases").resolve(name);
            Files.createDirectories(directory);
            subjects.put("originalBundle", original);
            subjects.put("projection", save("projection.json", obj(
                    "kind", "coordinated-es-projection-1", "scopeSha256", scope.getSha256(),
                    "originalBundleSha256", original.getSha256())));
            ObjectNode heads = obj();
            for (CoordinatedEsHead head : CoordinatedEsHead.values()) {
                heads.set(head.name(), obj(
                        "actorSha256", scope.getOriginal().subject(head).getSha256(),
                        "dictionarySha256", scope.getDictionaries().get(head).sha256()));
            }
            ObjectNode protocol = obj(
                    "kind", "coordinated-es-campaign-1", "scienceSeconds", 86400,
                    "auditSeconds", 900, "budget", obj("maximumTerminalExecutions", 139306),
                    "gameLimits", obj("maximumSeconds", 300, "maximumDecisions", 12000),
                    "automaticRetry", false, "automaticSuccessor", false,
                    "calibration", obj("kind", version(protocolVersion).getRecipeKind()),
                    "original", obj("bundleSha256", original.getSha256(),
                            "scopeSha256", scope.getSha256(),
                            "ecologySha256", scope.getEcologySha256(),
                            "projectionSha256", subject("projection").getSha256(), "heads", heads));
            Path protocolPath = directory.resolve("protocol.json");
            Files.writeString(protocolPath, JSON.writeValueAsString(protocol) + "\n");
            subjects.put("protocol", WIRE.source(protocolPath));
            subjects.put("historical", save("historical.json", obj(
                    "kind", "coordinated-es-historical-admission-1", "complete", true,
                    "passed", true, "protocolSha256", subject("protocol").getSha256(),
                    "scopeSha256", scope.getSha256(), "ecologySha256", scope.getEcologySha256(),
                    "originalBundleSha256", original.getSha256(),
                    "scientificTerminalExecutions", 2, "zeroFailures", true,
                    "controls", List.of(
                            obj("globalIndex", 0, "index", 0, "side", "candidate",
                                    "canonicalVerified", true),
                            obj("globalIndex", 4095, "index", 511, "side", "candidate",
                                    "canonicalVerified", true)))));
            subjects.put("calibration", save("calibration.json", calibration(sealVersion)));
            Map<String, String> identity = WIRE.scope(scope, subject("projection"));
            String previous = CpePairedDigest.INSTANCE.json(center(identity, 0));
            ArrayNode rows = JSON.createArrayNode();
            for (int generation = 1; generation <= 16; generation++) {
                ObjectNode center = center(identity, generation);
                TokenPairedSubject next = save("center-" + generation + ".json", center);
                TokenPairedSubject update = save("update-" + generation + ".json", obj(
                        "kind", "coordinated-es-update-1", "complete", true,
                        "protocolSha256", subject("protocol").getSha256(),
                        "previousCenterSha256", previous, "populationSha256", "e".repeat(64),
                        "center", center));
                rows.add(obj("generation", generation, "inputCenterSha256", previous,
                        "outputCenterSha256", next.getSha256(), "outputCenter", ref(next),
                        "update", ref(update), "populationSha256", "e".repeat(64),
                        "controls", 256, "mutants", 8192, "reproductions", 2));
                previous = next.getSha256();
                subjects.put("center", next);
            }
            ObjectNode audit = obj("kind", "coordinated-es-generation-audit-1", "complete", true,
                    "passed", true, "protocolSha256", subject("protocol").getSha256(),
                    "ecologySha256", scope.getEcologySha256(),
                    "calibrationSha256", subject("calibration").getSha256(),
                    "completedGenerations", 16, "finalCenterSha256", subject("center").getSha256(),
                    "generations", rows);
            identity.forEach(audit::put);
            subjects.put("generationAudit", save("generation-audit.json", audit));
            subjects.put("finalBundle", finalBundle());
            subjects.put("centerMaterialization", save("center-materialization.json", obj(
                    "kind", "coordinated-es-center-materialization-1",
                    "protocolSha256", subject("protocol").getSha256(),
                    "centerSha256", subject("center").getSha256(), "generation", 16,
                    "projectionSha256", subject("projection").getSha256(),
                    "originalBundleSha256", original.getSha256(),
                    "calibrationSha256", subject("calibration").getSha256(),
                    "bundleSha256", subject("finalBundle").getSha256(), "finalCandidate", true)));
        }

        TokenPairedSubject finalBundle() throws Exception {
            ObjectNode heads = obj();
            for (CoordinatedEsHead head : CoordinatedEsHead.values()) {
                ObjectNode model = (ObjectNode) JSON.readTree(scope.getOriginal().subject(head)
                        .getPath().toFile());
                ((ArrayNode) model.path("actorWeights")).set(0, JSON.valueToTree(.0125));
                String name = "final-" + head.name() + ".json";
                TokenPairedSubject file = save(name, model);
                heads.set(head.name(), obj("path", name, "sha256", file.getSha256(),
                        "dictionarySha256", scope.getDictionaries().get(head).sha256()));
            }
            return save("final-bundle.json", obj(
                    "kind", "coordinated-es-bundle-1", "heads", heads));
        }

        ObjectNode calibration(int number) {
            ObjectNode heads = obj();
            ObjectNode scales = obj();
            for (String name : List.of("EXPANSION", "TOKEN")) {
                ObjectNode receipt = headReceipt.deepCopy();
                receipt.put("head", name);
                ObjectNode bindings = (ObjectNode) receipt.path("subjects");
                bindings.put("protocolSha256", subject("protocol").getSha256());
                bindings.put("projectionSha256", subject("projection").getSha256());
                bindings.put("originalModelSha256", scope.getOriginal()
                        .subject(CoordinatedEsHead.valueOf(name)).getSha256());
                if (number == 1) {
                    receipt.put("kind", version(1).getRecipeKind());
                    receipt.set("recipe", JSON.valueToTree(CoordinatedEsCalibrationReceipt.INSTANCE
                            .getRecipe()));
                    receipt.remove(List.of("intervals", "commonInterval"));
                    receipt.put("scale", 1.0 + 1.0 / 1048576);
                }
                heads.set(name, receipt);
                scales.set(name, receipt.path("scale"));
            }
            JsonNode bindings = heads.path("TOKEN").path("subjects");
            return obj("kind", version(number).getReceiptKind(), "passed", true, "complete", true,
                    "protocolSha256", subject("protocol").getSha256(),
                    "originalBundleSha256", original.getSha256(),
                    "projectionSha256", subject("projection").getSha256(),
                    "panelSha256", bindings.path("publicPanelSha256"),
                    "probePopulationSha256", "1".repeat(64),
                    "directionsSha256", bindings.path("directionsSha256"),
                    "prescribedMeanSha256", bindings.path("prescribedMeanSha256"),
                    "scales", scales, "heads", heads, "semanticFitnessDecoded", false,
                    "calibrationAttempts", 1);
        }

        TokenPairedSubject subject(String key) {
            return subjects.get(key);
        }

        TokenPairedSubject save(String name, JsonNode value) throws Exception {
            return write(directory.resolve(name), value);
        }

        ObjectNode node(String key) throws Exception {
            return (ObjectNode) JSON.readTree(subject(key).getPath().toFile());
        }

        void replace(String key, ObjectNode node) throws Exception {
            subjects.put(key, write(subject(key).getPath(), node));
        }

        void edit(String key, Consumer<ObjectNode> change) throws Exception {
            ObjectNode value = node(key);
            change.accept(value);
            replace(key, value);
        }

        CoordinatedEsAdmission read() throws Exception {
            ObjectNode admission = obj("kind", "coordinated-es-final-admission-1", "complete", true,
                    "completedGenerations", 16, "scopeSha256", scope.getSha256(),
                    "ecologySha256", scope.getEcologySha256(),
                    "validationEffectsUsedForSelection", false);
            subjects.forEach((key, subject) -> admission.set(key, ref(subject)));
            return CoordinatedEsAdmission.Companion.read(save("admission.json", admission), arena);
        }
    }

    private static CoordinatedEsCalibrationVersion version(int number) {
        return number == 1
                ? CoordinatedEsCalibrationVersion.V1 : CoordinatedEsCalibrationVersion.V2;
    }

    private static ObjectNode center(Map<String, String> identity, int generation) {
        return obj("kind", "coordinated-es-center-1", "scope", identity, "generation", generation,
                "coordinates", java.util.Collections.nCopies(256, generation * .1));
    }

    private static ObjectNode obj(Object... fields) {
        ObjectNode value = JSON.createObjectNode();
        for (int i = 0; i < fields.length; i += 2) {
            value.set((String) fields[i], JSON.valueToTree(fields[i + 1]));
        }
        return value;
    }

    private static ObjectNode ref(TokenPairedSubject subject) {
        return JSON.valueToTree(WIRE.subject(subject));
    }

    private static TokenPairedSubject write(Path path, JsonNode value) throws Exception {
        JSON.writeValue(path.toFile(), value);
        return WIRE.source(path);
    }

    private static Map<String, String> inventory(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(), PurchaseJson.INSTANCE.hash(path));
            }
        }
        return result;
    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void rejects(Checked action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException failure) {
            assertThat(failure).hasMessageThat().contains(message);
            return;
        }
        throw new AssertionError("Expected native rejection: " + message);
    }

    private static void check(String name, Checked action) {
        try {
            action.run();
            RESULTS.add(Map.of("name", name, "passed", true));
        } catch (Throwable failure) {
            RESULTS.add(Map.of("name", name, "passed", false, "failure", failure.toString()));
            failure.printStackTrace(System.err);
        }
    }

    private static void xml(long failures) throws Exception {
        try (var stream = Files.newOutputStream(output.resolve("TEST-final-admission-shape.xml"))) {
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name", CoordinatedEsFinalAdmissionShape.class.getName());
            xml.writeAttribute("tests", Integer.toString(RESULTS.size()));
            xml.writeAttribute("failures", Long.toString(failures));
            for (Map<String, Object> result : RESULTS) {
                xml.writeStartElement("testcase");
                xml.writeAttribute("name", result.get("name").toString());
                if (!Boolean.TRUE.equals(result.get("passed"))) {
                    xml.writeStartElement("failure");
                    xml.writeCharacters(result.get("failure").toString());
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        }
    }
}
