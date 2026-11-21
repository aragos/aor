"""Equal-roster paired win share. Each deal and focal seat contributes once per comparison."""

from collections import defaultdict
from math import sqrt
from statistics import NormalDist, mean, variance


def grouped(rows, key):
    groups = defaultdict(list)
    for row in rows:
        groups[key(row)].append(row)
    return groups


def roster(row):
    return row.get("roster", "market" if row.get("adopted") else "shipped")


def estimate(rows, confidence=0.95):
    """Two-sided normal interval with stratified variance, computed on paired differences."""
    if not rows or len({row["seed"] for row in rows}) != len(rows):
        raise ValueError("Each independent paired game must have one unique world seed.")
    strata = grouped(rows, lambda row: (roster(row), row["configuration"]))
    differences = {
        key: [r["candidate"] - r["control"] for r in group] for key, group in strata.items()
    }
    if min(map(len, differences.values())) < 2:
        raise ValueError("A stratified interval requires at least two worlds per roster and setup.")
    effect = mean(mean(values) for values in differences.values())
    se = sqrt(sum(variance(values) / len(values) for values in differences.values())) / len(strata)
    width = NormalDist().inv_cdf((1 + confidence) / 2) * se
    return dict(pairs=len(rows), gain=effect, lower=effect - width, upper=effect + width, se=se)


def review(rows, versus_shipped=True):
    overall = estimate(rows)
    rivals = {key: estimate(group) for key, group in grouped(rows, roster).items()}
    openings = {
        key: estimate(group)
        for key, group in grouped(
            rows,
            lambda r: (
                "drawThree" if r["configuration"].endswith("/draw-three-keep-two") else "single"
            ),
        ).items()
    }
    lower = overall["gain"] - NormalDist().inv_cdf(0.9875) * overall["se"]
    qualifies = (overall["gain"] >= 0.01 and lower > 0) if versus_shipped else overall["lower"] > 0
    qualifies = (
        qualifies
        and rivals["shipped"]["lower"] >= -0.025
        and min(r["lower"] for r in rivals.values()) >= -0.045
        and min(r["lower"] for r in openings.values()) >= -0.05
    )
    setups = {
        key: estimate(group) for key, group in grouped(rows, lambda r: r["configuration"]).items()
    }
    weakest = min(setups, key=lambda key: setups[key]["gain"])
    rank_gain = mean(row["rankDifference"] for row in rows) if "rankDifference" in rows[0] else None
    return dict(
        qualified=qualifies,
        weakestSetup=dict(configuration=weakest, **setups[weakest]),
        rankGain=rank_gain,
        overall=overall,
        lowerOneSided9875=lower,
        rosters=rivals,
        openings=openings,
        setups=len({r["configuration"] for r in rows}),
    )
