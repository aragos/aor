"""Find whether a strong opponent can turn deliberate midgame bids into more wins."""

import argparse
from collections import Counter, defaultdict
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from midgame_rematch_campaign import public_reveals
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/midgame-response-surface-20261119"
DEVELOP = 384
FRESH = 1024
MODAL_RESPONSE = 17
ALTERNATIVES = (12, 14, 15, 16, 18, 20, 22)


def comparison(stage, amounts, worlds, history_groups):
    baseline = load_games(stage, "control")
    summary = {}
    for name, amount in amounts.items():
        match = load_games(stage, name)
        effects = defaultdict(list)
        factual = Counter()
        means = defaultdict(list)
        for index in range(worlds):
            before, after = baseline[index], match[index]
            seat = before["seat"]
            rival = (seat + 1) % 4
            identity = lambda row: (row["seed"], row["seat"],
                                    row["diagnostics"]["CANDIDATE"]["openingBid"])
            if identity(before) != identity(after):
                raise ValueError("The fixed midgame bid must leave the matched setup and opening untouched.")
            for row in (before, after):
                declared = row["diagnostics"]["CANDIDATE"]["retargetingRivalSeats"]
                if declared != [rival]:
                    raise ValueError("Exactly the same strong rival should exploit bid variation.")
            before_wins, after_wins = [row["roles"]["CANDIDATE"]["wins"] for row in (before, after)]
            group = (index // GAMES_PER_GROUP) % history_groups
            for label, player in (("rival", rival), ("target", seat)):
                effects[label, group].append(after_wins[player] - before_wins[player])
                means[label].append(after_wins[player])
            for turn, reveal in public_reveals(after).items():
                if 3 <= turn <= 10:
                    if reveal["prediction"] != amount + 1:
                        raise ValueError("The public-history rival must use its declared static midgame policy.")
                    factual["offers"] += 1
                    factual["matchedAmount"] += reveal["actualRivalBid"] == amount
                    factual["movedEarlier"] += reveal["rivalBeforeTarget"]
        summary[name] = {
            "amount": amount,
            "winRates": {label: statistics.mean(values) for label, values in means.items()},
            "pairedWinEffects": {
                label: interval([statistics.mean(effects[label, group])
                                 for group in range(history_groups)])
                for label in ("rival", "target")
            },
            "bidEvidence": dict(factual),
        }
    return summary


def evaluate(campaign, label, amounts, worlds, anchor, evolved, fresh):
    style, setup, _ = selection_context(anchor, "experienced", worlds, fresh)
    stage = f"midgame-response-surface-{label}"
    campaign.stage(
        stage, dict.fromkeys((name for name in amounts if name != "control"), anchor["baseline"]),
        worlds, (style,),
        baseline=anchor["baseline"], lineup=setup["lineup"], shard=128,
        counter_variants=dict.fromkeys(amounts, {"minimumObservations": 16, "lookbackTurns": 16}),
        series_priors=dict.fromkeys(amounts, setup["priors"]),
        series_responses=dict.fromkeys(amounts, setup["responses"]),
        series_midgame_priors={name: [amount + 1] * worlds for name, amount in amounts.items()},
        leading_rival_variants=dict.fromkeys(amounts, evolved),
        capital_geography_variants=tuple(amounts),
        rival_targeting_lineups=dict.fromkeys(
            amounts, {"kind": "leading-rival-table-1", "offsets": [1], "policy": evolved},
        ),
    )
    groups = len(anchor["history"]) // (2 * GAMES_PER_GROUP)
    return comparison(campaign.root / stage, amounts, worlds, groups)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    target = previous / "automata-capital-evolved-targeting-20261119/result.json"
    qualifier = read(target)
    if not qualifier["passed"]:
        raise ValueError("Probe the wider preview policy once it has passed independent qualification.")
    original = previous / "automata-weighted-opening-with-pressure-20261119/plan.json"
    basis = read(original)
    evolved = qualifier["configuration"]
    amounts = {"control": MODAL_RESPONSE, **{f"bid-{amount}": amount for amount in ALTERNATIVES}}
    plan = {"amounts": amounts, "targetSha256": sha(target), "anchorSha256": sha(original),
            "developWorlds": DEVELOP, "freshWorlds": FRESH}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("A resumed response study must retain its original benchmark opponents.")
    development = evaluate(campaign, "develop", amounts, DEVELOP, basis, evolved, fresh=False)
    selected = max((name for name in development if name != "control"),
                   key=lambda name: development[name]["pairedWinEffects"]["rival"]["lower95"])
    write(root / "screen.json", {"selected": selected, "outcomes": development})
    if campaign.remaining() < 1600:
        return
    fresh = evaluate(campaign, "fresh", {"control": MODAL_RESPONSE, selected: amounts[selected]},
                     FRESH, basis, evolved, fresh=True)
    write(root / "result.json", {"selected": selected, "outcomes": fresh, "worlds": FRESH})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
