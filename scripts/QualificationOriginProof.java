import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Preparation only. Run with Java21 source mode and the frozen83 nine-JAR classpath.
 * The unchanged83 reader owns the complete admission; this helper never simulates or chooses.
 * Request and output are absolute local paths. All retained inputs must stay at these paths.
 */
public final class QualificationOriginProof {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "dev.aragos.aor.training.";
    private static final String TRAINING_SHA =
            "f35e48eb03c93b8e159c94de5d1a7ca3d29fff04bad999f97ed865093f2adf11";
    private static final Set<String> JARS = Set.of(
            "ai-jvm.jar", "annotations-13.0.jar", "core-jvm.jar",
            "jackson-annotations-2.22.jar", "jackson-core-2.22.4.jar",
            "jackson-databind-2.22.4.jar", "kotlin-stdlib-2.4.21.jar",
            "kotlinx-collections-immutable-jvm-0.5.2.jar", "training.jar");

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Use QualificationOriginProof.java REQUEST OUTPUT.");
        Path requestPath = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        require(!Files.exists(output), "An origin proof cannot overwrite prior evidence.");
        JsonNode request = JSON.readTree(requestPath.toFile());
        fields(request, Set.of("kind", "host", "admission", "arena", "runtime"));
        require(request.path("kind").asText().equals("coordinated-qualification-origin-request-1")
                && Set.of("DESKTOP", "MAC").contains(request.path("host").asText()),
                "An explicit host origin-proof request is required.");
        JsonNode runtime = request.required("runtime");
        fields(runtime, JARS);
        Map<String, String> pins = new LinkedHashMap<>();
        Map<String, Object> runtimeSubjects = new LinkedHashMap<>();
        Map<String, Path> paths = new LinkedHashMap<>();
        for (String name : JARS.stream().sorted().toList()) {
            Path path = subjectPath(runtime.required(name));
            require(path.getFileName().toString().equals(name), "Runtime JAR name changed.");
            paths.put(name, path);
            pins.put(path.toString(), hash(path));
            runtimeSubjects.put(name, subject(path));
        }
        require(hash(paths.get("training.jar")).equals(TRAINING_SHA),
                "Only the immutable83 f35 training runtime may attest the origin.");
        Set<Path> actualClasspath = Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(Path::of).map(path -> {
                    try { return path.toRealPath(); }
                    catch (Exception error) { throw new IllegalArgumentException(error); }
                }).collect(Collectors.toSet());
        require(actualClasspath.size() == 9 && actualClasspath.equals(Set.copyOf(paths.values())),
                "The executing classpath must be exactly the nine declared frozen JARs.");
        for (var binding : Map.of(
                PREFIX + "CoordinatedEsAdmission", "training.jar",
                "dev.aragos.aor.ai.BotModel", "ai-jvm.jar",
                "dev.aragos.aor.GameView", "core-jvm.jar").entrySet()) {
            Path loaded = Path.of(Class.forName(binding.getKey()).getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toRealPath();
            require(loaded.equals(paths.get(binding.getValue())), "A policy owner loaded elsewhere.");
        }
        Object admissionSubject = nativeSubject(subjectPath(request.required("admission")));
        Object arenaSubject = nativeSubject(subjectPath(request.required("arena")));
        Class<?> subjectClass = Class.forName(PREFIX + "TokenPairedSubject");
        Class<?> admissionClass = Class.forName(PREFIX + "CoordinatedEsAdmission");
        Object companion = admissionClass.getField("Companion").get(null);
        Method read = companion.getClass().getMethod("read", subjectClass, subjectClass);
        Object admission = read.invoke(companion, admissionSubject, arenaSubject);
        admissionClass.getMethod("verify").invoke(admission);
        Object scope = property(admission, "Scope");
        @SuppressWarnings("unchecked")
        Map<String, String> admittedPins = (Map<String, String>) property(admission, "Pins");
        for (var pin : admittedPins.entrySet()) {
            require(hash(Path.of(pin.getKey())).equals(pin.getValue()), "Origin closure changed.");
            String previous = pins.put(pin.getKey(), pin.getValue());
            require(previous == null || previous.equals(pin.getValue()), "Conflicting closure pins.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> admittedSubjects = (Map<String, Object>) property(admission, "Subjects");
        Map<String, Object> subjects = new LinkedHashMap<>();
        for (var entry : admittedSubjects.entrySet()) {
            subjects.put(entry.getKey(), subject((Path) property(entry.getValue(), "Path")));
        }
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("kind", "coordinated-qualification-origin-proof-1");
        proof.put("host", request.path("host").asText());
        proof.put("passed", true);
        proof.put("complete", true);
        proof.put("admission", subject((Path) property(admissionSubject, "Path")));
        proof.put("arena", subject((Path) property(arenaSubject, "Path")));
        proof.put("runtime", runtimeSubjects);
        proof.put("scopeIdentity", property(scope, "Identity"));
        proof.put("scopeSha256", property(scope, "Sha256"));
        proof.put("ecologySha256", property(scope, "EcologySha256"));
        proof.put("subjects", subjects);
        proof.put("inputPins", pins);
        proof.put("execution", Map.of("newGames", 0, "policyCalls", 0, "actualFits", 0));
        // Retain only a complete successful attestation; caller preserves stdout/stderr on failure.
        Files.createDirectories(output.getParent());
        Files.write(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(proof),
                StandardOpenOption.CREATE_NEW);
    }

    private static Object nativeSubject(Path path) throws Exception {
        return Class.forName(PREFIX + "TokenPairedSubject")
                .getConstructor(Path.class, String.class, String.class)
                .newInstance(path, hash(path), path.toString());
    }

    private static Object property(Object owner, String name) throws Exception {
        return owner.getClass().getMethod("get" + name).invoke(owner);
    }

    private static Path subjectPath(JsonNode node) throws Exception {
        fields(node, Set.of("path", "sha256"));
        Path declared = Path.of(node.required("path").asText());
        require(declared.isAbsolute() && declared.toRealPath().equals(declared),
                "Origin subjects require exact retained absolute real paths.");
        require(hash(declared).equals(node.required("sha256").asText()), "Subject hash changed.");
        return declared;
    }

    private static Map<String, String> subject(Path path) throws Exception {
        return Map.of("path", path.toString(), "sha256", hash(path));
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1048576];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void fields(JsonNode node, Set<String> expected) {
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(node.isObject() && actual.equals(expected), "Unexpected origin proof fields.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
