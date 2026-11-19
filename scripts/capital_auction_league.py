"""Challenge opening auction bids against rivals that also spend cash on capital choice."""

import argparse
from pathlib import Path
import statistics

from capital_auction_campaign import observations
from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup, trained_counter
from initiative_league import rank


REMOTE = "/home/aragos/aor-worker/capital-auction-league-20261119"


def setup(bids, rivals):
    output = {"kind": "capital-auction-study-1", "bid": bids[0] if len(bids) == 1 else None}
    if len(bids) > 1:
        output["mixedBids"] = bids
    if rivals:
        output["rivals"] = [{"offset": offset, "bid": bid}
                            for offset, bid in enumerate(rivals, start=1)]
    return output


def evaluate(campaign, label, candidates, count, plan, cases):
    outcomes = {name: {} for name in candidates}
    roster = {int(offset): value for offset, value in plan["rivals"].items()}
    for name, style, auction in cases:
        stage = f"{label}-{name}"
        bids = {role: setup(values, auction) for role, values in {"control": [], **candidates}.items()}
        adversarial = style == "REVEALED_UNDERCUT"
        opposition = lineup({offset: value for offset, value in roster.items()
                             if not adversarial or offset != 1})
        options = {role: plan["counter"] for role in bids} if adversarial else None
        evaluation = campaign.stage(
            stage, dict.fromkeys(candidates, plan["preview"]), count, (style,), shard=64,
            baseline=plan["preview"], lineup=opposition, counter_variants=options, capital_variants=bids,
        )
        control_setup = observations(campaign.root / stage, "control")
        for role, answer in evaluation.items():
            outcomes[role][name] = dict(answer["outcomes"][style], setup={
                "control": control_setup, "candidate": observations(campaign.root / stage, role)})
    return outcomes


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, background=True, batch_size=2)
    campaign.validate_runtime()
    capital_report = previous / "automata-capital-auction-20261118/result.json"
    earlier = read(capital_report)
    branch = next(iter(earlier["outcomes"].values()))
    if branch["ordinary"]["lower95"] <= 0 or branch["familiar"]["mean"] <= -.025:
        write(root / "result.json", {"status": "capital-effect-did-not-replicate"})
        return
    plan_path = root / "plan.json"
    if resume and plan_path.is_file():
        plan = read(plan_path)
        if plan["originalReportSha256"] != sha(capital_report):
            raise ValueError("The earlier capital result must match the original plan.")
    else:
        table = previous / "automata-heterogeneous-20261118"
        qualified = read(table / "table-fresh.json")
        if qualified["passed"] is not True:
            raise ValueError("The capital league requires the qualified preview.")
        roster = read(table / "roster.json")
        bids = earlier["bid"]
        candidates = {
            "original": [bids],
            "small": [1],
            "moderate": [5],
            "larger": [8],
            "mixed-low": [0, 1, 4],
            "mixed-wide": [1, 4, 8],
            "mixed-contest": [4, 5, 8],
        }
        plan = {"preview": qualified["config"], "rivals": roster["rivals"],
                "counter": trained_counter(previous, roster["counter"]), "candidates": candidates,
                "originalReportSha256": sha(capital_report)}
        write(plan_path, plan)
    cases = (("usual-auction", "QUALIFIED", []),
             ("contested-auction", "QUALIFIED", [1, 4, 7]),
             ("prepared-rival", "REVEALED_UNDERCUT", [5, 1, 3]))
    screen = evaluate(campaign, "capital-league-screen", plan["candidates"], 128, plan, cases)
    ranked = rank(screen)
    selected = [name for name in ranked if min(point["mean"] for point in screen[name].values()) >= -.025
                and statistics.mean(point["mean"] for point in screen[name].values()) >= .025][:2]
    payload = {"screen": screen, "nominees": selected}
    screen_path = root / "screen.json"
    if resume and screen_path.is_file():
        if read(screen_path) != payload:
            raise ValueError("The capital league report differs from its recorded shards.")
    else:
        write(screen_path, payload)
    if not selected or campaign.remaining() < 3200:
        write(root / "result.json", {"status": "capital-league-screen-complete", "nominees": selected})
        return
    nominees = {name: plan["candidates"][name] for name in selected}
    fresh = evaluate(campaign, "capital-league-fresh", nominees, 512, plan, cases)
    choice = rank(fresh)[0]
    write(root / "result.json", {"status": "capital-league-fresh-complete", "choice": choice,
                                 "bids": nominees[choice], "fresh": fresh,
                                 "passed":all(point["lower95"] > 0 for point in fresh[choice].values())})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
