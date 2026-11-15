import dev.aragos.aor.CoordinatedPreviewPolicy;
import java.nio.file.Path;

/** Offline cached decoding only: no game, command, service or policy forward. */
public final class VerifyCoordinatedPreview {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Supply the immutable preview manifest path.");
        }
        var policy = CoordinatedPreviewPolicy.INSTANCE.read(Path.of(args[0]));
        System.out.println("profile=" + policy.getProfile().getVersion());
        System.out.println("manifestSha256=" + policy.getAssetSha256());
        System.out.println("games=0 forwards=0 serverStarted=false");
    }
}
