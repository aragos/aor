"""Snapshot and role contracts for completed token-order collections; stdlib only.

Continuation roles are environment provenance, never observation or training features.
Omitted continuationProtocol retains the original collection metadata contract.
"""

import json
from pathlib import Path

from automata_experiments import require, sha
import token_order_fixed_reference as fixed


KIND = "token-order-collection"
PROPOSALS = "token-order-proposals-1"
CONFIGURATION = "players-4/standard/no-crusades/deck-true/art-true"
SELECTION = "PRECOMMITTED_ORDINAL_V1"
ARENA_CONTINUATION = "ARENA_OVERLAYS_1"
EIGHT_TAIL_CONTINUATION = "ARENA_OVERLAYS_EIGHT_TAILS_1"
EXPANSION_FEATURES = ("tokens", "progress", "reach", "commodity", "income", "risk",
                      "action.expansion", "action.pass")
TOKEN_REFERENCE_VERSION = "ordinary-token-reference-1"
RULES_TRANSFER = {"sourceRulesVersion": "ah1996-three-epoch-4",
                  "targetRulesVersion": "ah1996-three-epoch-5"}
ENCODER = "aor-token-observation-2"


def read(path):
    """Retain the fitter's strict JSON-number handling without importing Torch."""
    def reject_constant(text):
        raise ValueError(f"Nonfinite JSON number: {text}")
    return json.loads(Path(path).read_text(), parse_constant=reject_constant)


def integer(value, name, minimum, maximum):
    require(type(value) is int and minimum <= value <= maximum,
            f"{name} must be an integer in [{minimum}, {maximum}].")
    return value


def transfer_metadata(source, manifest, snapshots):
    """Transferred weights retain their original bytes and never import source qualification."""
    transfer = source.get("experimentalRulesTransfer")
    actual = manifest.get("experimentalRulesTransfer")
    if transfer is None:
        require(actual is None, "Token data contains an undeclared rules transfer.")
        return
    require(transfer == RULES_TRANSFER and actual == dict(RULES_TRANSFER,
                initializationOnly=True, qualificationTransferred=False)
            and manifest.get("rulesVersion") == RULES_TRANSFER["targetRulesVersion"],
            "Token rules transfer must identify unqualified rules4 initialization evaluated under rules5.")
    references = {"candidate": source["candidate"], "incumbent": source["incumbent"],
                  **{f"opponent-{index}": path for index, path in enumerate(source["opponents"])}}
    expected = {role for role, path in references.items() if path is not None}
    inputs = manifest["ordinaryModelSources"]
    require(set(inputs) == expected, "Transfer must retain every explicit ordinary source identity.")
    source_directory = Path(manifest["source"]).parent
    for role, item in inputs.items():
        name = "initial.json" if role == "candidate" else f"{role}.json"
        require(item == {"path": str((source_directory / references[role]).resolve()),
                         "sha256": snapshots[name], "snapshot": name},
                "Transferred ordinary bytes differ from their source identity: " + role)


def continuation_metadata(directory, source, manifest, snapshots, *, eight_tails=False,
                          fixed_reference=False):
    """Opt-in arena roles bind archived inputs; they never become learning features.

    This contract requires identical serialized non-expansion coefficients, including
    explicit prior zeros. It does not duplicate the native schema-one prior in Python.
    """
    fields = ("continuationProtocol", "replayOrigins", "tokenReference")
    if "continuationProtocol" not in source:
        require(all(key not in manifest for key in fields)
                and "replayOrigins" not in source
                and all(source.get(key) is None and manifest.get(key) is None
                        for key in ("tokenOrder", "expansionImitation")),
                "Legacy token collection cannot contain undeclared continuation overlays.")
        return {}
    expected = fixed.PROTOCOL if fixed_reference else (
        EIGHT_TAIL_CONTINUATION if eight_tails else ARENA_CONTINUATION)
    require(source["continuationProtocol"] == manifest.get("continuationProtocol")
            == expected, "Unsupported token continuation protocol.")
    origins = source.get("replayOrigins")
    count = manifest["trainingRoots"] + manifest["validationRoots"]
    require(isinstance(origins, list) and 1 <= len(origins) <= 8
            and all(type(index) is int and 0 <= index < count for index in origins)
            and len(origins) == len(set(origins)) and manifest.get("replayOrigins") == origins,
            "Arena continuation requires one through eight distinct predeclared replay origins.")
    reference = {"version": TOKEN_REFERENCE_VERSION, "ordinaryModelSnapshot": "initial.json",
                 "ordinaryModelSha256": snapshots["initial.json"]}
    if fixed_reference:
        reference.update(version=fixed.REFERENCE, selector=fixed.SELECTOR,
                         proposalReference=TOKEN_REFERENCE_VERSION)
    require(manifest.get("tokenReference") == reference,
            "Token reference must identify the exact ordinary candidate snapshot.")
    for name in ("initial.json", "incumbent.json"):
        require(read(directory / name)["weights"].get("planning.tokenOrderControl.fixed16To15") != 1,
                "Controlled token reference cannot enable the fixed token gate.")
    token = source.get("tokenOrder")
    if fixed_reference:
        require(isinstance(token, dict) and token.get("candidateSelector") == fixed.SELECTOR,
                "Fixed-reference collection requires the exact controlled fixed-T selector.")
    if token is None:
        require(manifest.get("tokenOrder") is None, "Token rival selectors were not declared.")
    else:
        keys = {"opponentSelectors", "candidateSelector"} if fixed_reference else {"opponentSelectors"}
        require(isinstance(token, dict) and set(token) == keys,
                "Fixed-reference collection permits only fixed T and indexed opponent selectors."
                if fixed_reference else
                "Collection permits only opponent token selectors, never controlled token overlays.")
        selectors = token["opponentSelectors"]
        require(isinstance(selectors, list) and len(selectors) == len(source["opponents"])
                and all(selector in ("ORDINARY", "FIXED_16_TO_15") for selector in selectors)
                and manifest.get("tokenOrder") == token,
                "Token rival selectors must retain their explicit ordinary-pool index order.")
    require(isinstance(manifest.get("source"), str) and manifest["source"],
            "Arena continuation metadata requires its original source path.")
    source_directory = Path(manifest["source"]).parent
    pins = {}

    def snapshot(reference, name, metadata):
        require(isinstance(reference, str) and bool(reference),
                "An arena continuation model requires an explicit source path.")
        expected = {"path": str((source_directory / reference).resolve()),
                    "sha256": sha(directory / name), "snapshot": name}
        require(metadata == expected, "Arena continuation snapshot identity changed: " + name)
        pins[name] = expected["sha256"]
        return read(directory / name)

    for role in ("baseline", "policy"):
        snapshot(source["frozenPurchase"][role], f"frozen-purchase-{role}.json",
                 manifest["frozenPurchase"][role])
    expansion = source.get("expansionImitation")
    require(isinstance(expansion, dict) and "candidateModel" in expansion
            and set(expansion) <= {"candidateModel", "opponentModels"},
            "Arena continuation requires candidate expansion and optional indexed rivals only.")
    actual = manifest.get("expansionImitation")
    require(isinstance(actual, dict)
            and set(actual) == {"version", "decision", "featureNames", *expansion}
            and actual["version"] == "expansion-imitation-1" and actual["decision"] == "expand"
            and actual["featureNames"] == list(EXPANSION_FEATURES),
            "Expansion continuation must retain its exact phase, feature dictionary and role set.")

    def expansion_snapshot(reference, name, ordinary_name, metadata):
        model = snapshot(reference, name, metadata)
        ordinary = read(directory / ordinary_name)
        require(model.get("schemaVersion") == ordinary.get("schemaVersion") == 1
                and isinstance(model.get("weights"), dict) and isinstance(ordinary.get("weights"), dict)
                and {key: value for key, value in model["weights"].items() if key not in EXPANSION_FEATURES}
                == {key: value for key, value in ordinary["weights"].items() if key not in EXPANSION_FEATURES},
                "Expansion snapshots must preserve every serialized coefficient outside the eight coordinates.")

    expansion_snapshot(expansion["candidateModel"], "expansion-imitation-candidate.json", "initial.json",
                       actual["candidateModel"])
    if "opponentModels" in expansion:
        rivals, metadata = expansion["opponentModels"], actual["opponentModels"]
        require(isinstance(rivals, list) and len(rivals) == len(source["opponents"])
                and isinstance(metadata, list) and len(metadata) == len(rivals),
                "Expansion rival models must retain one optional entry per ordinary pool index.")
        for index, (reference, item) in enumerate(zip(rivals, metadata)):
            if reference is None:
                require(item is None, "An ordinary rival cannot acquire an expansion overlay.")
            else:
                expansion_snapshot(reference, f"expansion-imitation-opponent-{index}.json",
                                   f"opponent-{index}.json", item)
    return pins


def collection_metadata(directory, manifest, finished, *, eight_tails=False, fixed_reference=False):
    """Bind completed data to its prospective request table and archived environment."""
    require(manifest.get("kind") == finished.get("kind") == KIND
            and manifest["signature"] == finished["signature"]
            and manifest.get("complete") is True and manifest.get("originSchemaVersion") == 1
            and manifest["encoderVersion"] == ENCODER
            and manifest["proposalVersion"] == PROPOSALS
            and manifest.get("selectionProtocol") == SELECTION,
            "Use a complete token-order collection with the exact encoder and proposal contract.")
    prospective = read(directory / "run.json")
    require(prospective == {key: value for key, value in manifest.items() if key not in ("complete", "counts")},
            "Completed token metadata differs from its prospective run manifest.")
    source = read(directory / "source.json")
    require(sha(directory / "source.json") == manifest["sourceSha256"],
            "Token source snapshot changed.")
    require(source.get("schemaVersion") == 1 and source.get("purpose") == "SCREENING"
            and source.get("configurations") == [CONFIGURATION]
            and manifest["configuration"] == CONFIGURATION
            and set(source["frozenPurchase"]) == {"baseline", "policy"},
            "Token collection requires the fixed shared purchase environment without side overrides.")
    require(not fixed_reference or eight_tails,
            "Fixed-reference residual loading requires the explicit eight-tail loader.")
    replicates = 8 if eight_tails else 2
    if eight_tails:
        expected = fixed.PROTOCOL if fixed_reference else EIGHT_TAIL_CONTINUATION
        require(source.get("continuationProtocol") == expected,
                "Eight-tail loading requires its explicit versioned continuation protocol.")
    for key, lower, upper in (("trainingRoots", 2, 8192), ("validationRoots", 2, 8192),
                              ("continuationReplicates", replicates, replicates), ("seed", 0, 99999999)):
        integer(manifest[key], key, lower, upper)
        require(source[key] == manifest[key], "Token source and manifest disagree: " + key)
    opponents = source["opponents"]
    require(isinstance(opponents, list) and 1 <= len(opponents) <= 16,
            "Token collection needs a bounded frozen rival pool.")
    snapshots = manifest["ordinaryModelSnapshots"]
    expected_names = {"initial.json", "incumbent.json"} | {f"opponent-{i}.json" for i in range(len(opponents))}
    require(set(snapshots) == expected_names, "Token collection must bind every ordinary model snapshot.")
    transfer_metadata(source, manifest, snapshots)
    require(read(directory / "initial.json")["weights"] == read(directory / "incumbent.json")["weights"],
            "Token collection must use identical ordinary candidate and incumbent policies.")
    pins = {"source.json": manifest["sourceSha256"], **snapshots}
    frozen = manifest["frozenPurchase"]
    require(set(frozen) == {"baseline", "policy"}, "Purchase roles must remain shared during collection.")
    for role in ("baseline", "policy"):
        name = f"frozen-purchase-{role}.json"
        require(frozen[role]["snapshot"] == name, "Unexpected frozen purchase snapshot name.")
        pins[name] = frozen[role]["sha256"]
    for name, expected in pins.items():
        require(sha(directory / name) == expected, "Token environment snapshot changed: " + name)
    pins.update(continuation_metadata(directory, source, manifest, snapshots,
                                     eight_tails=eight_tails, fixed_reference=fixed_reference))
    return source, pins
