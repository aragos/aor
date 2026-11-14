package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WinCoordinatedPreviewPolicyTest {
    @Test
    fun bothWinGatesAdmitTheGenericProfileWithoutARewardGate() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_WIN_SUPPORT_1)
        assertThat(policy.assetSha256).isEqualTo(fixture.hash(fixture.manifest))
    }

    @Test
    fun failedMixedWinGateRejectsBeforeModelDecode() = withFixture { fixture ->
        fixture.changeQualification {
            (it["banks"]["mixed"] as ObjectNode).put("win99Passed", false)
        }
        Files.writeString(fixture.root.resolve("candidate/heads/CARD/policy.json"), "not a model")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }

        assertThat(failure).hasMessageThat().contains("99% win gate")
    }

    @Test
    fun incompleteHomogeneousBankCannotAdmitAnAsset() = withFixture { fixture ->
        fixture.changeQualification {
            (it["banks"]["homogeneous"] as ObjectNode).put("complete", false)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anotherInferenceAssetCannotReuseTheCertificate() = withFixture { fixture ->
        fixture.changeQualification { it.put("inferenceAssetSha256", "0".repeat(64)) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun theNominationMustHaveAnExactHash() = withFixture { fixture ->
        fixture.changeQualification { it.put("nominationSha256", "unbound") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anEarlierQualificationVersionCannotAuthorizeTheGenericProfile() = withFixture { fixture ->
        fixture.changeQualification { it.put("kind", "coordinated-support-qualification-1") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun aDifferentSelectorCannotReuseTheGenericVersion() = withFixture { fixture ->
        fixture.changeInference { it.put("selectorVersion", "sampled-territory-decoder") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anotherRulesConfigurationCannotReuseTheGenericVersion() = withFixture { fixture ->
        fixture.changeInference { (it["configuration"] as ObjectNode).put("playerCount", 3) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun inferenceAssetsRetainTheConfinedRelativePathBoundary() = withFixture { fixture ->
        fixture.changeManifest { (it["inferenceAsset"] as ObjectNode).put("path", "../asset.json") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun aLoadedGenericAssetRetainsItsImmutableIdentity() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
        val expected = fixture.hash(fixture.manifest)
        Files.writeString(fixture.root.resolve("inference-asset.json"), "changed after caching")

        assertThat(policy.assetSha256).isEqualTo(expected)
        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_WIN_SUPPORT_1)
    }

    /** Constructed transport and browser evidence; its qualification claims are invented. */
    @Test
    fun exportsClearlyLabeledGenericAssetForServerIntegration() {
        val directory = Path.of("build/test-fixtures/win-coordinated-preview").toAbsolutePath()
        val fixture = PreviewAssetFixture(directory)
        fixture.toWinQualified()
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
        fixture.write(
            "constructed-fixture.json",
            mapOf(
                "kind" to "constructed-win-coordinated-preview-fixture-1",
                "constructedOnly" to true,
                "realModels" to 0,
                "scientificGames" to 0,
                "fits" to 0,
                "qualificationClaimsInventedForTransportTest" to true,
                "manifest" to fixture.subject(fixture.manifest),
            ),
        )

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_WIN_SUPPORT_1)
    }

    private fun withFixture(block: (PreviewAssetFixture) -> Unit) {
        val directory = Files.createTempDirectory("constructed-win-qualified-asset-")
        try {
            val fixture = PreviewAssetFixture(directory)
            fixture.toWinQualified()
            block(fixture)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
