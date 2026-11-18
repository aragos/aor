import dev.aragos.aor.CoordinatedPreviewPolicy;
import dev.aragos.aor.ServerBotPolicy;
import java.nio.file.Path;

/** Actual local asset admission only; this program never calls the policy chooser. */
public final class QualifiedAssetAdmission {
    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Provide a manifest path and accept or reject.");
        }
        boolean reject = arguments[1].equals("reject");
        if (!reject && !arguments[1].equals("accept")) {
            throw new IllegalArgumentException("Admission expectation must be accept or reject.");
        }
        try {
            ServerBotPolicy policy = CoordinatedPreviewPolicy.INSTANCE.read(Path.of(arguments[0]));
            if (reject) {
                throw new IllegalStateException("A changed full-policy identity was accepted.");
            }
            String profile = policy.getProfile().getVersion();
            if (!profile.equals("aor-coordinated-robust-support-1")) {
                throw new IllegalStateException("The actual asset must retain its robust profile.");
            }
            System.out.println("{\"admitted\":true,\"profile\":\"" + profile
                + "\",\"manifestSha256\":\"" + policy.getAssetSha256()
                + "\",\"modelForwards\":0}");
        } catch (IllegalArgumentException failure) {
            if (!reject || failure.getMessage() == null
                || !failure.getMessage().contains("bind this exact complete model")) {
                throw failure;
            }
            System.out.println("{\"changedPolicyRejected\":true,\"modelForwards\":0}");
        }
    }
}
