"""Test the expansion finalist against opponents that already know its opening."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from initiative_payoff_challenger import trained_responses
from initiative_series_rivals import GAMES_PER_GROUP, profiles


REMOTE = "/home/aragos/aor-worker/retargeting-adversarial-test-20261119"
STYLE = "REVEALED_UNDERCUT"
ARMS = ("control", "retargeted")


def compare(campaign, label, plan, count, *, priors=None, replies=None, fixed=None):
    stage = f"retargeting-adversarial-{label}"
    counter = {"minimumObservations": 1, "lookbackTurns": 1}
    if fixed is not None:
        counter["openingBidPrior"] = fixed + 1
    campaign.stage(
        stage, {"retargeted": plan["baseline"]}, count, (STYLE,), shard=128,
        baseline=plan["baseline"], lineup=plan["rivals"],
        counter_variants=dict.fromkeys(ARMS, counter),
        series_priors=dict.fromkeys(ARMS, priors) if priors else None,
        series_responses=dict.fromkeys(ARMS, replies) if replies else None,
        leading_rival_variants={"retargeted": plan["policy"]},
    )
    arms = {name: load_games(campaign.root / stage, name) for name in ARMS}
    deltas, rival_deltas = [], []
    for i in range(count):
        own, rival = [], []
        baseline = arms["control"][i]
        original = baseline["diagnostics"]["CANDIDATE"]["openingBid"]
        for rows in arms.values():
            row = rows[i]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            if row["seed"] != baseline["seed"] or diagnostic["openingBid"] != original:
                raise ValueError("A tactical candidate must preserve its first bid in every paired match.")
            reveal = next(entry for entry in diagnostic["publicTokenCounter"]["decisionProofs"]
                          if entry["turn"] == 1 and entry["phase"] == "order-revealed")
            group = "lateBid" if original["capitalPosition"] >= 2 else "earlyBid"
            expected = replies[i][group] if replies else fixed
            if reveal["actualRivalBid"] != expected:
                raise ValueError("The rival must play the independent learned or fixed opening.")
            if priors and reveal["prediction"] != priors[i][group]:
                raise ValueError("The learned prediction must come from previous completed games.")
            wins = row["roles"]["CANDIDATE"]["wins"]
            own.append(wins[row["seat"]])
            rival.append(wins[(row["seat"] + 1) % 4])
        deltas.append(own[1] - own[0])
        rival_deltas.append(rival[1] - rival[0])
    if replies:
        def by_history(values):
            return [statistics.mean(values[i:i + GAMES_PER_GROUP])
                    for i in range(0, count, GAMES_PER_GROUP)]
        return {"games": count, "ownWinDelta": interval(by_history(deltas)),
                "counterWinDelta": interval(by_history(rival_deltas))}
    return {"games": count, "ownWinDelta": interval(deltas),
            "counterWinDelta": interval(rival_deltas)}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, background=True, resume=resume)
    campaign.validate_runtime()
    policy_root = previous / "automata-credible-leader-pressure-20261119"
    screened = read(policy_root / "screen.json")
    nomination = next(iter(screened["nominees"]), None)
    if nomination is None or nomination[2] != "near-lead":
        raise ValueError("The challenger requires the exact screened retargeting finalist.")
    policy = read(policy_root / "plan.json")
    learner_file = previous / "automata-payoff-trained-challenger-20261119/plan.json"
    learner = read(learner_file)
    if policy["baseline"] != learner["targets"]["preview"]:
        raise ValueError("Public history must describe this exact opening already tested.")
    history = learner["histories"]["preview"]
    plan = {
        "baseline": policy["baseline"], "policy": policy["policies"]["near-lead"],
        "rivals": lineup({int(offset): value for offset, value in policy["rivals"].items()
                          if offset != "1"}),
        "payoffPriorSha256": sha(learner_file),
        "nominationSha256": sha(policy_root / "screen.json"),
        "forecast": profiles(history)["capital-modal"],
        "reply": trained_responses(history, learner["payoff"])["expected-win"],
    }
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("The experienced-rival comparison needs the original frozen history and policy.")
    result = {
        "experienced": compare(campaign, "experienced", plan, len(history),
                               priors=plan["forecast"], replies=plan["reply"]),
        "known16": compare(campaign, "known-16", plan, 768, fixed=16),
    }
    write(root / "result.json", {
        "configuration": plan["policy"], "outcomes": result,
        "passed": result["experienced"]["ownWinDelta"]["lower95"] > 0
                  and result["known16"]["ownWinDelta"]["lower95"] > -.025,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
