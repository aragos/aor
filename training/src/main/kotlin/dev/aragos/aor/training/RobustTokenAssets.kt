package dev.aragos.aor.training

import java.nio.file.Path

/** Decoded immutable subjects reused only inside a single sequential native batch. */
internal class RobustTokenAssets {
    data class Reference(
        val arena: ArenaSpecification,
        val asset: CoordinatedInferenceAsset,
        val pins: Map<String, String>,
        val dictionaries: Map<CoordinatedEsHead, CpePairedDictionary>,
    )

    private data class Key(val path: Path, val sha256: String) {
        constructor(subject: TokenPairedSubject) : this(subject.path, subject.sha256)
    }

    private var reference: Pair<Pair<Key, Key>, Reference>? = null
    private val bundles = linkedMapOf<Key, CoordinatedEsBundle>()

    fun reference(arenaSource: TokenPairedSubject, inference: TokenPairedSubject): Reference {
        val key = Key(arenaSource) to Key(inference)
        reference
            ?.takeIf { it.first == key }
            ?.second
            ?.let { cached ->
                cached.pins.forEach { (path, digest) ->
                    require(PurchaseJson.hash(Path.of(path)) == digest) {
                        "Frozen cached robust-token subject changed: $path."
                    }
                }
                return cached
            }
        val parsed = ArenaSpecification.read(arenaSource.path)
        val configuration = parsed.configurations.single()
        val asset = CoordinatedInferenceAsset.read(inference, configuration)
        // The descriptor supplies frozen reference composition. Each shard admits its own seeds.
        val arena =
            CoordinatedInferenceEcology.read(
                arenaSource,
                asset,
                CoordinatedQualificationBank(
                    parsed.seed,
                    List(4096) { StrategyArena.seed(parsed.seed, it) },
                ),
                "homogeneous",
            )
        val loaded =
            Reference(
                arena,
                asset,
                asset.pins + CoordinatedWinEcology.pins(arenaSource, arena),
                CoordinatedEsBundle.dictionaries(configuration),
            )
        bundles.clear()
        reference = key to loaded
        return loaded
    }

    fun bundle(subject: TokenPairedSubject, reference: Reference): CoordinatedEsBundle {
        val key = Key(subject)
        bundles[key]?.let { cached ->
            cached.verify()
            return cached
        }
        val loaded = CoordinatedEsBundle.read(subject, reference.dictionaries)
        if (bundles.size == 4) {
            bundles.remove(bundles.keys.first())
        }
        bundles[key] = loaded
        return loaded
    }
}
