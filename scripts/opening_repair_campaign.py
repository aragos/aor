"""Learn wider opening choices after adding the low-bid and high-budget stress cases."""

import argparse
from collections import defaultdict
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import with_mix
from initiative_series_rivals import GAMES_PER_GROUP
from weighted_opening_campaign import fit, policy, report, tournament, verify_fit


REMOTE = "/home/aragos/aor-worker/opening-repair-20261119"
BIDS = tuple(range(13, 23))
DEVELOP = ("mixed", "known-14", "known-20", "experienced")
FRESH = ("mixed", "known-14", "known-16", "experienced", "contested-steep")
DEVELOP_WORLDS = 384
FRESH_WORLDS = 1280
CAP = .27


def clustered(rows, selected):
    groups = defaultdict(list)
    rivals = defaultdict(list)
    for i, row in enumerate(rows):
        own, rival = row["outcomes"][selected]
        baseline, reference_rival = row["outcomes"]["control"]
        group = (i // GAMES_PER_GROUP) % 64
        groups[group].append(own - baseline)
        rivals[group].append(rival - reference_rival)
    return {"ownWinDelta": interval([statistics.mean(x) for x in groups.values()]),
            "seatToLeftWinDelta": interval([statistics.mean(x) for x in rivals.values()])}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, resume=resume)
    campaign.validate_runtime()
    first = previous / "automata-weighted-opening-with-pressure-20261119"
    preliminary = read(first / "result.json")
    capital = previous / "automata-second-capital-geography-20261119/result.json"
    if preliminary["passed"] or preliminary["outcomes"]["known-14"]["ownWinDelta"]["lower95"] >= -.025:
        raise ValueError("This follow-up is triggered by the independently observed low-bid weakness.")
    if not read(capital)["passed"]:
        raise ValueError("Use the already qualified geography rule as the current reference.")
    anchor = dict(read(first / "plan.json"), stagePrefix="opening-repair", secondCapitalGeography=True)
    sealed = {
        "referenceHistorySha256": sha(first / "plan.json"),
        "specificWeaknessSha256": sha(first / "result.json"),
        "originalCapitalQualificationSha256": sha(capital),
        "bids": list(BIDS), "cappedWeight": CAP,
        "developmentCases": list(DEVELOP), "freshCases": list(FRESH),
        "worlds": {"development": DEVELOP_WORLDS, "fresh": FRESH_WORLDS},
    }
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, sealed)
    elif read(manifest) != sealed:
        raise ValueError("A resumed opening search must retain the original fresh plan.")
    arms = {f"bid-{amount}": with_mix(anchor["baseline"], [amount]) for amount in BIDS}
    training, withheld = {}, {}
    for case in DEVELOP:
        rows, _ = tournament(campaign, anchor, arms, case, DEVELOP_WORLDS)
        training[case] = [row for i, row in enumerate(rows) if (i // 4) % 3 != 2]
        withheld[case] = [row for i, row in enumerate(rows) if (i // 4) % 3 == 2]
    fits = {name: fit(training, penalty, CAP, BIDS)
            for name, penalty in (("risk-focused", .012), ("broad-support", .032))}
    selected, effects = verify_fit(withheld, fits, BIDS)
    write(root / "screen.json", {"candidate": selected, "weights": fits, "heldoutWinDeltas": effects})
    if selected is None or campaign.remaining() < 4500:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    config = policy(anchor["baseline"], fits[selected], BIDS)
    reports = {}
    for case in FRESH:
        rows, _ = tournament(campaign, anchor, {selected: config}, case, FRESH_WORLDS, fresh=True)
        summary = report(rows, selected)
        reports[case] = summary | (clustered(rows, selected) if case == "experienced" else {})
    points = [value["ownWinDelta"] for value in reports.values()]
    write(root / "result.json", {
        "passed": (min(point["lower95"] for point in points) > -.025 and
                   statistics.mean(point["mean"] for point in points) >= .015 and
                   sum(point["lower95"] > 0 for point in points) >= 2),
        "configuration": config, "selected": selected, "outcomes": reports,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
