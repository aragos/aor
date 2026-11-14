package dev.aragos.aor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Loads only a marked, constructed native-exported inference unit through the actual server reader. */
public final class GenericInferenceBridge {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected constructed manifest and fixture marker paths.");
        }
        Path manifest = Path.of(args[0]).toRealPath();
        Path marker = Path.of(args[1]).toRealPath();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fixture = mapper.readTree(Files.readAllBytes(marker));
        if (!fixture.path("constructedOnly").asBoolean(false)) {
            throw new IllegalArgumentException("The source-only bridge requires constructed evidence.");
        }
        ServerBotPolicy policy = CoordinatedPreviewPolicy.INSTANCE.read(manifest);
        String hash = hash(manifest);
        if (policy.getProfile() != ServerBotProfile.COORDINATED_WIN_SUPPORT_1
                || !policy.getAssetSha256().equals(hash)) {
            throw new IllegalArgumentException("The server must retain the exact generic asset identity.");
        }
        System.out.println(mapper.writeValueAsString(Map.of(
                "kind", "constructed-native-server-inference-bridge-1",
                "passed", true,
                "constructedOnly", true,
                "profile", policy.getProfile().getVersion(),
                "manifest", Map.of("path", manifest.toString(), "sha256", hash),
                "marker", Map.of("path", marker.toString(), "sha256", hash(marker)),
                "modelForwards", 0,
                "scientificGames", 0,
                "strengthClaimsInvented", true)));
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
